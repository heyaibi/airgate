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
 * [tick] runs the Wi-Fi radio-state poll, the Bluetooth/airplane radio-state
 * poll, the system-settings poll, and the tamper-only check, in that order.
 * Every step is isolated so a failing monitor — including one that throws an
 * [Error] rather than an [Exception] — never takes the rest of the tick down:
 * a flaky Wi-Fi read must not starve the settings poll, and neither may starve
 * the security-critical tamper check. Kept framework-free and unit-testable so
 * the loop's composition is pinned by tests instead of living only inside a
 * service's anonymous runnable. Rescheduling (the interval between ticks)
 * belongs to the caller; this object runs a single tick and always returns
 * normally.
 */
internal object AuditLoop {
    fun tick(
        checkWifiRadioState: () -> Unit,
        checkRadioState: () -> Unit,
        checkSettingsState: () -> Unit,
        checkTamperOnly: () -> Boolean
    ) {
        runIsolated { checkWifiRadioState() }
        runIsolated { checkRadioState() }
        runIsolated { checkSettingsState() }
        runIsolated { checkTamperOnly() }
    }

    private fun runIsolated(step: () -> Unit) {
        try {
            step()
        } catch (t: Throwable) {
            // A failing step must not take the rest of the tick down — and the
            // tick itself must always return normally so the caller's reschedule
            // keeps the loop alive. Catching Throwable (not just Exception) means
            // even an Error from one step cannot silently kill the monitoring
            // loop; if it could, the poll backstops (Wi-Fi and Bluetooth/airplane
            // state) would die with it until the service was recreated. Every
            // other step still runs, and the loop re-arms for the next tick.
        }
    }
}
