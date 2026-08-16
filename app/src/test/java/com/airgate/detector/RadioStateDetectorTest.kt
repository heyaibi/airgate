/*
 * Copyright (C) 2026 The Airgate project contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.airgate.detector

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import com.airgate.domain.model.ResponseTier
import com.airgate.domain.model.ScoringGroup
import com.airgate.domain.model.ViolationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [RadioStateDetector]. The decision functions are free of
 * Android framework calls, so every branch is exercised on the JVM. The only
 * actions the receiver may react to are the trusted protected system
 * broadcasts; spoofable vendor/custom actions (the FM radio actions) must never
 * produce a breach and must never appear in the registered filter.
 */
class RadioStateDetectorTest {

    // --- Trusted broadcast registration -----------------------------------

    @Test
    fun `trusted broadcast set is exactly the protected system actions`() {
        assertEquals(
            setOf(
                "android.intent.action.AIRPLANE_MODE",
                "android.bluetooth.adapter.action.STATE_CHANGED",
                "android.bluetooth.device.action.FOUND",
                "android.bluetooth.adapter.action.DISCOVERY_STARTED",
                "android.bluetooth.device.action.BOND_STATE_CHANGED"
            ),
            RadioStateDetector.TRUSTED_BROADCAST_ACTIONS.toSet()
        )
    }

    @Test
    fun `trusted broadcast set excludes both spoofable FM radio actions`() {
        assertTrue(
            "qualcomm caf FM action must not be registered",
            RadioStateDetector.TRUSTED_BROADCAST_ACTIONS.none { it == "com.caf.fmradio.FM_STATE_CHANGED" }
        )
        assertTrue(
            "aosp hardware FM action must not be registered",
            RadioStateDetector.TRUSTED_BROADCAST_ACTIONS.none { it == "android.hardware.fm.action.FM_STATE_CHANGED" }
        )
    }

    // --- Airplane mode broadcast branches ---------------------------------

    @Test
    fun `airplane mode changed while airplane is off fires an AIRPLANE_MODE_OFF breach`() {
        val breach = RadioStateDetector.resolveBreach(ACTION_AIRPLANE_MODE_CHANGED, isAirplaneModeOn = false, bluetoothState = ERROR)

        assertEquals(ViolationType.AIRPLANE_MODE_OFF, breach?.violationType)
        assertEquals(ResponseTier.ALARM_STREAK, breach?.tier)
        assertEquals(ScoringGroup.WIRELESS, breach?.violationType?.scoringGroup)
        assertEquals(1, breach?.weight)
        assertEquals(ACTION_AIRPLANE_MODE_CHANGED, breach?.rawMetadata?.get("action"))
    }

    @Test
    fun `airplane mode changed while airplane is on fires nothing`() {
        assertNull(RadioStateDetector.resolveBreach(ACTION_AIRPLANE_MODE_CHANGED, isAirplaneModeOn = true, bluetoothState = ERROR))
    }

    // --- Bluetooth state broadcast branches -------------------------------

    @Test
    fun `bluetooth state ON fires a BLUETOOTH_ACTIVITY breach`() {
        val breach = RadioStateDetector.resolveBreach(ACTION_BLUETOOTH_STATE_CHANGED, isAirplaneModeOn = false, bluetoothState = BluetoothAdapter.STATE_ON)

        assertEquals(ViolationType.BLUETOOTH_ACTIVITY, breach?.violationType)
        assertEquals(ResponseTier.ALARM_STREAK, breach?.tier)
        assertEquals(ScoringGroup.WIRELESS, breach?.violationType?.scoringGroup)
        assertEquals(1, breach?.weight)
        assertEquals("BLUETOOTH", breach?.rawMetadata?.get("wireless_interface"))
        assertEquals(BluetoothAdapter.STATE_ON.toString(), breach?.rawMetadata?.get("state"))
    }

    @Test
    fun `bluetooth state TURNING_ON fires a BLUETOOTH_ACTIVITY breach`() {
        val breach = RadioStateDetector.resolveBreach(ACTION_BLUETOOTH_STATE_CHANGED, isAirplaneModeOn = false, bluetoothState = BluetoothAdapter.STATE_TURNING_ON)

        assertEquals(ViolationType.BLUETOOTH_ACTIVITY, breach?.violationType)
        assertEquals(ResponseTier.ALARM_STREAK, breach?.tier)
    }

    @Test
    fun `bluetooth state OFF fires nothing`() {
        assertNull(RadioStateDetector.resolveBreach(ACTION_BLUETOOTH_STATE_CHANGED, isAirplaneModeOn = false, bluetoothState = BluetoothAdapter.STATE_OFF))
    }

