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

package com.airgate.policy

import android.app.admin.DevicePolicyManager
import android.content.Context
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.WipeResult

class WipeController(
    private val context: Context,
    private val dhizukuManager: DhizukuManager
) {
    companion object {
        // WIPE_SILENTLY is 0x0008; 0x0001 is WIPE_EXTERNAL_STORAGE. Aliases keep
        // call sites/tests stable and inline at compile time on all minSdk levels.
        @Suppress("InlinedApi") // constants are inlined at compile time and work on all minSdk levels
        val WIPE_SILENTLY: Int = DevicePolicyManager.WIPE_SILENTLY
        @Suppress("InlinedApi")
        val WIPE_RESET_PROTECTION_DATA: Int = DevicePolicyManager.WIPE_RESET_PROTECTION_DATA
    }

    /**
     * Executes the full factory reset. Dry-run mode returns
     * [WipeResult.SIMULATED] (the truthful simulated result of a successful
     * wipe) without touching the destructive system API — the wipe-path UI
     * (SecurityState.WIPING -> SimulatedWipeScreen) is driven by ThreatEngine,
     * so the simulation screen still appears without performing a real wipe.
     */
    fun executeWipe(config: AppConfig): WipeResult {
        if (config.dryRunMode) {
            return WipeResult.SIMULATED
        }

        var flags = WIPE_SILENTLY
        if (config.includeFRPData) {
            flags = flags or WIPE_RESET_PROTECTION_DATA
        }

        return dhizukuManager.wipeDevice(flags, config)
    }
}
