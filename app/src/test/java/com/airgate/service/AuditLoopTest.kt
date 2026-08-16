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
 * the Wi-Fi radio-state poll is a first-class member of every audit loop tick
 * (alongside the settings poll and the tamper check), and that a failing step
 * never blocks the rest of the tick.
 */
class AuditLoopTest {

    @Test
    fun `one tick runs the wifi poll, settings poll and tamper check in order`() {
        val order = mutableListOf<String>()

        AuditLoop.tick(
            checkWifiRadioState = { order += "wifi" },
            checkSettingsState = { order += "settings" },
            checkTamperOnly = { order += "tamper"; true }
        )

        assertEquals(
            listOf("wifi", "settings", "tamper"),
            order
        )
    }

    @Test
    fun `a throwing wifi poll does not block the settings poll or tamper check`() {
        val order = mutableListOf<String>()

        AuditLoop.tick(
            checkWifiRadioState = { order += "wifi"; throw RuntimeException("wifi read failed") },
            checkSettingsState = { order += "settings" },
            checkTamperOnly = { order += "tamper"; true }
        )

        assertEquals(
            "a failing wifi poll must not take down the settings poll and tamper check",
            listOf("wifi", "settings", "tamper"),
            order
        )
    }

    @Test
    fun `a throwing settings poll does not block the tamper check`() {
        val order = mutableListOf<String>()

        AuditLoop.tick(
            checkWifiRadioState = { order += "wifi" },
            checkSettingsState = { order += "settings"; throw RuntimeException("settings read failed") },
            checkTamperOnly = { order += "tamper"; true }
        )

        assertEquals(listOf("wifi", "settings", "tamper"), order)
    }

    @Test
    fun `a throwing tamper check is swallowed and the tick returns`() {
        var tamperRan = false
        AuditLoop.tick(
            checkWifiRadioState = {},
            checkSettingsState = {},
            checkTamperOnly = { tamperRan = true; throw RuntimeException("tamper check failed") }
        )

        assertTrue("the tamper check must have been attempted", tamperRan)
    }

    @Test
    fun `each step runs exactly once per tick`() {
        var wifiCount = 0
        var settingsCount = 0
        var tamperCount = 0

        AuditLoop.tick(
            checkWifiRadioState = { wifiCount++ },
            checkSettingsState = { settingsCount++ },
            checkTamperOnly = { tamperCount++; true }
        )
        AuditLoop.tick(
            checkWifiRadioState = { wifiCount++ },
            checkSettingsState = { settingsCount++ },
            checkTamperOnly = { tamperCount++; true }
        )

        assertEquals(2, wifiCount)
        assertEquals(2, settingsCount)
        assertEquals(2, tamperCount)
    }
}
