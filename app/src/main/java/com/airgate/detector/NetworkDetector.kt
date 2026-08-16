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
            resolveBreaches(networkCapabilities).forEach(listener::onBreachDetected)
        }

        override fun onLost(network: Network) {
            // A monitored network dropping is GOOD for an air-gapped device —
            // disconnection is the enforced state. No breach is raised here; the callback
            // is overridden so future state tracking (e.g. "last validated network") has
            // a clean reset point.
            super.onLost(network)
        }
    }

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

    companion object {
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
