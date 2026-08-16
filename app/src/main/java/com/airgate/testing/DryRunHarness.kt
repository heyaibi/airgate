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

package com.airgate.testing

import android.content.Context
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.BreachEvent
import com.airgate.domain.model.ResponseTier
import com.airgate.domain.model.ViolationType
import com.airgate.engine.ThreatEngine
import java.util.UUID

class DryRunHarness(
    private val context: Context,
    private val repository: SecurityStateRepository = SecurityStateRepository(context),
    private val dhizukuManager: DhizukuManager = DhizukuManager(context),
    private val threatEngine: ThreatEngine = ThreatEngine(context, repository, dhizukuManager)
) {

    fun isDryRunEnabled(): Boolean {
        return repository.getConfig().dryRunMode
    }

    fun simulateBreach(violationType: ViolationType, customMetadata: Map<String, String> = emptyMap()): BreachEvent {
        val event = BreachEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            violationType = violationType,
            tier = violationType.defaultTier,
            weight = violationType.defaultWeight,
            rawMetadata = mapOf("simulated" to "true") + customMetadata
        )
        threatEngine.processBreach(event)
        return event
    }

    fun simulateWifiBreach(): BreachEvent = simulateBreach(ViolationType.WIFI_TRANSCEIVER_ENABLED, mapOf("interface" to "WIFI"))

    fun simulateBluetoothBreach(): BreachEvent = simulateBreach(ViolationType.BLUETOOTH_ACTIVITY, mapOf("interface" to "BLUETOOTH"))

    fun simulateUsbHostAttach(): BreachEvent = simulateBreach(ViolationType.USB_HOST_LINK, mapOf("device" to "Simulated_OTG"))

    fun simulateAdbEnabled(): BreachEvent = simulateBreach(ViolationType.ADB_ENABLED_FLIP, mapOf("adb" to "1"))

    fun simulateClockShift(): BreachEvent = simulateBreach(ViolationType.SYSTEM_CLOCK_CHANGED, mapOf("skewMins" to "15"))

    fun resetStreakForTesting() {
        repository.setStreak(0)
    }
}
