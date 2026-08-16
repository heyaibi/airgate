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

/**
 * The composition of one periodic audit tick: which monitors run every cycle.
 *
 * [tick] runs the Wi-Fi radio-state poll, the system-settings poll, and the
 * tamper-only check, in that order. Every step is isolated so a failing monitor
 * never takes the rest of the tick down: a flaky Wi-Fi read must not starve the
 * settings poll, and neither may starve the security-critical tamper check.
 * Kept framework-free and unit-testable so the loop's composition is pinned by
 * tests instead of living only inside a service's anonymous runnable.
 * Rescheduling (the interval between ticks) belongs to the caller; this object
 * runs a single tick and always returns normally.
 */
internal object AuditLoop {
    fun tick(
        checkWifiRadioState: () -> Unit,
        checkSettingsState: () -> Unit,
        checkTamperOnly: () -> Boolean
    ) {
        runIsolated { checkWifiRadioState() }
        runIsolated { checkSettingsState() }
        runIsolated { checkTamperOnly() }
    }

    private fun runIsolated(step: () -> Unit) {
        try {
            step()
        } catch (e: Exception) {
            // A failing step must not take the rest of the tick down; every other
            // step still runs, and the caller's rescheduling continues because
            // tick always returns normally.
        }
    }
}
