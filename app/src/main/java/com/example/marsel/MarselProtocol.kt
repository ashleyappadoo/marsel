package com.example.marsel

/**
 * Logique pure du protocole Marsel Relay Network (MRN).
 *
 * Aucune dépendance Android : tout est testable en JUnit pur (Section 7).
 * MainActivity délègue ici l'encodage/décodage des TXT records DNS-SD,
 * l'extraction JSON, le nommage des instances de service et la
 * classification de la qualité réseau.
 */
object MarselProtocol {

    // ── Types de paquets ────────────────────────────────────────────────
    const val TYPE_EMERGENCY = "MARSEL_EMERGENCY"
    const val TYPE_POSITION = "MARSEL_POSITION_UPDATE"
    const val TYPE_RESOLVED = "MARSEL_EMERGENCY_RESOLVED"
    const val TYPE_RESOLVED_SMS_REQUEST = "MARSEL_RESOLVED_SMS_REQUEST"
    const val TYPE_TIMEOUT_SMS_REQUEST = "MARSEL_TIMEOUT_SMS_REQUEST"

    // TXT short types (clé "y" du record)
    const val SHORT_EMERGENCY = "E"
    const val SHORT_POSITION = "P"
    const val SHORT_RESOLVED = "R"
    const val SHORT_RESOLVED_SMS_REQUEST = "F"
    const val SHORT_TIMEOUT_SMS_REQUEST = "T"

    const val EMERGENCY_TIMEOUT_MS = 20 * 60_000L

    fun shortTypeOf(type: String): String? = when (type) {
        TYPE_EMERGENCY -> SHORT_EMERGENCY
        TYPE_POSITION -> SHORT_POSITION
        TYPE_RESOLVED -> SHORT_RESOLVED
        TYPE_RESOLVED_SMS_REQUEST -> SHORT_RESOLVED_SMS_REQUEST
        TYPE_TIMEOUT_SMS_REQUEST -> SHORT_TIMEOUT_SMS_REQUEST
        else -> null
    }

    fun typeOfShort(shortType: String): String? = when (shortType) {
        SHORT_EMERGENCY -> TYPE_EMERGENCY
        SHORT_POSITION -> TYPE_POSITION
        SHORT_RESOLVED -> TYPE_RESOLVED
        SHORT_RESOLVED_SMS_REQUEST -> TYPE_RESOLVED_SMS_REQUEST
        SHORT_TIMEOUT_SMS_REQUEST -> TYPE_TIMEOUT_SMS_REQUEST
        else -> null
    }

