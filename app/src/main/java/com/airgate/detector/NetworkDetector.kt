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

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.airgate.domain.model.BreachEvent
import com.airgate.domain.model.ViolationType
import java.util.UUID

class NetworkDetector(
    private val context: Context,
    private val listener: SignalListener
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            val breaches = resolveBreaches(networkCapabilities)
            val hasWifiTransport =
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            var transceiverToEmit: BreachEvent? = null
            synchronized(radioLatchLock) {
                val result = resolveRadioStateCallback(wifiRadioReported, hasWifiTransport)
                wifiRadioReported = result.nextReported
                if (result.shouldEmit) {
                    transceiverToEmit = breaches.firstOrNull {
                        it.violationType == ViolationType.WIFI_TRANSCEIVER_ENABLED
                    }
                }
            }
            breaches.asSequence()
                .filter { it.violationType != ViolationType.WIFI_TRANSCEIVER_ENABLED }
                .forEach(listener::onBreachDetected)
            transceiverToEmit?.let(listener::onBreachDetected)
        }

        override fun onLost(network: Network) {
            // A monitored network dropping is GOOD for an air-gapped device —
            // disconnection is the enforced state. No breach is raised here; the callback
            // is overridden so future state tracking (e.g. "last validated network") has
            // a clean reset point.
            super.onLost(network)
        }
    }

    // Shared "radio-on episode" latch: WIFI_TRANSCEIVER_ENABLED is reported at most
    // once per radio-on episode, whichever mechanism observes it first. Both the
    // network callback (Wi-Fi transport present on a connected network) and the
    // periodic poll (the radio switch itself) set it, and only the poll's
    // definitive DISABLED observation resets it — so a radio that stays on while
    // connected then drops back to unconnected is one episode, and a transient
    // failed read never forgets an on radio. Guarded because the callback runs on
    // the main thread while the poll runs on the audit thread.
    private val radioLatchLock = Any()
    private var wifiRadioReported = false

    fun startMonitoring() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            // Log or handle permission exception if any
        }
    }

    fun stopMonitoring() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Ignore unregister errors
        }
    }

    /**
     * Periodic poll of the Wi-Fi radio state. The network callback above only
     * fires for connected networks, so a radio that is on but unassociated
     * (scanning, never connected) produces zero callbacks and would otherwise be
     * invisible. Reading [WifiManager.getWifiState] sees the radio switch itself,
     * so every on state is detected regardless of whether any network exists.
     * Runs on the audit loop's background thread.
     */
    fun checkWifiRadioState() {
        val wifiState = readWifiState()
        var emit = false
        synchronized(radioLatchLock) {
            val result = resolveRadioStatePoll(wifiRadioReported, wifiState)
            wifiRadioReported = result.nextReported
            emit = result.shouldEmit
        }
        if (emit) {
            listener.onBreachDetected(radioPollBreach(wifiState))
        }
    }

    private fun readWifiState(): Int {
        return try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.wifiState
        } catch (e: Exception) {
            // A failed read is treated as UNKNOWN: no breach fires for it, the
            // reported-episode state is left untouched, and the next poll
            // re-attempts the read instead of staying poisoned.
            WifiManager.WIFI_STATE_UNKNOWN
        }
    }

    companion object {

        /**
         * Outcome of one radio-state observation for either mechanism: whether a
         * WIFI_TRANSCEIVER_ENABLED breach must be emitted now, and the reported
         * value the caller must remember for the shared episode latch.
         */
        internal data class RadioStateResult(
            val shouldEmit: Boolean,
            val nextReported: Boolean
        )

        /**
         * Pure decision logic for the periodic poll, keyed on the radio switch
         * state alone. A breach fires only when the radio is fully enabled and the
         * episode has not yet been reported, so a sustained radio-on state never
         * re-fires on every tick. Only definitive states move the latch: ENABLED
         * reports, DISABLED clears, and the transitional or unknown states
         * (ENABLING / DISABLING / UNKNOWN — including a failed read) leave it
         * untouched, so a transient read failure can never forget an on radio.
         * The first observation (previous == false) treats an already-enabled
         * radio as a violation: the radio being live is the air-gap breach
         * whether or not it is connected to anything. Free of Android framework
         * calls so every branch is unit-testable.
         */
        internal fun resolveRadioStatePoll(
            previousReported: Boolean,
            wifiState: Int
        ): RadioStateResult {
            val shouldEmit = wifiState == WifiManager.WIFI_STATE_ENABLED && !previousReported
            val nextReported = when (wifiState) {
                WifiManager.WIFI_STATE_ENABLED -> true
                WifiManager.WIFI_STATE_DISABLED -> false
                else -> previousReported
            }
            return RadioStateResult(
                shouldEmit = shouldEmit,
                nextReported = nextReported
            )
        }

        /**
         * Pure decision logic for the network-callback path, keyed on Wi-Fi
         * transport presence. Emits only when a Wi-Fi transport is present and the
         * episode has not already been reported (by this callback or by the poll),
         * which keeps the two mechanisms from recording the same radio-on episode
         * twice. Absence of a transport never clears the latch — only the poll's
         * definitive DISABLED observation ends an episode. Free of Android
         * framework calls so every branch is unit-testable.
         */
        internal fun resolveRadioStateCallback(
            previousReported: Boolean,
            wifiTransportPresent: Boolean
        ): RadioStateResult {
            return RadioStateResult(
                shouldEmit = wifiTransportPresent && !previousReported,
                nextReported = if (wifiTransportPresent) true else previousReported
            )
        }

        /**
         * Builds the poll-sourced WIFI_TRANSCEIVER_ENABLED breach so the metadata
         * is testable on the JVM. Distinct from the callback-sourced breach (which
         * rides the existing [resolveBreaches] path) via the WIFI_POLL source.
         */
        internal fun radioPollBreach(wifiState: Int): BreachEvent = breachOf(
            ViolationType.WIFI_TRANSCEIVER_ENABLED,
            rawMetadata = mapOf(
                "source" to "WIFI_POLL",
                "state" to wifiState.toString()
            )
        )

        /**
         * Framework-coupled entry point: extracts the capability/transport state
         * from a [NetworkCapabilities] snapshot and resolves the breaches to raise.
         * Kept in the companion so the decision logic below stays free of Android
         * framework calls and every branch is JVM-testable.
         */
        internal fun resolveBreaches(caps: NetworkCapabilities): List<BreachEvent> {
            return resolveBreaches(
                hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                hasValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                hasWifiTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                hasCellularTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                hasEthernetTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                hasBluetoothTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
            )
        }

        /**
         * Pure decision logic for one capabilities snapshot: maps the
         * framework-observed capability/transport state to the breaches to raise.
         * Free of Android framework calls so every branch is unit-testable.
         *
         * The Wi-Fi transceiver violation fires on Wi-Fi transport presence alone,
         * independent of NET_CAPABILITY_VALIDATED: the air-gap breach is the radio
         * being live, whether or not the network has passed internet validation
         * (captive portals and LAN-only APs never validate).
         */
        internal fun resolveBreaches(
            hasInternet: Boolean,
            hasValidated: Boolean,
            hasWifiTransport: Boolean,
            hasCellularTransport: Boolean,
            hasEthernetTransport: Boolean,
            hasBluetoothTransport: Boolean
        ): List<BreachEvent> {
            val hasValidatedInternet = hasInternet && hasValidated

            val transportStr = when {
                hasWifiTransport -> "WIFI"
                hasCellularTransport -> "CELLULAR"
                hasEthernetTransport -> "ETHERNET"
                hasBluetoothTransport -> "BLUETOOTH"
                else -> "OTHER"
            }

            // VALIDATED_NETWORK must be transport-agnostic — it must also fire
            // on Wi-Fi, not only when the Wi-Fi branch below happens not to match.
            val source = if (hasWifiTransport) "WIFI_MONITOR" else "NETWORK_MONITOR"

            val breaches = mutableListOf<BreachEvent>()

            if (hasValidatedInternet) {
                breaches += breachOf(
                    ViolationType.VALIDATED_NETWORK,
                    rawMetadata = mapOf("transport" to transportStr, "source" to source)
                )
            }

            // Wi-Fi transport present means the transceiver is live and the air gap
            // is broken — the state itself is the violation, independent of whether
            // the network has validated internet connectivity.
            if (hasWifiTransport) {
                breaches += breachOf(
                    ViolationType.WIFI_TRANSCEIVER_ENABLED,
                    rawMetadata = mapOf("transport" to "WIFI", "source" to source)
                )
            }

            if (hasEthernetTransport && (hasInternet || hasValidated)) {
                breaches += breachOf(
                    ViolationType.OTG_ETHERNET_ATTACHED,
                    rawMetadata = mapOf("transport" to transportStr, "source" to source)
                )
            }

            return breaches
        }

        private fun breachOf(
            violationType: ViolationType,
            rawMetadata: Map<String, String>
        ): BreachEvent {
            return BreachEvent(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                violationType = violationType,
                tier = violationType.defaultTier,
                weight = violationType.defaultWeight,
                rawMetadata = rawMetadata
            )
        }
    }
}