    @Test
    fun `bluetooth state TURNING_OFF fires nothing`() {
        assertNull(RadioStateDetector.resolveBreach(ACTION_BLUETOOTH_STATE_CHANGED, isAirplaneModeOn = false, bluetoothState = BluetoothAdapter.STATE_TURNING_OFF))
    }

    @Test
    fun `bluetooth state ERROR fires nothing`() {
        assertNull(RadioStateDetector.resolveBreach(ACTION_BLUETOOTH_STATE_CHANGED, isAirplaneModeOn = false, bluetoothState = ERROR))
    }

    // --- Bluetooth proximity broadcast branches (LOG_ONLY) ----------------

    @Test
    fun `bluetooth device FOUND is logged only`() {
        val breach = RadioStateDetector.resolveBreach(BluetoothDevice.ACTION_FOUND, isAirplaneModeOn = false, bluetoothState = ERROR)

        assertEquals(ViolationType.BLUETOOTH_ACTIVITY, breach?.violationType)
        assertEquals(ResponseTier.LOG_ONLY, breach?.tier)
        assertEquals(BluetoothDevice.ACTION_FOUND, breach?.rawMetadata?.get("action"))
    }

    @Test
    fun `bluetooth discovery STARTED is logged only`() {
        val breach = RadioStateDetector.resolveBreach(BluetoothAdapter.ACTION_DISCOVERY_STARTED, isAirplaneModeOn = false, bluetoothState = ERROR)

        assertEquals(ViolationType.BLUETOOTH_ACTIVITY, breach?.violationType)
        assertEquals(ResponseTier.LOG_ONLY, breach?.tier)
    }

    @Test
    fun `bluetooth bond state changed is logged only`() {
        val breach = RadioStateDetector.resolveBreach(BluetoothDevice.ACTION_BOND_STATE_CHANGED, isAirplaneModeOn = false, bluetoothState = ERROR)

        assertEquals(ViolationType.BLUETOOTH_ACTIVITY, breach?.violationType)
        assertEquals(ResponseTier.LOG_ONLY, breach?.tier)
    }

    // --- Removed FM radio handling ----------------------------------------

    @Test
    fun `caf FM state changed broadcast fires nothing even when tuned on`() {
        assertNull(RadioStateDetector.resolveBreach(FM_CAF_ACTION, isAirplaneModeOn = false, bluetoothState = ERROR))
    }

    @Test
    fun `aosp hardware FM state changed broadcast fires nothing even when tuned on`() {
        assertNull(RadioStateDetector.resolveBreach(FM_AOSP_ACTION, isAirplaneModeOn = false, bluetoothState = ERROR))
    }

    // --- Unknown / malformed broadcast inputs -----------------------------

    @Test
    fun `null action fires nothing`() {
        assertNull(RadioStateDetector.resolveBreach(null, isAirplaneModeOn = false, bluetoothState = ERROR))
    }

    @Test
    fun `unrelated custom action fires nothing`() {
        assertNull(RadioStateDetector.resolveBreach("com.example.UNRELATED_EVENT", isAirplaneModeOn = false, bluetoothState = ERROR))
    }

    // --- Airplane-mode episode latch (the poll backstop) ------------------

    @Test
    fun `airplane off at the first poll is reported - the initial-state case`() {
        // The core gap this closes: no ACTION_AIRPLANE_MODE_CHANGED fires on
        // service start when airplane mode is already off. The first observation
        // (previous == false) must detect the already-violating state.
        val result = RadioStateDetector.resolveAirplaneState(
            previousReported = false,
            airplaneOn = false
        )

        assertTrue("an already-off airplane mode must be reported", result.shouldEmit)
        assertTrue("reporting the episode must latch it", result.nextReported)
    }

    @Test
    fun `airplane on never reports and arms the latch for the next off`() {
        val result = RadioStateDetector.resolveAirplaneState(
            previousReported = false,
            airplaneOn = true
        )

        assertFalse(result.shouldEmit)
        assertFalse("airplane on must clear the reported state", result.nextReported)
    }

    @Test
    fun `airplane staying off is never re-reported on a later poll`() {
        val sustained = RadioStateDetector.resolveAirplaneState(
            previousReported = true,
            airplaneOn = false
        )

        assertFalse("a sustained airplane-off state must not re-report", sustained.shouldEmit)
        assertTrue("the episode stays latched", sustained.nextReported)
    }

