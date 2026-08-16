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
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import com.airgate.domain.model.BreachEvent
import com.airgate.domain.model.ResponseTier
import com.airgate.domain.model.ViolationType
import java.util.UUID

/**
 * Detects radio-state violations: airplane mode switched off and Bluetooth
 * activity. Two mechanisms feed the same episode latches:
 *
 *  1. The broadcast receiver (the fast path): the state-change broadcasts are
 *     edge-triggered and non-sticky, so they fire only when a radio is *toggled*
 *     while the process is alive.
 *  2. [checkRadioState], the poll backstop (runs on the 10s audit loop): reads
 *     the live adapter/setting state. Without it, a service start (boot or
 *     START_STICKY restart) with Bluetooth already on or airplane mode already
 *     off is never detected — the broadcasts fire only on transitions.
 *
 * Both paths share a "report once per episode" latch per signal, so whichever
 * mechanism observes the start of an episode first reports it and the other
 * stays silent; only a definitive return to the safe state opens the latch.
 */
class RadioStateDetector(
    private val context: Context,
    private val listener: SignalListener
) : BroadcastReceiver() {

    // The broadcast receiver runs on the main thread while the poll runs on the
    // audit thread, so the episode latches are guarded.
    private val radioLatchLock = Any()
    private var bluetoothReported = false
    private var airplaneReported = false

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        // The airplane-mode setting is authoritative only for the airplane
        // broadcast (which fires on a real toggle). The raw (nullable) read is
        // passed through to the episode latch so an unreadable setting never
        // opens an episode — identical to the poll's failure semantics. Only the
        // broadcast decision function below coerces a failed read to airplane-ON
        // (safe), so a read error never fabricates a breach.
        val rawAirplaneOn = if (action == Intent.ACTION_AIRPLANE_MODE_CHANGED) {
            readAirplaneModeOn()
        } else {
            null
        }
        val isAirplaneModeOn = action == Intent.ACTION_AIRPLANE_MODE_CHANGED && (rawAirplaneOn ?: true)
        val bluetoothState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)

        // The airplane / bluetooth state-change broadcasts are edge-triggered and
        // non-sticky, so they consult the shared episode latches: the latch moves
        // even when nothing is emitted (e.g. airplane turning back on clears it),
        // and an already-reported episode is never reported a second time.
        val latch = when (action) {
            Intent.ACTION_AIRPLANE_MODE_CHANGED -> synchronized(radioLatchLock) {
                val r = resolveAirplaneState(airplaneReported, rawAirplaneOn)
                airplaneReported = r.nextReported
                r
            }
            BluetoothAdapter.ACTION_STATE_CHANGED -> synchronized(radioLatchLock) {
                val r = resolveBluetoothState(bluetoothReported, bluetoothState)
                bluetoothReported = r.nextReported
                r
            }
            else -> null
        }

        resolveBreach(action, isAirplaneModeOn, bluetoothState)
            ?.takeIf { latch == null || latch.shouldEmit }
            ?.let(listener::onBreachDetected)
    }

    /**
     * Periodic poll of the live radio state. Covers the gap the broadcasts
     * cannot: a service start (boot or restart) with Bluetooth already on or
     * airplane mode already off produces no transition broadcast, so without
     * this the violation is never raised. Runs on the audit loop's background
     * thread, guarded against the main-thread receiver via [radioLatchLock].
     */
    fun checkRadioState() {
        val bluetoothState = readBluetoothState()
        val bluetoothResult = synchronized(radioLatchLock) {
            val r = resolveBluetoothState(bluetoothReported, bluetoothState)
            bluetoothReported = r.nextReported
            r
        }
        if (bluetoothResult.shouldEmit) {
            listener.onBreachDetected(bluetoothPollBreach(bluetoothState))
        }

        val airplaneOn = readAirplaneModeOn()
        val airplaneResult = synchronized(radioLatchLock) {
            val r = resolveAirplaneState(airplaneReported, airplaneOn)
            airplaneReported = r.nextReported
            r
        }
        if (airplaneResult.shouldEmit) {
            listener.onBreachDetected(airplanePollBreach())
        }
    }

    private fun readBluetoothState(): Int {
        return try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.state
                ?: BluetoothAdapter.ERROR
        } catch (e: Exception) {
            // A thrown read (e.g. BLUETOOTH_CONNECT denied) is not evidence the
            // radio is off: mapped to ERROR, which neither fires a breach nor
            // moves the latch, so the next poll re-attempts the read. The
            // framework's other failure mode — a Bluetooth-stack outage — returns
            // STATE_OFF rather than throwing, and the episode latch treats that
            // as a definitive off: the same still-on radio is then reported again
            // on the next poll. That duplicate is the accepted fail-safe trade-off
            // (a security monitor over-reports rather than under-reports); it can
            // never turn a real off into a reported-on.
            BluetoothAdapter.ERROR
        }
    }

    private fun readAirplaneModeOn(): Boolean? {
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0
            ) != 0
        } catch (e: Exception) {
            null
        }
    }

    fun getIntentFilter(): IntentFilter {
        return IntentFilter().apply {
            TRUSTED_BROADCAST_ACTIONS.forEach(::addAction)
        }
    }

    companion object {
        /**
         * Outcome of one radio-state observation for either mechanism: whether a
         * breach must be emitted now, and the reported value the caller must
         * remember for the shared episode latch.
         */
        internal data class RadioStateResult(
            val shouldEmit: Boolean,
            val nextReported: Boolean
        )

        /**
         * Pure decision logic for the airplane-mode episode latch, keyed on the
         * observed setting. A breach fires only when airplane mode is off and the
         * episode has not yet been reported, so a sustained airplane-off state
         * never re-fires on every poll tick. Airplane mode ON always re-arms the
         * latch (a later off is a fresh episode), and a null (unreadable) setting
         * — a failed read — leaves it untouched so a read blip never forgets an
         * off radio. Free of Android framework calls so every branch is
         * unit-testable.
         */
        internal fun resolveAirplaneState(
            previousReported: Boolean,
            airplaneOn: Boolean?
        ): RadioStateResult {
            return when (airplaneOn) {
                null -> RadioStateResult(shouldEmit = false, nextReported = previousReported)
                true -> RadioStateResult(shouldEmit = false, nextReported = false)
                false -> RadioStateResult(
                    shouldEmit = !previousReported,
                    nextReported = true
                )
            }
        }

        /**
         * Pure decision logic for the bluetooth episode latch, keyed on the
         * adapter state. A breach fires only when the radio is live (ON or
         * TURNING_ON) and the episode has not yet been reported. Only definitive
         * states move the latch: ON / TURNING_ON report, OFF clears, and the
         * transitional-off / error states (including a thrown read mapped to
         * ERROR) leave it untouched, so a thrown read never forgets an on radio.
         * A definitive STATE_OFF clears the episode even when it was caused by a
         * Bluetooth-stack blip rather than a real off — that is indistinguishable
         * at this layer, and the resulting re-report of a still-on radio is the
         * accepted fail-safe trade-off. Free of Android framework calls so every
         * branch is unit-testable.
         */
        internal fun resolveBluetoothState(
            previousReported: Boolean,
            bluetoothState: Int
        ): RadioStateResult {
            val live = bluetoothState == BluetoothAdapter.STATE_ON ||
                bluetoothState == BluetoothAdapter.STATE_TURNING_ON
            val nextReported = when (bluetoothState) {
                BluetoothAdapter.STATE_ON,
                BluetoothAdapter.STATE_TURNING_ON -> true
                BluetoothAdapter.STATE_OFF -> false
                else -> previousReported
            }
            return RadioStateResult(
                shouldEmit = live && !previousReported,
                nextReported = nextReported
            )
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

        /**
         * Builds the poll-sourced BLUETOOTH_ACTIVITY breach so the metadata is
         * testable on the JVM. Distinct from the broadcast-sourced breach (which
         * rides [resolveBreach]) via the RADIO_POLL source.
         */
        internal fun bluetoothPollBreach(bluetoothState: Int): BreachEvent = breachOf(
            ViolationType.BLUETOOTH_ACTIVITY,
            rawMetadata = mapOf(
                "wireless_interface" to "BLUETOOTH",
                "state" to bluetoothState.toString(),
                "source" to "RADIO_POLL"
            )
        )

        /**
         * Builds the poll-sourced AIRPLANE_MODE_OFF breach so the metadata is
         * testable on the JVM. Distinct from the broadcast-sourced breach (which
         * rides [resolveBreach]) via the RADIO_POLL source.
         */
        internal fun airplanePollBreach(): BreachEvent = breachOf(
            ViolationType.AIRPLANE_MODE_OFF,
            rawMetadata = mapOf("source" to "RADIO_POLL")
        )

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
