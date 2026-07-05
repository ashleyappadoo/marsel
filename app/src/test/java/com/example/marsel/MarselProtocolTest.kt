package com.example.marsel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests unitaires purs du protocole MRN (Section 7). Aucune dépendance Android :
 * MarselProtocol et DedupLedger sont testables directement en JVM.
 */
class MarselProtocolTest {

    // ── Cascade réseau (F1) ─────────────────────────────────────────────
    @Test
    fun mobileValidated_isMobileStable() {
        val q = MarselProtocol.classifyNetworkQuality(
            hasCellular = true, hasWifi = false, hasEthernet = false,
            validated = true, hasInternet = true
        )
        assertEquals("MOBILE_STABLE", q)
    }

    @Test
    fun wifiValidated_isWifiStable() {
        val q = MarselProtocol.classifyNetworkQuality(
            hasCellular = false, hasWifi = true, hasEthernet = false,
            validated = true, hasInternet = true
        )
        assertEquals("WIFI_STABLE", q)
    }

    @Test
    fun wifiNotValidated_isNone() {
        // WiFi connecté mais sans accès internet réel (portail captif) → NONE (F1)
        val q = MarselProtocol.classifyNetworkQuality(
            hasCellular = false, hasWifi = true, hasEthernet = false,
            validated = false, hasInternet = true
        )
        assertEquals("NONE", q)
    }

    @Test
    fun noInternetCapability_isNone() {
        val q = MarselProtocol.classifyNetworkQuality(
            hasCellular = true, hasWifi = false, hasEthernet = false,
            validated = true, hasInternet = false
        )
        assertEquals("NONE", q)
    }

    @Test
    fun mobilePreferredOverWifi() {
        val q = MarselProtocol.classifyNetworkQuality(
            hasCellular = true, hasWifi = true, hasEthernet = false,
            validated = true, hasInternet = true
        )
        assertEquals("MOBILE_STABLE", q)
    }

    // ── Filtrage des pairs (Section 2) ──────────────────────────────────
    @Test
    fun connectTarget_onlyMarselPeer() {
        val visible = listOf("aa:tv", "bb:printer", "cc:marsel")
        val marsel = setOf("cc:marsel")
        assertEquals("cc:marsel", MarselProtocol.selectConnectTarget(visible, marsel))
    }

    @Test
    fun connectTarget_noneWhenNoMarselPeer() {
        // Aucun appareil visible n'est un pair Marsel → jamais de connect() (B1)
        val visible = listOf("aa:tv", "bb:printer")
        assertNull(MarselProtocol.selectConnectTarget(visible, emptySet()))
    }

    @Test
    fun peerExpiresAfterTtl() {
        assertFalse(MarselProtocol.isPeerExpired(lastSeenMs = 1_000, nowMs = 40_000, ttlMs = 60_000))
        assertTrue(MarselProtocol.isPeerExpired(lastSeenMs = 1_000, nowMs = 70_000, ttlMs = 60_000))
    }

    // ── Déduplication persistée (E1/E2/F3) ──────────────────────────────
    private class MapStore(val m: MutableMap<String, Long> = mutableMapOf()) : DedupLedger.KeyValueStore {
        override fun all(): Map<String, Long> = HashMap(m)
        override fun put(key: String, value: Long) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
    }

    @Test
    fun dedup_firstUnseenThenSeen() {
        var now = 1_000L
        val ledger = DedupLedger(MapStore(), ttlMs = 600_000L) { now }
        assertFalse(ledger.checkAndMark("msg", "id-1"))  // 1re fois
        assertTrue(ledger.checkAndMark("msg", "id-1"))   // déjà vu
    }

    @Test
    fun dedup_survivesRestart() {
        // E1 : même store, nouveau ledger (= restart de l'app) → toujours vu
        val store = MapStore()
        var now = 1_000L
        DedupLedger(store, ttlMs = 600_000L) { now }.checkAndMark("sms", "emg-42")
        val afterRestart = DedupLedger(store, ttlMs = 600_000L) { now }
        assertTrue(afterRestart.checkAndMark("sms", "emg-42"))
    }

    @Test
    fun dedup_expiresByAge() {
        val store = MapStore()
        var now = 1_000L
        val ledger = DedupLedger(store, ttlMs = 10_000L) { now }
        ledger.checkAndMark("msg", "old")
        now = 100_000L  // bien au-delà du TTL
        assertFalse(ledger.checkAndMark("msg", "old"))  // expiré → non vu
    }

    @Test
    fun dedup_kindsAreIsolated() {
        var now = 1_000L
        val ledger = DedupLedger(MapStore(), ttlMs = 600_000L) { now }
        ledger.checkAndMark("msg", "x")
        assertFalse(ledger.isSeen("sms", "x"))  // même id, espace différent
        assertTrue(ledger.isSeen("msg", "x"))
    }

