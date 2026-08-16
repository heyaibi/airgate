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

            val caps = networkCapabilities
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val hasValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val hasValidatedInternet = hasInternet && hasValidated

            val transportStr = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BLUETOOTH"
                else -> "OTHER"
            }

            val source = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) "WIFI_MONITOR" else "NETWORK_MONITOR"

            // VALIDATED_NETWORK must be transport-agnostic — it must also fire
            // on Wi-Fi, not only when the Wi-Fi branch below happens not to match.
            if (hasValidatedInternet) {
                listener.onBreachDetected(
                    BreachEvent(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        violationType = ViolationType.VALIDATED_NETWORK,
                        tier = ViolationType.VALIDATED_NETWORK.defaultTier,
                        weight = ViolationType.VALIDATED_NETWORK.defaultWeight,
                        rawMetadata = mapOf("transport" to transportStr, "source" to source)
                    )
                )
            }

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && hasValidatedInternet) {
                listener.onBreachDetected(
                    BreachEvent(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        violationType = ViolationType.WIFI_TRANSCEIVER_ENABLED,
                        tier = ViolationType.WIFI_TRANSCEIVER_ENABLED.defaultTier,
                        weight = ViolationType.WIFI_TRANSCEIVER_ENABLED.defaultWeight,
                        rawMetadata = mapOf("transport" to "WIFI", "source" to source)
                    )
                )
            }

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) && (hasInternet || hasValidated)) {
                listener.onBreachDetected(
                    BreachEvent(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        violationType = ViolationType.OTG_ETHERNET_ATTACHED,
                        tier = ViolationType.OTG_ETHERNET_ATTACHED.defaultTier,
                        weight = ViolationType.OTG_ETHERNET_ATTACHED.defaultWeight,
                        rawMetadata = mapOf("transport" to transportStr, "source" to source)
                    )
                )
            }
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
}