    @Test
    fun `airplane off after a return to on is reported again`() {
        var latch = false
        var result = RadioStateDetector.resolveAirplaneState(latch, airplaneOn = false)
        assertTrue(result.shouldEmit)
        latch = result.nextReported

        result = RadioStateDetector.resolveAirplaneState(latch, airplaneOn = true)
        assertFalse(result.shouldEmit)
        latch = result.nextReported
        assertFalse("airplane on must clear the episode", latch)

        result = RadioStateDetector.resolveAirplaneState(latch, airplaneOn = false)
        assertTrue("a fresh on->off episode must report again", result.shouldEmit)
    }

    @Test
    fun `a failed airplane read never fires and never moves the latch`() {
        val latched = RadioStateDetector.resolveAirplaneState(
            previousReported = true,
            airplaneOn = null
        )
        assertFalse(latched.shouldEmit)
        assertTrue("an unreadable setting must not clear a latched episode", latched.nextReported)

        val fresh = RadioStateDetector.resolveAirplaneState(
            previousReported = false,
            airplaneOn = null
        )
        assertFalse(fresh.shouldEmit)
        assertFalse(fresh.nextReported)
    }

    @Test
    fun `a failed airplane read does not forget an off radio`() {
        var latch = false
        var result = RadioStateDetector.resolveAirplaneState(latch, airplaneOn = false)
        assertTrue(result.shouldEmit)
        latch = result.nextReported

        // A read blip is not evidence airplane came back on: the episode must
        // stay latched so the next off read (same episode) is not re-reported.
        result = RadioStateDetector.resolveAirplaneState(latch, airplaneOn = null)
        assertFalse(result.shouldEmit)
        latch = result.nextReported

        val afterRecovery = RadioStateDetector.resolveAirplaneState(latch, airplaneOn = false)
        assertFalse("recovery to the same episode must not re-report", afterRecovery.shouldEmit)
    }

    // --- Bluetooth episode latch (the poll backstop) ----------------------

    @Test
    fun `bluetooth already on at the first poll is reported - the initial-state case`() {
        // The core gap this closes: no ACTION_STATE_CHANGED fires on service start
        // when Bluetooth is already on. The first observation must detect it.
        val result = RadioStateDetector.resolveBluetoothState(
            previousReported = false,
            bluetoothState = BluetoothAdapter.STATE_ON
        )

        assertTrue("an already-on radio must be reported", result.shouldEmit)
        assertTrue("reporting the episode must latch it", result.nextReported)
    }

    @Test
    fun `bluetooth turning on at the first poll is reported`() {
        val result = RadioStateDetector.resolveBluetoothState(
            previousReported = false,
            bluetoothState = BluetoothAdapter.STATE_TURNING_ON
        )

        assertTrue(result.shouldEmit)
        assertTrue(result.nextReported)
    }

    @Test
    fun `bluetooth staying on is never re-reported on a later poll`() {
        val sustained = RadioStateDetector.resolveBluetoothState(
            previousReported = true,
            bluetoothState = BluetoothAdapter.STATE_ON
        )

        assertFalse("a sustained radio-on state must not re-report", sustained.shouldEmit)
        assertTrue("the episode stays latched", sustained.nextReported)
    }

    @Test
    fun `bluetooth turning off then back on is reported again`() {
        var latch = false
        var result = RadioStateDetector.resolveBluetoothState(latch, BluetoothAdapter.STATE_ON)
        assertTrue(result.shouldEmit)
        latch = result.nextReported

        result = RadioStateDetector.resolveBluetoothState(latch, BluetoothAdapter.STATE_OFF)
        assertFalse(result.shouldEmit)
        latch = result.nextReported
        assertFalse("a definitive off must clear the episode", latch)

        result = RadioStateDetector.resolveBluetoothState(latch, BluetoothAdapter.STATE_ON)
        assertTrue("a fresh disabled->enabled transition must report again", result.shouldEmit)
    }

    @Test
    fun `a failed bluetooth read never forgets an on radio`() {
        // A failed/error read is not evidence the radio turned off: the episode
        // must stay latched so the next ON read (same physical episode) is not
        // reported twice.
        val errored = RadioStateDetector.resolveBluetoothState(
            previousReported = true,
            bluetoothState = ERROR
        )
        assertFalse(errored.shouldEmit)
        assertTrue("an error read must not clear a latched episode", errored.nextReported)

        val afterRecovery = RadioStateDetector.resolveBluetoothState(
            previousReported = errored.nextReported,
            bluetoothState = BluetoothAdapter.STATE_ON
        )
        assertFalse("recovery to the same episode must not re-report", afterRecovery.shouldEmit)
    }

