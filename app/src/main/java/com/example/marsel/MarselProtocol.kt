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
    // ACK : un relais a RÉELLEMENT envoyé les SMS (accusé système Android) —
    // remonte de proche en proche jusqu'à l'émetteur pour l'informer.
    const val TYPE_SMS_ACK = "MARSEL_SMS_ACK"

    // TXT short types (clé "y" du record)
    const val SHORT_EMERGENCY = "E"
    const val SHORT_POSITION = "P"
    const val SHORT_RESOLVED = "R"
    const val SHORT_RESOLVED_SMS_REQUEST = "F"
    const val SHORT_TIMEOUT_SMS_REQUEST = "T"
    const val SHORT_SMS_ACK = "S"

    const val EMERGENCY_TIMEOUT_MS = 20 * 60_000L

    // Plafond de sauts du maillage : borne la propagation ET le délai avant
    // que l'émetteur conclue à l'échec (notification « SMS impossibles »).
    const val MAX_HOPS = 5

    fun shortTypeOf(type: String): String? = when (type) {
        TYPE_EMERGENCY -> SHORT_EMERGENCY
        TYPE_POSITION -> SHORT_POSITION
        TYPE_RESOLVED -> SHORT_RESOLVED
        TYPE_RESOLVED_SMS_REQUEST -> SHORT_RESOLVED_SMS_REQUEST
        TYPE_TIMEOUT_SMS_REQUEST -> SHORT_TIMEOUT_SMS_REQUEST
        TYPE_SMS_ACK -> SHORT_SMS_ACK
        else -> null
    }

    fun typeOfShort(shortType: String): String? = when (shortType) {
        SHORT_EMERGENCY -> TYPE_EMERGENCY
        SHORT_POSITION -> TYPE_POSITION
        SHORT_RESOLVED -> TYPE_RESOLVED
        SHORT_RESOLVED_SMS_REQUEST -> TYPE_RESOLVED_SMS_REQUEST
        SHORT_TIMEOUT_SMS_REQUEST -> TYPE_TIMEOUT_SMS_REQUEST
        SHORT_SMS_ACK -> TYPE_SMS_ACK
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

    // ── Anonymisation (TODO.md §2) ───────────────────────────────────────
    // Le vrai nom/pseudo de l'émetteur n'est communiqué qu'à ses proches
    // (SMS local, composé depuis l'état JS local — jamais depuis un paquet
    // reçu). Tout ce qui transite sur le MRN (TXT DNS-SD ET socket) ne doit
    // porter qu'un identifiant d'alerte pseudonymisé, dérivé de façon
    // déterministe de l'emergencyId : chaque appareil du réseau calcule le
    // même alias indépendamment, sans qu'aucun champ pseudo supplémentaire
    // n'ait besoin de circuler.
    //
    // Limite connue (documentée dans TODO.md §2.3) : un proche qui reçoit
    // l'alerte via le MRN (pas seulement par SMS) verra lui aussi l'alias,
    // faute d'un mécanisme de reconnaissance « je suis un proche déclaré de
    // cet émetteur » — ce mécanisme (appairage) reste à concevoir.
    fun deriveAlertAlias(emergencyId: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(emergencyId.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02X".format(it) }.take(6)
        return "Alerte Marsel #$hex"
    }

    private val PSEUDO_FIELD_REGEX = Regex("\"pseudo\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /**
     * Remplace le champ "pseudo" d'un paquet JSON par l'alias pseudonymisé
     * dérivé de son emergencyId — à appeler UNE SEULE FOIS, au moment où un
     * paquet quitte le contexte local de l'émetteur pour être diffusé sur
     * le MRN (DNS-SD et/ou socket). N'affecte jamais l'état JS local
     * (marsel_user, marsel_emergency…) : ceux-ci gardent le vrai pseudo
     * pour l'affichage local et la composition des SMS aux propres contacts.
     *
     * EXCEPTION VOULUE : les paquets F/T (RESOLVED_SMS_REQUEST /
     * TIMEOUT_SMS_REQUEST), ET tout paquet E marqué relaySms="1", ne sont
     * JAMAIS anonymisés. Dans ces deux cas, le paquet sert À PORTER le vrai
     * pseudo + les numéros des proches jusqu'à un relais qui composera le
     * SMS « <pseudo> a déclenché une alerte » à destination de CES MÊMES
     * proches (TODO.md §2.3 : le nom réel reste dû aux destinataires
     * déclarés par l'émetteur lui-même — forwardEmergencyToContacts() sur
     * un E avec relaySms="1" ET handleSmsRequestPacket() sur un F/T en ont
     * tous deux besoin). Un paquet E/P/R/S SANS relaySms est de la
     * télémétrie publique affichée à des tiers/relais quelconques et doit
     * donc rester anonymisé.
     */
    fun anonymizePseudoForTransit(json: String): String {
        val type = extractJsonString(json, "type")
        val relaySms = extractJsonString(json, "relaySms") == "1"
        if (type == TYPE_RESOLVED_SMS_REQUEST || type == TYPE_TIMEOUT_SMS_REQUEST || relaySms) return json
        val emergencyId = extractJsonString(json, "emergencyId")
            ?: extractJsonString(json, "id")
            ?: extractJsonString(json, "messageId")
            ?: return json
        val alias = deriveAlertAlias(emergencyId)
        if (!PSEUDO_FIELD_REGEX.containsMatchIn(json)) return json
        return PSEUDO_FIELD_REGEX.replaceFirst(json, "\"pseudo\":\"$alias\"")
    }

    /**
     * Variante SANS exception pour l'affichage JS (carte, popups, toasts).
     * Contrairement à anonymizePseudoForTransit(), aucun cas n'est exempté
     * ici : la couche JS n'a jamais besoin du vrai pseudo (l'envoi du SMS
     * relaySms="1" à la place de l'émetteur est entièrement natif, via
     * forwardEmergencyToContacts()/handleSmsRequestPacket() — le JS ne
     * fait qu'afficher). À utiliser pour tout paquet transmis à la WebView
     * pour affichage (ex. window.onRelayMessageReceived).
     */
    fun anonymizePseudoForDisplay(json: String): String {
        val emergencyId = extractJsonString(json, "emergencyId")
            ?: extractJsonString(json, "id")
            ?: extractJsonString(json, "messageId")
            ?: return json
        val alias = deriveAlertAlias(emergencyId)
        if (!PSEUDO_FIELD_REGEX.containsMatchIn(json)) return json
        return PSEUDO_FIELD_REGEX.replaceFirst(json, "\"pseudo\":\"$alias\"")
    }

    // ── Encodage TXT record (paquet JSON → map compacte) ────────────────
    // Clés : y=type court, i=messageId, e=emergencyId, p=pseudo, a=lat,
    // o=lng, s=timestamp, h=hopCount, c=numéros mobiles (chaîne relay
    // hors-ligne), u=1 si un enregistrement audio existe.
    fun buildTxtRecord(shortType: String, json: String, nowMs: Long): Map<String, String>? {
        val msgId = extractJsonString(json, "messageId") ?: return null
        val emergencyId = extractJsonString(json, "emergencyId")
            ?: extractJsonString(json, "id") ?: msgId
        // ANONYMISATION (TODO.md §2.2) : le TXT DNS-SD ne porte JAMAIS le
        // vrai pseudo pour les paquets de télémétrie publique — la clé "p"
        // est alors dérivée de l'emergencyId, jamais lue depuis le paquet.
        // EXCEPTION VOULUE pour F/T (RESOLVED_SMS_REQUEST/TIMEOUT_SMS_REQUEST)
        // et pour tout paquet marqué relaySms="1" : ces paquets doivent
        // porter le vrai pseudo jusqu'au relais qui composera le SMS aux
        // propres contacts de l'émetteur (même raison qu'anonymizePseudoForTransit,
        // qui a déjà laissé le vrai pseudo dans le JSON source dans ces cas).
        val isSmsDelegation = shortType == SHORT_RESOLVED_SMS_REQUEST ||
            shortType == SHORT_TIMEOUT_SMS_REQUEST ||
            extractJsonString(json, "relaySms") == "1"
        val pseudo = if (isSmsDelegation) {
            sanitizeTxtValue(extractJsonString(json, "pseudo") ?: "Utilisateur", maxLen = 32)
        } else {
            sanitizeTxtValue(deriveAlertAlias(emergencyId), maxLen = 32)
        }
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

        // ID-FIX (terrain) : la troncature à 40 caractères CASSAIT la corrélation
        // des ACK — les ids des demandes F/T font 42-43 caractères
        // (emg-xxxxxxxx-xxxxxxxx_ressms_<13 chiffres>), les derniers chiffres
        // étaient coupés dans le TXT : l'émetteur attendait l'ACK sous l'id
        // complet, le relais répondait sous l'id tronqué → fausse notification
        // « SMS non envoyés » alors que le SMS était parti, et dédup smsHandled
        // incohérente entre canal TXT (tronqué) et socket (complet) → risque de
        // double SMS. 64 couvre tous nos formats d'id avec marge (limite réelle
        // d'une valeur TXT : ~255 octets).
        val record = mutableMapOf(
            "y" to shortType,
            "i" to msgId.take(64),
            "e" to emergencyId.take(64),
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

        return """{"type":"$type","messageId":"$msgId","id":"$emergencyId","emergencyId":"$emergencyId","pseudo":"$pseudo","lat":$lat,"lng":$lng,"timestamp":$ts,"hopCount":$hop,"maxHops":$MAX_HOPS,"audio":"$audio","relaySms":"$relaySms","contacts":$contactsJson}"""
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
    fun ackInstanceName(messageId: String) = "marsel-ack-" + instanceSuffix(messageId)
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
