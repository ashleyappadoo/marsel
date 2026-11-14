package com.example.marsel

import com.example.marsel.RelayPolicy.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayPolicyTest {

    private fun state(
        enabled: Boolean = true,
        chargingOnly: Boolean = false,
        plugged: Boolean = false,
        level: Int = 80,
        latched: Boolean = false
    ) = RelayPolicy.decide(enabled, chargingOnly, plugged, level, latched)

    @Test
    fun disabled_isOff_whateverTheBattery() {
        assertEquals(State.OFF, state(enabled = false).state)
        assertEquals(State.OFF, state(enabled = false, plugged = true).state)
        assertEquals(State.OFF, state(enabled = false, level = 5).state)
    }

    @Test
    fun enabled_onBattery_isActive() {
        assertEquals(State.ACTIVE, state(level = 80).state)
        assertEquals(State.ACTIVE, state(level = 20).state)   // 20 % pile : pas encore « sous 20 % »
    }

    @Test
    fun under20_pauses() {
        val d = state(level = 19)
        assertEquals(State.PAUSED_LOW_BATTERY, d.state)
        assertTrue(d.lowBatteryLatched)
    }

    @Test
    fun hysteresis_staysPausedUntil25() {
        assertEquals(State.PAUSED_LOW_BATTERY, state(level = 22, latched = true).state)
        assertEquals(State.PAUSED_LOW_BATTERY, state(level = 24, latched = true).state)
        val resumed = state(level = 25, latched = true)
        assertEquals(State.ACTIVE, resumed.state)
        assertFalse(resumed.lowBatteryLatched)
        // Sans pause préalable, 22 % reste actif.
        assertEquals(State.ACTIVE, state(level = 22, latched = false).state)
    }

    @Test
    fun plugged_resumesImmediately_evenLowBattery() {
        val d = state(level = 5, latched = true, plugged = true)
        assertEquals(State.ACTIVE, d.state)
        assertFalse(d.lowBatteryLatched)
    }

    @Test
    fun chargingOnly_waitsForCharger() {
        assertEquals(State.WAITING_CHARGE, state(chargingOnly = true, level = 90).state)
        assertEquals(State.ACTIVE, state(chargingOnly = true, plugged = true).state)
        assertEquals(State.WAITING_CHARGE, state(chargingOnly = true, level = 10).state)
    }

    @Test
    fun unknownLevel_neverPauses_keepsPreviousLatch() {
        assertEquals(State.ACTIVE, state(level = -1, latched = false).state)
        assertEquals(State.PAUSED_LOW_BATTERY, state(level = -1, latched = true).state)
    }
}