    // ── Extraction JSON sans bibliothèque externe ───────────────────────
    // Tolère les guillemets/antislashs échappés dans les valeurs (I4).
    fun extractJsonString(json: String, key: String): String? {
        val pattern = """"$key"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
        val raw = pattern.find(json)?.groupValues?.getOrNull(1) ?: return null
        return raw
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    fun extractJsonInt(json: String, key: String): Int? {
        val pattern = """"$key"\s*:\s*(\d+)""".toRegex()
        return pattern.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun extractJsonNumber(json: String, key: String): Double? {
        val pattern = """"$key"\s*:\s*(-?\d+(?:\.\d+)?)""".toRegex()
        return pattern.find(json)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }

    /** Numéros mobiles du tableau contacts, dans l'ordre du JSON. */
    fun extractContactMobiles(json: String): List<String> {
        return """"mobile"\s*:\s*"([^"]+)"""".toRegex()
            .findAll(json)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .filter { it.isNotEmpty() }
            .toList()
    }

    /** Valeur sûre pour un TXT record (pas de guillemets/antislash, taille bornée). */
    fun sanitizeTxtValue(value: String, maxLen: Int = 24): String =
        value.replace("\\", "").replace("\"", "").take(maxLen)

    // ── Encodage TXT record (paquet JSON → map compacte) ────────────────
    // Clés : y=type court, i=messageId, e=emergencyId, p=pseudo, a=lat,
    // o=lng, s=timestamp, h=hopCount, c=numéros mobiles (chaîne relay
    // hors-ligne), u=1 si un enregistrement audio existe.
    fun buildTxtRecord(shortType: String, json: String, nowMs: Long): Map<String, String>? {
        val msgId = extractJsonString(json, "messageId") ?: return null
        val emergencyId = extractJsonString(json, "emergencyId")
            ?: extractJsonString(json, "id") ?: msgId
        val pseudo = sanitizeTxtValue(extractJsonString(json, "pseudo") ?: "")
        val lat = extractJsonNumber(json, "lat") ?: return null
        val lng = extractJsonNumber(json, "lng") ?: return null
        val ts = extractJsonNumber(json, "timestamp")?.toLong() ?: nowMs
        val hop = extractJsonInt(json, "hopCount") ?: 0
        val contacts = extractContactMobiles(json).take(5).joinToString(",")
        val audio = extractJsonString(json, "audio") == "1"
        // relaySms="1" : l'émetteur n'a PAS pu envoyer les SMS lui-même (hors réseau)
        // → un relais avec réseau doit les envoyer à sa place. "0"/absent : l'émetteur
        // a déjà SMS-é ses proches, le relais ne doit PAS renvoyer (anti double-SMS).
        val relaySms = extractJsonString(json, "relaySms") == "1"

        val record = mutableMapOf(
            "y" to shortType,
            "i" to msgId.take(40),
            "e" to emergencyId.take(40),
            "p" to pseudo,
            "a" to String.format(java.util.Locale.US, "%.5f", lat),
            "o" to String.format(java.util.Locale.US, "%.5f", lng),
            "s" to ts.toString(),
            "h" to hop.toString()
        )
        if (contacts.isNotEmpty()) record["c"] = contacts.take(240)
        if (audio) record["u"] = "1"
        if (relaySms) record["m"] = "1"
        return record
    }

    // ── Décodage TXT record (map → paquet JSON complet) ─────────────────
    fun txtRecordToJson(record: Map<String, String?>, nowMs: Long): String? {
        val msgId = record["i"] ?: return null
        val shortType = record["y"] ?: return null
        val type = typeOfShort(shortType) ?: return null
        val pseudo = sanitizeTxtValue(record["p"] ?: "Utilisateur")
        val lat = record["a"]?.toDoubleOrNull() ?: return null
        val lng = record["o"]?.toDoubleOrNull() ?: return null
        val ts = record["s"]?.toLongOrNull() ?: nowMs
        val hop = record["h"]?.toIntOrNull() ?: 0
        val emergencyId = record["e"] ?: msgId
        val audio = if (record["u"] == "1") "1" else "0"
        val relaySms = if (record["m"] == "1") "1" else "0"

        val contactsJson = (record["c"] ?: "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(",", "[", "]") { num ->
                """{"mobile":"${sanitizeTxtValue(num, 20)}"}"""
            }

        return """{"type":"$type","messageId":"$msgId","id":"$emergencyId","emergencyId":"$emergencyId","pseudo":"$pseudo","lat":$lat,"lng":$lng,"timestamp":$ts,"hopCount":$hop,"maxHops":10,"audio":"$audio","relaySms":"$relaySms","contacts":$contactsJson}"""
    }

    // ── Noms d'instance de service DNS-SD ───────────────────────────────
    // C3 : une instance DISTINCTE par urgence pour que deux alertes
    // (locale + relayée) coexistent sans s'écraser.
    fun instanceSuffix(messageId: String): String {
        val s = messageId.filter { it.isLetterOrDigit() }.takeLast(8).lowercase()
        return s.ifEmpty { "x" }
    }

    fun alertInstanceName(messageId: String) = "marsel-alert-" + instanceSuffix(messageId)
    fun resolvedInstanceName(messageId: String) = "marsel-res-" + instanceSuffix(messageId)
    fun smsReqInstanceName(messageId: String) = "marsel-req-" + instanceSuffix(messageId)
    const val POSITION_INSTANCE_NAME = "marsel-pos"

    // C2 : faut-il remplacer le service d'alerte local déjà enregistré ?
    fun shouldReplaceLocalAlert(currentMessageId: String?, newMessageId: String): Boolean =
        currentMessageId == null || currentMessageId != newMessageId

    // ── Cascade réseau (F1 / Section 1) ─────────────────────────────────
    // Un transport présent mais non VALIDATED (portail captif, box sans
    // internet) est traité comme NONE.
    fun classifyNetworkQuality(
        hasCellular: Boolean,
        hasWifi: Boolean,
        hasEthernet: Boolean,
        validated: Boolean,
        hasInternet: Boolean
    ): String {
        if (!validated || !hasInternet) return "NONE"
        return when {
            hasCellular -> "MOBILE_STABLE"
            hasWifi || hasEthernet -> "WIFI_STABLE"
            else -> "NONE"
        }
    }

    // ── Timer 20 minutes (Section 4) ────────────────────────────────────
    fun timeoutRemainingMs(
        emergencyStartMs: Long,
        nowMs: Long,
        timeoutMs: Long = EMERGENCY_TIMEOUT_MS
    ): Long = (emergencyStartMs + timeoutMs - nowMs).coerceAtLeast(0L)

    // ── Expiration des pairs Marsel (Section 2) ─────────────────────────
    fun isPeerExpired(lastSeenMs: Long, nowMs: Long, ttlMs: Long = 60_000L): Boolean =
        nowMs - lastSeenMs > ttlMs

    // Cible de connect() : premier appareil visible qui est AUSSI un pair Marsel
    // confirmé. Retourne null si aucun (→ pas de connect vers un non-Marsel, B1).
    fun selectConnectTarget(
        visibleAddresses: List<String>,
        marselAddresses: Set<String>
    ): String? = visibleAddresses.firstOrNull { marselAddresses.contains(it) }
}

/**
 * Déduplication persistée avec fenêtre glissante (E1/E2/F3).
 *
 * - `kind` sépare les espaces de noms : "msg" = messages traités,
 *   "sms" = SMS déjà envoyés (smsHandled).
 * - Chaque entrée porte un timestamp ; expirée après [ttlMs].
 * - checkAndMark rafraîchit le timestamp à chaque vue : tant qu'un
 *   TXT record est re-reçu en continu (alerte active), il reste dédupliqué.
 * - Le stockage est injectable : SharedPreferences côté app, HashMap en test.
 */
class DedupLedger(
    private val store: KeyValueStore,
    private val ttlMs: Long = 10 * 60_000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    interface KeyValueStore {
        fun all(): Map<String, Long>
        fun put(key: String, value: Long)
        fun remove(key: String)
    }

    /** true si [id] a déjà été vu il y a moins de [ttlMs]. Marque/rafraîchit toujours l'entrée. */
    @Synchronized
    fun checkAndMark(kind: String, id: String): Boolean {
        val now = clock()
        val snapshot = store.all()
        // Nettoyage à chaque écriture (E2 : éviction par âge, pas aléatoire)
        for ((k, ts) in snapshot) {
            if (now - ts > ttlMs) store.remove(k)
        }
        val key = "$kind:$id"
        val prev = snapshot[key]
        val seen = prev != null && now - prev <= ttlMs
        store.put(key, now)
        return seen
    }

    /** Consultation sans marquage. */
    @Synchronized
    fun isSeen(kind: String, id: String): Boolean {
        val ts = store.all()["$kind:$id"] ?: return false
        return clock() - ts <= ttlMs
    }
}