    // ── Instances de service distinctes (C3) ────────────────────────────
    @Test
    fun distinctAlertInstancesPerEmergency() {
        val a = MarselProtocol.alertInstanceName("emg-aaaaaaaa")
        val b = MarselProtocol.alertInstanceName("emg-bbbbbbbb")
        assertFalse("deux urgences → deux instances distinctes", a == b)
        assertTrue(a.startsWith("marsel-alert-"))
    }

    @Test
    fun shouldReplaceLocalAlert_logic() {
        assertTrue(MarselProtocol.shouldReplaceLocalAlert(null, "m1"))
        assertTrue(MarselProtocol.shouldReplaceLocalAlert("m1", "m2"))
        assertFalse(MarselProtocol.shouldReplaceLocalAlert("m1", "m1"))
    }

    // ── Timer 20 minutes (Section 4) ────────────────────────────────────
    @Test
    fun timeoutRemaining_midway() {
        val start = 1_000_000L
        val remaining = MarselProtocol.timeoutRemainingMs(start, start + 5 * 60_000L)
        assertEquals(15 * 60_000L, remaining)
    }

    @Test
    fun timeoutRemaining_pastDeadlineIsZero() {
        val start = 1_000_000L
        val remaining = MarselProtocol.timeoutRemainingMs(start, start + 25 * 60_000L)
        assertEquals(0L, remaining)  // déclenchement immédiat au restart
    }

    // ── TXT record : encodage/décodage E, P, R, F, T (I4) ───────────────
    @Test
    fun txtRoundTrip_emergencyWithContacts() {
        val json = """{"type":"MARSEL_EMERGENCY","messageId":"emg-1","emergencyId":"emg-1","pseudo":"Alice","lat":48.8566,"lng":2.3522,"timestamp":1700000000000,"hopCount":0,"contacts":[{"mobile":"+33611"},{"mobile":"+33622"}]}"""
        val rec = MarselProtocol.buildTxtRecord("E", json, 0)!!
        assertEquals("E", rec["y"])
        assertEquals("emg-1", rec["i"])
        assertEquals("+33611,+33622", rec["c"])

        val back = MarselProtocol.txtRecordToJson(rec, 0)!!
        assertEquals("MARSEL_EMERGENCY", MarselProtocol.extractJsonString(back, "type"))
        assertEquals("Alice", MarselProtocol.extractJsonString(back, "pseudo"))
        assertEquals(listOf("+33611", "+33622"), MarselProtocol.extractContactMobiles(back))
    }

    @Test
    fun txtRoundTrip_allTypes() {
        val types = mapOf(
            "MARSEL_EMERGENCY" to "E",
            "MARSEL_POSITION_UPDATE" to "P",
            "MARSEL_EMERGENCY_RESOLVED" to "R",
            "MARSEL_RESOLVED_SMS_REQUEST" to "F",
            "MARSEL_TIMEOUT_SMS_REQUEST" to "T"
        )
        for ((type, short) in types) {
            val json = """{"type":"$type","messageId":"m","emergencyId":"e","pseudo":"Bob","lat":1.0,"lng":2.0,"timestamp":10,"hopCount":1,"contacts":[]}"""
            val rec = MarselProtocol.buildTxtRecord(short, json, 0)!!
            assertEquals(short, rec["y"])
            val back = MarselProtocol.txtRecordToJson(rec, 0)!!
            assertEquals(type, MarselProtocol.extractJsonString(back, "type"))
        }
    }

    @Test
    fun txtRecord_audioFlagPreserved() {
        val json = """{"type":"MARSEL_RESOLVED_SMS_REQUEST","messageId":"m","emergencyId":"e","pseudo":"Bob","lat":1.0,"lng":2.0,"timestamp":10,"hopCount":0,"audio":"1","contacts":[{"mobile":"+33600"}]}"""
        val rec = MarselProtocol.buildTxtRecord("F", json, 0)!!
        assertEquals("1", rec["u"])
        val back = MarselProtocol.txtRecordToJson(rec, 0)!!
        assertEquals("1", MarselProtocol.extractJsonString(back, "audio"))
    }

    @Test
    fun extractJsonString_toleratesEscapedQuotes() {
        // I4 : un pseudo contenant un guillemet échappé ne casse pas l'extraction
        val json = """{"pseudo":"Al \" ice","lat":1.0}"""
        assertEquals("Al \" ice", MarselProtocol.extractJsonString(json, "pseudo"))
        assertEquals(1.0, MarselProtocol.extractJsonNumber(json, "lat")!!, 0.0001)
    }

    @Test
    fun buildTxtRecord_sanitizesPseudoQuotes() {
        // Un pseudo avec guillemets est nettoyé pour ne pas corrompre le TXT record
        val json = """{"type":"MARSEL_EMERGENCY","messageId":"m","emergencyId":"e","pseudo":"a\"b","lat":1.0,"lng":2.0,"timestamp":10,"hopCount":0,"contacts":[]}"""
        val rec = MarselProtocol.buildTxtRecord("E", json, 0)!!
        assertFalse("pas de guillemet dans le pseudo TXT", rec["p"]!!.contains("\""))
    }
}