    @Test
    fun `a failed bluetooth read after off still recovers and reports the next on`() {
        val errored = RadioStateDetector.resolveBluetoothState(
            previousReported = false,
            bluetoothState = ERROR
        )
        assertFalse(errored.shouldEmit)
        assertFalse(errored.nextReported)

        val recovered = RadioStateDetector.resolveBluetoothState(
            previousReported = errored.nextReported,
            bluetoothState = BluetoothAdapter.STATE_ON
        )
        assertTrue("a fresh on after an error read must report", recovered.shouldEmit)
    }

    @Test
    fun `bluetooth off or transitioning never reports regardless of previous state`() {
        for (previous in BOOLS) {
            for (state in NON_LIVE_STATES) {
                val result = RadioStateDetector.resolveBluetoothState(previous, state)
                assertFalse(
                    "state $state with previous=$previous must never report",
                    result.shouldEmit
                )
            }
        }
    }

    @Test
    fun `only definitive bluetooth states move the latch`() {
        assertEquals(false, RadioStateDetector.resolveBluetoothState(true, BluetoothAdapter.STATE_OFF).nextReported)
        assertEquals(false, RadioStateDetector.resolveBluetoothState(false, BluetoothAdapter.STATE_OFF).nextReported)
        assertEquals(true, RadioStateDetector.resolveBluetoothState(true, BluetoothAdapter.STATE_TURNING_OFF).nextReported)
        assertEquals(false, RadioStateDetector.resolveBluetoothState(false, BluetoothAdapter.STATE_TURNING_OFF).nextReported)
        assertEquals(true, RadioStateDetector.resolveBluetoothState(true, ERROR).nextReported)
        assertEquals(false, RadioStateDetector.resolveBluetoothState(false, ERROR).nextReported)
        assertEquals(true, RadioStateDetector.resolveBluetoothState(true, MALFORMED).nextReported)
        assertEquals(false, RadioStateDetector.resolveBluetoothState(false, MALFORMED).nextReported)
    }

    // --- Cross-mechanism coordination (one report per episode) ------------

    @Test
    fun `an airplane-off episode is reported exactly once across poll then broadcast`() {
        var latch = false

        // Poll observes airplane already off at service start.
        var result = RadioStateDetector.resolveAirplaneState(latch, airplaneOn = false)
        assertTrue("the poll must report the episode", result.shouldEmit)
        latch = result.nextReported

        // A later broadcast for the same episode must not duplicate the report.
        result = RadioStateDetector.resolveAirplaneState(latch, airplaneOn = false)
        assertFalse("the broadcast must not duplicate the poll's report", result.shouldEmit)
        latch = result.nextReported

        // Airplane returns on (broadcast clears), then off again: a new episode.
        result = RadioStateDetector.resolveAirplaneState(latch, airplaneOn = true)
        latch = result.nextReported
        result = RadioStateDetector.resolveAirplaneState(latch, airplaneOn = false)
        assertTrue("a fresh episode after on must be reported once", result.shouldEmit)
    }

    @Test
    fun `a bluetooth-on episode is reported exactly once across broadcast then poll`() {
        var latch = false

        // The broadcast reports the enable transition first.
        var result = RadioStateDetector.resolveBluetoothState(latch, BluetoothAdapter.STATE_TURNING_ON)
        assertTrue("the broadcast must report the episode", result.shouldEmit)
        latch = result.nextReported

        // The next poll then sees the same radio-on episode.
        result = RadioStateDetector.resolveBluetoothState(latch, BluetoothAdapter.STATE_ON)
        assertFalse("the poll must not duplicate the broadcast's report", result.shouldEmit)
        latch = result.nextReported

        // Repeated observations stay silent.
        result = RadioStateDetector.resolveBluetoothState(latch, BluetoothAdapter.STATE_ON)
        assertFalse(result.shouldEmit)
        latch = result.nextReported

        // Off (definitive) then on: a new episode.
        result = RadioStateDetector.resolveBluetoothState(latch, BluetoothAdapter.STATE_OFF)
        latch = result.nextReported
        result = RadioStateDetector.resolveBluetoothState(latch, BluetoothAdapter.STATE_ON)
        assertTrue("a fresh episode after off must be reported once", result.shouldEmit)
    }

    // --- Poll breach construction -----------------------------------------

