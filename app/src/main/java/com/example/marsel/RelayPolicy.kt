package com.example.marsel

/**
 * Règles du relais en arrière-plan (TODO.md, mode B) — logique pure, testée
 * en JVM.
 *
 *  - désactivé                      → OFF
 *  - « Uniquement en charge » et
 *    téléphone débranché            → WAITING_CHARGE
 *  - branché                        → ACTIVE (aucun coût batterie)
 *  - débranché sous 20 %            → PAUSED_LOW_BATTERY, et il le reste
 *    jusqu'à 25 % ou jusqu'au branchement (hystérésis : pas de bascule
 *    marche/arrêt à chaque point de batterie autour de 20 %).
 *
 * L'alerte en cours (mode A) n'est jamais concernée : elle n'est jamais
 * coupée pour la batterie.
 */
object RelayPolicy {

    const val PAUSE_BELOW_PCT = 20
    const val RESUME_AT_PCT = 25

    enum class State { OFF, ACTIVE, PAUSED_LOW_BATTERY, WAITING_CHARGE }

    data class Decision(val state: State, val lowBatteryLatched: Boolean)

    /**
     * @param levelPct niveau de batterie 0-100, ou -1 s'il est inconnu (jamais
     *                 de pause sur une valeur inconnue).
     * @param lowBatteryLatched état « pause batterie faible » de la décision
     *                 précédente (hystérésis).
     */
    fun decide(
        enabled: Boolean,
        chargingOnly: Boolean,
        plugged: Boolean,
        levelPct: Int,
        lowBatteryLatched: Boolean
    ): Decision {
        val latched = when {
            plugged -> false
            levelPct < 0 -> lowBatteryLatched
            levelPct < PAUSE_BELOW_PCT -> true
            levelPct >= RESUME_AT_PCT -> false
            else -> lowBatteryLatched
        }
        val state = when {
            !enabled -> State.OFF
            plugged -> State.ACTIVE
            chargingOnly -> State.WAITING_CHARGE
            latched -> State.PAUSED_LOW_BATTERY
            else -> State.ACTIVE
        }
        return Decision(state, latched)
    }
}
