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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import com.airgate.domain.model.BreachEvent
import com.airgate.domain.model.ResponseTier
import com.airgate.domain.model.ViolationType
import java.util.UUID

class RadioStateDetector(
    private val listener: SignalListener
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val isAirplaneModeOn = intent.action == Intent.ACTION_AIRPLANE_MODE_CHANGED &&
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON, 0
            ) != 0
        resolveBreach(
            action = intent.action,
            isAirplaneModeOn = isAirplaneModeOn,
            bluetoothState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
        )?.let(listener::onBreachDetected)
    }

    /**
     * Pure decision logic for one incoming broadcast: maps an action (plus the
     * framework-observed state) to an optional [BreachEvent]. Kept free of
     * Android framework calls so every branch is unit-testable. Actions outside
     * the trusted set (including vendor/custom FM actions) resolve to null.
     */
    internal fun resolveBreach(
        action: String?,
        isAirplaneModeOn: Boolean,
        bluetoothState: Int
    ): BreachEvent? {
        return when (action) {
            Intent.ACTION_AIRPLANE_MODE_CHANGED ->
                if (isAirplaneModeOn) {
                    null
                } else {
                    breachOf(
                        ViolationType.AIRPLANE_MODE_OFF,
                        rawMetadata = mapOf("action" to action.orEmpty())
                    )
                }

            BluetoothAdapter.ACTION_STATE_CHANGED ->
                if (bluetoothState == BluetoothAdapter.STATE_ON ||
                    bluetoothState == BluetoothAdapter.STATE_TURNING_ON
                ) {
                    breachOf(
                        ViolationType.BLUETOOTH_ACTIVITY,
                        rawMetadata = mapOf(
                            "wireless_interface" to "BLUETOOTH",
                            "state" to bluetoothState.toString()
                        )
                    )
                } else {
                    null
                }

            BluetoothDevice.ACTION_FOUND,
            BluetoothAdapter.ACTION_DISCOVERY_STARTED,
            BluetoothDevice.ACTION_BOND_STATE_CHANGED ->
                // Discovery/found/bond events are passive proximity noise: nearby
                // devices trigger them constantly, and they only occur while BT is
                // already ON (which fires its own alarm above). Log them for audit
                // only; the ALARM tier is reserved for the BT-ON transition.
                breachOf(
                    ViolationType.BLUETOOTH_ACTIVITY,
                    rawMetadata = mapOf("action" to action.orEmpty()),
                    tier = ResponseTier.LOG_ONLY
                )

            else -> null
        }
    }

    fun getIntentFilter(): IntentFilter {
        return IntentFilter().apply {
            TRUSTED_BROADCAST_ACTIONS.forEach(::addAction)
        }
    }

    private fun breachOf(
        violationType: ViolationType,
        rawMetadata: Map<String, String>,
        tier: ResponseTier = violationType.defaultTier
    ): BreachEvent {
        return BreachEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            violationType = violationType,
            tier = tier,
            weight = violationType.defaultWeight,
            rawMetadata = rawMetadata
        )
    }

    companion object {
        /**
         * The only broadcast actions this receiver listens for. Each one is a
         * system broadcast on the protected-broadcast list, so only the system
         * (or a privileged component) can originate it. Vendor and custom
         * broadcast actions are deliberately not registered: they are not
         * protected, so any installed app could spoof them into this exported
         * receiver and fabricate breaches that feed the alarm and wipe paths.
         */
        internal val TRUSTED_BROADCAST_ACTIONS: List<String> = listOf(
            Intent.ACTION_AIRPLANE_MODE_CHANGED,
            BluetoothAdapter.ACTION_STATE_CHANGED,
            BluetoothDevice.ACTION_FOUND,
            BluetoothAdapter.ACTION_DISCOVERY_STARTED,
            BluetoothDevice.ACTION_BOND_STATE_CHANGED
        )
    }

}
