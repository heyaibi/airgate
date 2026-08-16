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

package com.airgate.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Composition tests for the periodic audit tick. These pin the contract that
 * network-registration re-arm and the Wi-Fi radio-state poll and the
 * Bluetooth/airplane radio-state poll are first-class members of every audit
 * loop tick (alongside the settings poll and the tamper check), and that a
 * failing step never blocks the rest of the tick.
 */
class AuditLoopTest {

    @Test
    fun `one tick runs registration, wifi poll, radio poll, settings poll and tamper check in order`() {
        val order = mutableListOf<String>()

        AuditLoop.tick(
            ensureNetworkRegistration = { order += "registration" },
            checkWifiRadioState = { order += "wifi" },
            checkRadioState = { order += "radio" },
            checkSettingsState = { order += "settings" },
            checkTamperOnly = { order += "tamper"; true }
        )

        assertEquals(
            listOf("registration", "wifi", "radio", "settings", "tamper"),
            order
        )
    }

    @Test
    fun `a throwing registration step does not block the wifi poll, radio poll, settings poll or tamper check`() {
        val order = mutableListOf<String>()

        AuditLoop.tick(
            ensureNetworkRegistration = { order += "registration"; throw RuntimeException("register failed") },
            checkWifiRadioState = { order += "wifi" },
            checkRadioState = { order += "radio" },
            checkSettingsState = { order += "settings" },
            checkTamperOnly = { order += "tamper"; true }
        )

        assertEquals(
            "a failing registration step must not take down the remaining steps",
            listOf("registration", "wifi", "radio", "settings", "tamper"),
            order
        )
    }

    @Test
    fun `a throwing wifi poll does not block the radio poll, settings poll or tamper check`() {
        val order = mutableListOf<String>()

        AuditLoop.tick(
            ensureNetworkRegistration = { order += "registration" },
            checkWifiRadioState = { order += "wifi"; throw RuntimeException("wifi read failed") },
            checkRadioState = { order += "radio" },
            checkSettingsState = { order += "settings" },
            checkTamperOnly = { order += "tamper"; true }
        )

        assertEquals(
            "a failing wifi poll must not take down the radio/settings polls and tamper check",
            listOf("registration", "wifi", "radio", "settings", "tamper"),
            order
        )
    }

    @Test
    fun `a throwing radio poll does not block the settings poll or tamper check`() {
        val order = mutableListOf<String>()

        AuditLoop.tick(
            ensureNetworkRegistration = { order += "registration" },
            checkWifiRadioState = { order += "wifi" },
            checkRadioState = { order += "radio"; throw RuntimeException("radio read failed") },
            checkSettingsState = { order += "settings" },
            checkTamperOnly = { order += "tamper"; true }
        )

        assertEquals(
            "a failing radio poll must not take down the settings poll and tamper check",
            listOf("registration", "wifi", "radio", "settings", "tamper"),
            order
        )
    }

    @Test
    fun `a throwing settings poll does not block the tamper check`() {
        val order = mutableListOf<String>()

        AuditLoop.tick(
            ensureNetworkRegistration = { order += "registration" },
            checkWifiRadioState = { order += "wifi" },
            checkRadioState = { order += "radio" },
            checkSettingsState = { order += "settings"; throw RuntimeException("settings read failed") },
            checkTamperOnly = { order += "tamper"; true }
        )

        assertEquals(listOf("registration", "wifi", "radio", "settings", "tamper"), order)
    }

    @Test
    fun `a throwing tamper check is swallowed and the tick returns`() {
        var tamperRan = false
        AuditLoop.tick(
            ensureNetworkRegistration = {},
            checkWifiRadioState = {},
            checkRadioState = {},
            checkSettingsState = {},
            checkTamperOnly = { tamperRan = true; throw RuntimeException("tamper check failed") }
        )

        assertTrue("the tamper check must have been attempted", tamperRan)
    }

    @Test
    fun `a step throwing an Error does not block the rest of the tick`() {
        // An Error (not just an Exception) in one step must not kill the loop:
        // if it propagated out of tick, the caller's reschedule would never run
        // and the poll backstops would die for the process lifetime.
        val order = mutableListOf<String>()

        AuditLoop.tick(
            ensureNetworkRegistration = { order += "registration"; throw AssertionError("register failed hard") },
            checkWifiRadioState = { order += "wifi"; throw AssertionError("hard failure") },
            checkRadioState = { order += "radio" },
            checkSettingsState = { order += "settings" },
            checkTamperOnly = { order += "tamper"; true }
        )

        assertEquals(
            "a failing step throwing an Error must not take down the remaining steps",
            listOf("registration", "wifi", "radio", "settings", "tamper"),
            order
        )
    }

    @Test
    fun `an Error from every step is swallowed and the tick still returns normally`() {
        // Even in the worst case the tick must return so the caller re-arms.
        AuditLoop.tick(
            ensureNetworkRegistration = { throw AssertionError("registration") },
            checkWifiRadioState = { throw AssertionError("wifi") },
            checkRadioState = { throw AssertionError("radio") },
            checkSettingsState = { throw AssertionError("settings") },
            checkTamperOnly = { throw AssertionError("tamper") }
        )
    }

    @Test
    fun `each step runs exactly once per tick`() {
        var registrationCount = 0
        var wifiCount = 0
        var radioCount = 0
        var settingsCount = 0
        var tamperCount = 0

        fun runTick() {
            AuditLoop.tick(
                ensureNetworkRegistration = { registrationCount++ },
                checkWifiRadioState = { wifiCount++ },
                checkRadioState = { radioCount++ },
                checkSettingsState = { settingsCount++ },
                checkTamperOnly = { tamperCount++; true }
            )
        }
        runTick()
        runTick()

        assertEquals(2, registrationCount)
        assertEquals(2, wifiCount)
        assertEquals(2, radioCount)
        assertEquals(2, settingsCount)
        assertEquals(2, tamperCount)
    }
}
