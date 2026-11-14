package com.example.marsel

import android.content.Context

/**
 * Réglages « Réseau Marsel » (mode B), stockés localement sur l'appareil
 * uniquement : interrupteur « Relais en arrière-plan » (désactivé par
 * défaut), option « Uniquement en charge », et date du consentement explicite
 * donné à l'activation (TODO.md).
 */
object RelaySettings {
    private const val PREFS = "marsel_relay"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CHARGING_ONLY = "charging_only"
    private const val KEY_CONSENT_AT = "consent_at"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun isChargingOnly(context: Context): Boolean = prefs(context).getBoolean(KEY_CHARGING_ONLY, false)

    /** Date (ms) du consentement donné à la dernière activation, 0 si jamais. */
    fun consentAt(context: Context): Long = prefs(context).getLong(KEY_CONSENT_AT, 0L)

    fun setEnabled(context: Context, enabled: Boolean) {
        val e = prefs(context).edit().putBoolean(KEY_ENABLED, enabled)
        if (enabled) e.putLong(KEY_CONSENT_AT, System.currentTimeMillis())
        e.apply()
    }

    fun setChargingOnly(context: Context, chargingOnly: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHARGING_ONLY, chargingOnly).apply()
    }
}
