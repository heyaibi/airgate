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

package com.airgate.engine

import android.content.Context
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.dhizuku.DhizukuManager
import com.airgate.policy.DevicePolicyEnforcer

/**
 * Reactive hardening run the moment a breach is detected: force Airplane mode ON
 * via Dhizuku and re-assert every Device Owner restriction.
 */
class ReactiveHardener(
    context: Context,
    private val repository: SecurityStateRepository,
    private val dhizukuManager: DhizukuManager
) {
    private val policyEnforcer = DevicePolicyEnforcer(context, dhizukuManager)

    fun harden() {
        val config = repository.getConfig()
        // 1. Force Airplane mode ON via Dhizuku
        dhizukuManager.setGlobalSetting("airplane_mode_on", "1", config)

        // 2. Re-assert all DO restrictions
        policyEnforcer.enforceAllPolicies(config)
    }
}
