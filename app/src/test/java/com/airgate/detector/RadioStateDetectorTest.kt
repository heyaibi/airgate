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
import com.airgate.domain.model.BreachEvent
import com.airgate.domain.model.ResponseTier
import com.airgate.domain.model.ScoringGroup
import com.airgate.domain.model.ViolationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [RadioStateDetector]. The decision function is free of
 * Android framework calls, so every branch is exercised on the JVM. The only
 * actions the receiver may react to are the trusted protected system
 * broadcasts; spoofable vendor/custom actions (the FM radio actions) must never
 * produce a breach and must never appear in the registered filter.
 */
class RadioStateDetectorTest {

    private val detector: RadioStateDetector = RadioStateDetector(NoopListener())

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

    // --- Airplane mode branches -------------------------------------------

    @Test
    fun `airplane mode changed while airplane is off fires an AIRPLANE_MODE_OFF breach`() {
        val breach = detector.resolveBreach(ACTION_AIRPLANE_MODE_CHANGED, isAirplaneModeOn = false, bluetoothState = ERROR)

        assertEquals(ViolationType.AIRPLANE_MODE_OFF, breach?.violationType)
        assertEquals(ResponseTier.ALARM_STREAK, breach?.tier)
        assertEquals(ScoringGroup.WIRELESS, breach?.violationType?.scoringGroup)
        assertEquals(1, breach?.weight)
        assertEquals(ACTION_AIRPLANE_MODE_CHANGED, breach?.rawMetadata?.get("action"))
    }

    @Test
    fun `airplane mode changed while airplane is on fires nothing`() {
        assertNull(detector.resolveBreach(ACTION_AIRPLANE_MODE_CHANGED, isAirplaneModeOn = true, bluetoothState = ERROR))
    }

    // --- Bluetooth state branches -----------------------------------------

    @Test
    fun `bluetooth state ON fires a BLUETOOTH_ACTIVITY breach`() {
        val breach = detector.resolveBreach(ACTION_BLUETOOTH_STATE_CHANGED, isAirplaneModeOn = false, bluetoothState = BluetoothAdapter.STATE_ON)

        assertEquals(ViolationType.BLUETOOTH_ACTIVITY, breach?.violationType)
        assertEquals(ResponseTier.ALARM_STREAK, breach?.tier)
        assertEquals(ScoringGroup.WIRELESS, breach?.violationType?.scoringGroup)
        assertEquals(1, breach?.weight)
        assertEquals("BLUETOOTH", breach?.rawMetadata?.get("wireless_interface"))
        assertEquals(BluetoothAdapter.STATE_ON.toString(), breach?.rawMetadata?.get("state"))
    }

    @Test
    fun `bluetooth state TURNING_ON fires a BLUETOOTH_ACTIVITY breach`() {
        val breach = detector.resolveBreach(ACTION_BLUETOOTH_STATE_CHANGED, isAirplaneModeOn = false, bluetoothState = BluetoothAdapter.STATE_TURNING_ON)

        assertEquals(ViolationType.BLUETOOTH_ACTIVITY, breach?.violationType)
        assertEquals(ResponseTier.ALARM_STREAK, breach?.tier)
    }

    @Test
    fun `bluetooth state OFF fires nothing`() {
        assertNull(detector.resolveBreach(ACTION_BLUETOOTH_STATE_CHANGED, isAirplaneModeOn = false, bluetoothState = BluetoothAdapter.STATE_OFF))
    }

    @Test
    fun `bluetooth state TURNING_OFF fires nothing`() {
        assertNull(detector.resolveBreach(ACTION_BLUETOOTH_STATE_CHANGED, isAirplaneModeOn = false, bluetoothState = BluetoothAdapter.STATE_TURNING_OFF))
    }

    @Test
    fun `bluetooth state ERROR fires nothing`() {
        assertNull(detector.resolveBreach(ACTION_BLUETOOTH_STATE_CHANGED, isAirplaneModeOn = false, bluetoothState = ERROR))
    }

    // --- Bluetooth proximity branches (LOG_ONLY) --------------------------

    @Test
    fun `bluetooth device FOUND is logged only`() {
        val breach = detector.resolveBreach(BluetoothDevice.ACTION_FOUND, isAirplaneModeOn = false, bluetoothState = ERROR)

        assertEquals(ViolationType.BLUETOOTH_ACTIVITY, breach?.violationType)
        assertEquals(ResponseTier.LOG_ONLY, breach?.tier)
        assertEquals(BluetoothDevice.ACTION_FOUND, breach?.rawMetadata?.get("action"))
    }

    @Test
    fun `bluetooth discovery STARTED is logged only`() {
        val breach = detector.resolveBreach(BluetoothAdapter.ACTION_DISCOVERY_STARTED, isAirplaneModeOn = false, bluetoothState = ERROR)

        assertEquals(ViolationType.BLUETOOTH_ACTIVITY, breach?.violationType)
        assertEquals(ResponseTier.LOG_ONLY, breach?.tier)
    }

    @Test
    fun `bluetooth bond state changed is logged only`() {
        val breach = detector.resolveBreach(BluetoothDevice.ACTION_BOND_STATE_CHANGED, isAirplaneModeOn = false, bluetoothState = ERROR)

        assertEquals(ViolationType.BLUETOOTH_ACTIVITY, breach?.violationType)
        assertEquals(ResponseTier.LOG_ONLY, breach?.tier)
    }

    // --- Removed FM radio handling ----------------------------------------

    @Test
    fun `caf FM state changed broadcast fires nothing even when tuned on`() {
        assertNull(detector.resolveBreach(FM_CAF_ACTION, isAirplaneModeOn = false, bluetoothState = ERROR))
    }

    @Test
    fun `aosp hardware FM state changed broadcast fires nothing even when tuned on`() {
        assertNull(detector.resolveBreach(FM_AOSP_ACTION, isAirplaneModeOn = false, bluetoothState = ERROR))
    }

    // --- Unknown / malformed inputs ---------------------------------------

    @Test
    fun `null action fires nothing`() {
        assertNull(detector.resolveBreach(null, isAirplaneModeOn = false, bluetoothState = ERROR))
    }

    @Test
    fun `unrelated custom action fires nothing`() {
        assertNull(detector.resolveBreach("com.example.UNRELATED_EVENT", isAirplaneModeOn = false, bluetoothState = ERROR))
    }

    private class NoopListener : SignalListener {
        override fun onBreachDetected(event: BreachEvent) = Unit
    }

    private companion object {
        const val ACTION_AIRPLANE_MODE_CHANGED = "android.intent.action.AIRPLANE_MODE"
        const val ACTION_BLUETOOTH_STATE_CHANGED = "android.bluetooth.adapter.action.STATE_CHANGED"
        const val FM_CAF_ACTION = "com.caf.fmradio.FM_STATE_CHANGED"
        const val FM_AOSP_ACTION = "android.hardware.fm.action.FM_STATE_CHANGED"
        const val ERROR = Integer.MIN_VALUE
    }
}
