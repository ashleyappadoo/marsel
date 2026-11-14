package com.example.marsel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * File unique des opérations WiFi P2P : une seule en vol, ordre conservé,
 * délai maximal, abandon propre.
 */
class P2pOpQueueTest {

    /** Ordonnanceur manuel : le temps n'avance que via advance(). */
    private class FakeScheduler : P2pOpQueue.Scheduler {
        private data class Task(val at: Long, val seq: Long, val r: Runnable)
        private val tasks = mutableListOf<Task>()
        private var now = 0L
        private var seq = 0L

        override fun post(task: Runnable) { tasks += Task(now, seq++, task) }
        override fun postDelayed(task: Runnable, delayMs: Long) { tasks += Task(now + delayMs, seq++, task) }
        override fun cancel(task: Runnable) { tasks.removeAll { it.r === task } }

        /** Exécute tout ce qui est dû jusqu'à now + ms (y compris ce qui est posté entre-temps). */
        fun advance(ms: Long = 0) {
            val target = now + ms
            while (true) {
                val next = tasks.filter { it.at <= target }.minWithOrNull(compareBy({ it.at }, { it.seq })) ?: break
                tasks.remove(next)
                now = next.at
                next.r.run()
            }
            now = target
        }
    }

    @Test
    fun oneOperationInFlightAtATime_inOrder() {
        val s = FakeScheduler()
        val q = P2pOpQueue(s)
        val log = mutableListOf<String>()
        val dones = mutableMapOf<String, () -> Unit>()
        q.enqueue("A") { done -> log += "A"; dones["A"] = done }
        q.enqueue("B") { done -> log += "B"; dones["B"] = done }
        s.advance()
        assertEquals(listOf("A"), log)          // B attend la réponse de A
        assertEquals("A", q.runningLabel)
        dones.getValue("A")()
        s.advance()
        assertEquals(listOf("A", "B"), log)
        dones.getValue("B")()
        s.advance()
        assertNull(q.runningLabel)
        assertEquals(0, q.size)
    }

    @Test
    fun timeout_unblocksTheQueue() {
        val s = FakeScheduler()
        val timedOut = mutableListOf<String>()
        val q = P2pOpQueue(s, timeoutMs = 5_000) { timedOut += it }
        val log = mutableListOf<String>()
        q.enqueue("silencieuse") { log += "silencieuse" }   // ne répond jamais
        q.enqueue("suivante") { done -> log += "suivante"; done() }
        s.advance(4_999)
        assertEquals(listOf("silencieuse"), log)
        s.advance(1)
        assertEquals(listOf("silencieuse"), timedOut)
        assertEquals(listOf("silencieuse", "suivante"), log)
    }

    @Test
    fun duplicateOrLateDone_isIgnored() {
        val s = FakeScheduler()
        val q = P2pOpQueue(s, timeoutMs = 1_000)
        val log = mutableListOf<String>()
        var doneA: (() -> Unit)? = null
        var doneB: (() -> Unit)? = null
        q.enqueue("A") { done -> log += "A"; doneA = done }
        q.enqueue("B") { done -> log += "B"; doneB = done }
        q.enqueue("C") { done -> log += "C"; done() }
        s.advance()
        doneA!!()
        doneA!!()                                  // double réponse : ne fait pas sauter B
        s.advance()
        assertEquals(listOf("A", "B"), log)
        assertEquals("B", q.runningLabel)
        doneB!!()
        s.advance()
        assertEquals(listOf("A", "B", "C"), log)
    }

    @Test
    fun clear_dropsPendingAndIgnoresLateAnswer() {
        val s = FakeScheduler()
        val q = P2pOpQueue(s)
        val log = mutableListOf<String>()
        var doneOld: (() -> Unit)? = null
        q.enqueue("ancien-canal") { done -> log += "ancien-canal"; doneOld = done }
        q.enqueue("abandonnée") { done -> log += "abandonnée"; done() }
        s.advance()
        q.clear()
        q.enqueue("nouveau-canal") { done -> log += "nouveau-canal"; done() }
        s.advance()
        doneOld!!()                                // réponse tardive de l'ancien canal
        s.advance(10_000)
        assertEquals(listOf("ancien-canal", "nouveau-canal"), log)
        assertEquals(0, q.size)
    }

    @Test
    fun exceptionInOperation_doesNotBlockTheQueue() {
        val s = FakeScheduler()
        val q = P2pOpQueue(s)
        val log = mutableListOf<String>()
        q.enqueue("plante") { throw IllegalStateException("canal fermé") }
        q.enqueue("ok") { done -> log += "ok"; done() }
        s.advance()
        assertEquals(listOf("ok"), log)
    }

    @Test
    fun enqueueFromCallback_runsAfterAlreadyQueuedOps() {
        val s = FakeScheduler()
        val q = P2pOpQueue(s)
        val log = mutableListOf<String>()
        q.enqueue("clear") { done ->
            log += "clear"
            q.enqueue("add") { d -> log += "add"; d() }   // chaîne lancée depuis la réponse
            done()
        }
        q.enqueue("autre") { done -> log += "autre"; done() }
        s.advance()
        assertEquals(listOf("clear", "autre", "add"), log)
    }

    @Test
    fun coalesce_skipsIdenticalPendingOperation_butNotTheRunningOne() {
        val s = FakeScheduler()
        val q = P2pOpQueue(s)
        val log = mutableListOf<String>()
        var doneFirst: (() -> Unit)? = null
        q.enqueue("discover", true) { done -> log += "discover#1"; doneFirst = done }
        s.advance()                                           // #1 en vol
        assertEquals(true, q.enqueue("discover", true) { done -> log += "discover#2"; done() })
        assertEquals(false, q.enqueue("discover", true) { done -> log += "discover#3"; done() })
        assertEquals(true, q.enqueue("autre", true) { done -> log += "autre"; done() })
        doneFirst!!()
        s.advance()
        assertEquals(listOf("discover#1", "discover#2", "autre"), log)
    }

    @Test
    fun synchronousDone_stillRunsNextOperation() {
        val s = FakeScheduler()
        val q = P2pOpQueue(s)
        val log = mutableListOf<String>()
        repeat(5) { i -> q.enqueue("op$i") { done -> log += "op$i"; done() } }
        s.advance()
        assertEquals((0 until 5).map { "op$it" }, log)
        assertEquals(0, q.size)
    }
}