    @Test
    fun `bluetooth poll breach is alarm-streak in the wireless group with the RADIO_POLL source`() {
        val breach = RadioStateDetector.bluetoothPollBreach(BluetoothAdapter.STATE_ON)

        assertEquals(ViolationType.BLUETOOTH_ACTIVITY, breach.violationType)
        assertEquals(ResponseTier.ALARM_STREAK, breach.tier)
        assertEquals(ScoringGroup.WIRELESS, breach.violationType.scoringGroup)
        assertEquals(1, breach.weight)
        assertEquals("BLUETOOTH", breach.rawMetadata["wireless_interface"])
        assertEquals(BluetoothAdapter.STATE_ON.toString(), breach.rawMetadata["state"])
        assertEquals("RADIO_POLL", breach.rawMetadata["source"])
    }

    @Test
    fun `airplane poll breach is alarm-streak in the wireless group with the RADIO_POLL source`() {
        val breach = RadioStateDetector.airplanePollBreach()

        assertEquals(ViolationType.AIRPLANE_MODE_OFF, breach.violationType)
        assertEquals(ResponseTier.ALARM_STREAK, breach.tier)
        assertEquals(ScoringGroup.WIRELESS, breach.violationType.scoringGroup)
        assertEquals(1, breach.weight)
        assertEquals("RADIO_POLL", breach.rawMetadata["source"])
    }

    @Test
    fun `poll breaches carry a unique id and a timestamp`() {
        val bt1 = RadioStateDetector.bluetoothPollBreach(BluetoothAdapter.STATE_ON)
        val bt2 = RadioStateDetector.bluetoothPollBreach(BluetoothAdapter.STATE_ON)
        val ap1 = RadioStateDetector.airplanePollBreach()
        val ap2 = RadioStateDetector.airplanePollBreach()

        assertTrue("each bluetooth poll breach must carry a unique id", bt1.id != bt2.id)
        assertTrue("each airplane poll breach must carry a unique id", ap1.id != ap2.id)
        assertTrue("poll breaches must carry a timestamp", bt1.timestamp > 0L && ap1.timestamp > 0L)
    }

    // --- Exhaustive decision tables ---------------------------------------

    @Test
    fun `exhaustive airplane latch table - every input combination`() {
        for (previous in BOOLS) {
            for (airplaneOn in listOf(null, true, false)) {
                val result = RadioStateDetector.resolveAirplaneState(previous, airplaneOn)
                val message = "airplaneOn=$airplaneOn previous=$previous"

                assertEquals(
                    message,
                    airplaneOn == false && !previous,
                    result.shouldEmit
                )
                assertEquals(
                    message,
                    when (airplaneOn) {
                        null -> previous
                        true -> false
                        false -> true
                    },
                    result.nextReported
                )
            }
        }
    }

    @Test
    fun `exhaustive bluetooth latch table - every state and previous combination`() {
        for (previous in BOOLS) {
            for (state in ALL_BLUETOOTH_STATES) {
                val result = RadioStateDetector.resolveBluetoothState(previous, state)
                val message = "state=$state previous=$previous"
                val live = state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_TURNING_ON

                assertEquals(message, live && !previous, result.shouldEmit)
                assertEquals(
                    message,
                    when (state) {
                        BluetoothAdapter.STATE_ON,
                        BluetoothAdapter.STATE_TURNING_ON -> true
                        BluetoothAdapter.STATE_OFF -> false
                        else -> previous
                    },
                    result.nextReported
                )
            }
        }
    }

    private companion object {
        const val ACTION_AIRPLANE_MODE_CHANGED = "android.intent.action.AIRPLANE_MODE"
        const val ACTION_BLUETOOTH_STATE_CHANGED = "android.bluetooth.adapter.action.STATE_CHANGED"
        const val FM_CAF_ACTION = "com.caf.fmradio.FM_STATE_CHANGED"
        const val FM_AOSP_ACTION = "android.hardware.fm.action.FM_STATE_CHANGED"

        /** The sentinel [RadioStateDetector] returns for an unreadable adapter. */
        const val ERROR = Integer.MIN_VALUE

        /** A value the framework can never return, for the else-branch. */
        const val MALFORMED = 99

        val BOOLS: List<Boolean> = listOf(false, true)

        /** Every state that must never be treated as a live radio. */
        val NON_LIVE_STATES: List<Int> = listOf(
            BluetoothAdapter.STATE_OFF,
            BluetoothAdapter.STATE_TURNING_OFF,
            ERROR
        )

        /** Every real state plus the unreadable/malformed sentinels. */
        val ALL_BLUETOOTH_STATES: List<Int> = NON_LIVE_STATES + listOf(
            BluetoothAdapter.STATE_ON,
            BluetoothAdapter.STATE_TURNING_ON,
            MALFORMED,
            -1,
            Int.MAX_VALUE
        )
    }
}
