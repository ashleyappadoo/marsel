package com.example.marsel

/**
 * File unique des opérations WifiP2pManager (TODO.md, prérequis du
 * ForegroundService).
 *
 * Le framework WiFi P2P refuse une opération lancée pendant qu'une autre est
 * en vol sur le même canal (reason=2 / BUSY, ou reason=3 quand une requête de
 * service est effacée en plein vol). Ici, une seule opération est en vol à la
 * fois : la suivante démarre quand la précédente a répondu (onSuccess /
 * onFailure → `done()`), ou au bout de [timeoutMs] si le framework ne répond
 * jamais. [clear] abandonne tout (canal recréé ou moteur arrêté) : une réponse
 * tardive d'une opération abandonnée est ignorée.
 *
 * Classe pure, sans Android, pour être testée en JVM. Toutes les méthodes
 * s'appellent sur le même thread que [Scheduler] (le thread principal).
 */
class P2pOpQueue(
    private val scheduler: Scheduler,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val onTimeout: (label: String) -> Unit = {}
) {
    interface Scheduler {
        fun post(task: Runnable)
        fun postDelayed(task: Runnable, delayMs: Long)
        fun cancel(task: Runnable)
    }

    private class Op(val label: String, val body: (done: () -> Unit) -> Unit)

    private val pending = ArrayDeque<Op>()
    private var running: Op? = null
    private var timeoutTask: Runnable? = null
    private var generation = 0
    private val runNextTask = Runnable { runNext() }

    /** Opérations en attente + celle en vol. */
    val size: Int get() = pending.size + if (running != null) 1 else 0

    /** Libellé de l'opération en vol (null si aucune). */
    val runningLabel: String? get() = running?.label

    /**
     * Ajoute une opération. [body] lance l'appel WifiP2pManager et DOIT appeler
     * `done()` depuis son onSuccess/onFailure (ou tout de suite s'il n'a rien
     * lancé). Un `done()` en double ou tardif est sans effet.
     */
    fun enqueue(label: String, body: (done: () -> Unit) -> Unit) = enqueue(label, false, body)

    /**
     * [coalesce] : si une opération de même libellé attend déjà (pas encore
     * lancée), celle-ci est ignorée — elle ferait exactement la même chose.
     * Évite l'accumulation de relances de découverte quand le framework est lent.
     * @return false si l'opération a été fusionnée avec une opération en attente.
     */
    fun enqueue(label: String, coalesce: Boolean, body: (done: () -> Unit) -> Unit): Boolean {
        if (coalesce && pending.any { it.label == label }) return false
        pending.addLast(Op(label, body))
        if (running == null) scheduleRunNext()
        return true
    }

    /** Abandonne l'opération en vol et toutes celles en attente. */
    fun clear() {
        generation++
        pending.clear()
        running = null
        timeoutTask?.let { scheduler.cancel(it) }
        timeoutTask = null
        scheduler.cancel(runNextTask)
    }

    private fun scheduleRunNext() {
        // Toujours posté : un enqueue fait depuis le callback d'une opération
        // ne relance pas la file en récursion.
        scheduler.cancel(runNextTask)
        scheduler.post(runNextTask)
    }

    private fun runNext() {
        if (running != null) return
        val op = pending.removeFirstOrNull() ?: return
        running = op
        val gen = generation
        var finished = false

        val done: () -> Unit = done@{
            if (finished || gen != generation || running !== op) return@done
            finished = true
            timeoutTask?.let { scheduler.cancel(it) }
            timeoutTask = null
            running = null
            if (pending.isNotEmpty()) scheduleRunNext()
        }

        val timeout = Runnable {
            if (!finished && gen == generation && running === op) {
                onTimeout(op.label)
                done()
            }
        }
        timeoutTask = timeout
        scheduler.postDelayed(timeout, timeoutMs)

        try {
            op.body(done)
        } catch (e: Exception) {
            // L'appelant journalise ses propres erreurs : la file ne doit
            // jamais rester bloquée sur une opération qui a levé.
            done()
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}
