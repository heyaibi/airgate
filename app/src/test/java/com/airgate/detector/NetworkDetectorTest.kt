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

import com.airgate.domain.model.BreachEvent
import com.airgate.domain.model.ResponseTier
import com.airgate.domain.model.ViolationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [NetworkDetector]. The decision function is free of
 * Android framework calls, so every branch is exercised on the JVM. The core
 * contract under test: the Wi-Fi transceiver violation fires on Wi-Fi transport
 * presence alone — an unvalidated, captive-portal or LAN-only Wi-Fi link is the
 * same air-gap breach as a validated one.
 */
class NetworkDetectorTest {

    // --- Wi-Fi transceiver branch (the transceiver fires on transport alone) ---

    @Test
    fun `wifi with validated internet fires both transceiver and validated network`() {
        val breaches = NetworkDetector.resolveBreaches(
            hasInternet = true,
            hasValidated = true,
            hasWifiTransport = true,
            hasCellularTransport = false,
            hasEthernetTransport = false,
            hasBluetoothTransport = false
        )

        assertEquals(
            listOf(ViolationType.VALIDATED_NETWORK, ViolationType.WIFI_TRANSCEIVER_ENABLED),
            breaches.map { it.violationType }
        )
        assertEquals(listOf(ResponseTier.ALARM_STREAK, ResponseTier.LOG_ONLY), breaches.map { it.tier })
        assertEquals("WIFI", breaches[1].rawMetadata["transport"])
        assertEquals("WIFI_MONITOR", breaches[1].rawMetadata["source"])
    }

    @Test
    fun `wifi with internet but unvalidated fires the transceiver only`() {
        val breaches = NetworkDetector.resolveBreaches(
            hasInternet = true,
            hasValidated = false,
            hasWifiTransport = true,
            hasCellularTransport = false,
            hasEthernetTransport = false,
            hasBluetoothTransport = false
        )

        assertEquals(
            "an unvalidated Wi-Fi link is still a live transceiver",
            listOf(ViolationType.WIFI_TRANSCEIVER_ENABLED),
            breaches.map { it.violationType }
        )
        assertEquals(ResponseTier.LOG_ONLY, breaches.single().tier)
        assertEquals("WIFI", breaches.single().rawMetadata["transport"])
        assertEquals("WIFI_MONITOR", breaches.single().rawMetadata["source"])
    }

    @Test
    fun `wifi with no internet capability at all fires the transceiver`() {
        val breaches = NetworkDetector.resolveBreaches(
            hasInternet = false,
            hasValidated = false,
            hasWifiTransport = true,
            hasCellularTransport = false,
            hasEthernetTransport = false,
            hasBluetoothTransport = false
        )

        assertEquals(listOf(ViolationType.WIFI_TRANSCEIVER_ENABLED), breaches.map { it.violationType })
    }

    @Test
    fun `wifi with validated but no internet fires the transceiver only`() {
        val breaches = NetworkDetector.resolveBreaches(
            hasInternet = false,
            hasValidated = true,
            hasWifiTransport = true,
            hasCellularTransport = false,
            hasEthernetTransport = false,
            hasBluetoothTransport = false
        )

        assertEquals(
            listOf(ViolationType.WIFI_TRANSCEIVER_ENABLED),
            breaches.map { it.violationType }
        )
    }

    @Test
    fun `no wifi transport never fires the transceiver even with validated internet`() {
        val breaches = NetworkDetector.resolveBreaches(
            hasInternet = true,
            hasValidated = true,
            hasWifiTransport = false,
            hasCellularTransport = true,
            hasEthernetTransport = false,
            hasBluetoothTransport = false
        )

        assertTrue(
            "cellular-only validated connection must not produce a Wi-Fi transceiver breach",
            breaches.none { it.violationType == ViolationType.WIFI_TRANSCEIVER_ENABLED }
        )
    }

    @Test
    fun `wifi transceiver breach always carries the WIFI transport metadata`() {
        for (hasInternet in BOOLS) {
            for (hasValidated in BOOLS) {
                val breaches = NetworkDetector.resolveBreaches(
                    hasInternet = hasInternet,
                    hasValidated = hasValidated,
                    hasWifiTransport = true,
                    hasCellularTransport = false,
                    hasEthernetTransport = false,
                    hasBluetoothTransport = false
                )
                val wifi = breaches.single { it.violationType == ViolationType.WIFI_TRANSCEIVER_ENABLED }
                assertEquals("WIFI", wifi.rawMetadata["transport"])
                assertEquals("WIFI_MONITOR", wifi.rawMetadata["source"])
            }
        }
    }

    // --- Validated network branch (transport-agnostic) ---

    @Test
    fun `validated network fires only when internet and validated are both present`() {
        for (hasInternet in BOOLS) {
            for (hasValidated in BOOLS) {
                val breaches = NetworkDetector.resolveBreaches(
                    hasInternet = hasInternet,
                    hasValidated = hasValidated,
                    hasWifiTransport = false,
                    hasCellularTransport = true,
                    hasEthernetTransport = false,
                    hasBluetoothTransport = false
                )
                val validated = breaches.any { it.violationType == ViolationType.VALIDATED_NETWORK }
                assertEquals(
                    "VALIDATED_NETWORK needs internet=$hasInternet AND validated=$hasValidated",
                    hasInternet && hasValidated,
                    validated
                )
            }
        }
    }

    @Test
    fun `validated network on cellular carries CELLULAR transport metadata`() {
        val breaches = NetworkDetector.resolveBreaches(
            hasInternet = true,
            hasValidated = true,
            hasWifiTransport = false,
            hasCellularTransport = true,
            hasEthernetTransport = false,
            hasBluetoothTransport = false
        )

        val validated = breaches.single { it.violationType == ViolationType.VALIDATED_NETWORK }
        assertEquals("CELLULAR", validated.rawMetadata["transport"])
        assertEquals("NETWORK_MONITOR", validated.rawMetadata["source"])
    }

    @Test
    fun `validated network on bluetooth PAN carries BLUETOOTH transport metadata`() {
        val breaches = NetworkDetector.resolveBreaches(
            hasInternet = true,
            hasValidated = true,
            hasWifiTransport = false,
            hasCellularTransport = false,
            hasEthernetTransport = false,
            hasBluetoothTransport = true
        )

        val validated = breaches.single { it.violationType == ViolationType.VALIDATED_NETWORK }
        assertEquals("BLUETOOTH", validated.rawMetadata["transport"])
    }

    @Test
    fun `unvalidated internet never fires the validated network breach`() {
        val breaches = NetworkDetector.resolveBreaches(
            hasInternet = true,
            hasValidated = false,
            hasWifiTransport = false,
            hasCellularTransport = true,
            hasEthernetTransport = false,
            hasBluetoothTransport = false
        )

        assertTrue(
            "a captive-portal/cellular link is not a validated network",
            breaches.none { it.violationType == ViolationType.VALIDATED_NETWORK }
        )
    }

    // --- Ethernet branch (OTG adapter) ---

    @Test
    fun `ethernet with internet fires the OTG ethernet breach`() {
        val breaches = NetworkDetector.resolveBreaches(
            hasInternet = true,
            hasValidated = false,
            hasWifiTransport = false,
            hasCellularTransport = false,
            hasEthernetTransport = true,
            hasBluetoothTransport = false
        )

        assertEquals(listOf(ViolationType.OTG_ETHERNET_ATTACHED), breaches.map { it.violationType })
        assertEquals(ResponseTier.ALARM_STREAK, breaches.single().tier)
        assertEquals("ETHERNET", breaches.single().rawMetadata["transport"])
    }

    @Test
    fun `ethernet with validated only fires the OTG ethernet breach`() {
        val breaches = NetworkDetector.resolveBreaches(
            hasInternet = false,
            hasValidated = true,
            hasWifiTransport = false,
            hasCellularTransport = false,
            hasEthernetTransport = true,
            hasBluetoothTransport = false
        )

        assertEquals(listOf(ViolationType.OTG_ETHERNET_ATTACHED), breaches.map { it.violationType })
    }

    @Test
    fun `ethernet with neither internet nor validated fires nothing`() {
        val breaches = NetworkDetector.resolveBreaches(
            hasInternet = false,
            hasValidated = false,
            hasWifiTransport = false,
            hasCellularTransport = false,
            hasEthernetTransport = true,
            hasBluetoothTransport = false
        )

        assertTrue(breaches.isEmpty())
    }

    // --- Transport string priority ---

    @Test
    fun `transport string prioritizes wifi over cellular over ethernet over bluetooth`() {
        val wifi = NetworkDetector.resolveBreaches(
            hasInternet = true, hasValidated = true,
            hasWifiTransport = true, hasCellularTransport = true,
            hasEthernetTransport = true, hasBluetoothTransport = true
        ).single { it.violationType == ViolationType.VALIDATED_NETWORK }
        assertEquals("WIFI", wifi.rawMetadata["transport"])

        val cellular = NetworkDetector.resolveBreaches(
            hasInternet = true, hasValidated = true,
            hasWifiTransport = false, hasCellularTransport = true,
            hasEthernetTransport = true, hasBluetoothTransport = true
        ).single { it.violationType == ViolationType.VALIDATED_NETWORK }
        assertEquals("CELLULAR", cellular.rawMetadata["transport"])

        val ethernet = NetworkDetector.resolveBreaches(
            hasInternet = true, hasValidated = true,
            hasWifiTransport = false, hasCellularTransport = false,
            hasEthernetTransport = true, hasBluetoothTransport = true
        ).single { it.violationType == ViolationType.VALIDATED_NETWORK }
        assertEquals("ETHERNET", ethernet.rawMetadata["transport"])

        val bluetooth = NetworkDetector.resolveBreaches(
            hasInternet = true, hasValidated = true,
            hasWifiTransport = false, hasCellularTransport = false,
            hasEthernetTransport = false, hasBluetoothTransport = true
        ).single { it.violationType == ViolationType.VALIDATED_NETWORK }
        assertEquals("BLUETOOTH", bluetooth.rawMetadata["transport"])
    }

    @Test
    fun `no transport resolves to OTHER`() {
        val breaches = NetworkDetector.resolveBreaches(
            hasInternet = true, hasValidated = true,
            hasWifiTransport = false, hasCellularTransport = false,
            hasEthernetTransport = false, hasBluetoothTransport = false
        )

        val validated = breaches.single { it.violationType == ViolationType.VALIDATED_NETWORK }
        assertEquals("OTHER", validated.rawMetadata["transport"])
        assertEquals("NETWORK_MONITOR", validated.rawMetadata["source"])
    }

    @Test
    fun `source is WIFI_MONITOR when wifi present else NETWORK_MONITOR`() {
        val wifi = NetworkDetector.resolveBreaches(
            hasInternet = true, hasValidated = true,
            hasWifiTransport = true, hasCellularTransport = true,
            hasEthernetTransport = false, hasBluetoothTransport = false
        ).single { it.violationType == ViolationType.VALIDATED_NETWORK }
        assertEquals("WIFI_MONITOR", wifi.rawMetadata["source"])

        val nonWifi = NetworkDetector.resolveBreaches(
            hasInternet = true, hasValidated = true,
            hasWifiTransport = false, hasCellularTransport = true,
            hasEthernetTransport = false, hasBluetoothTransport = false
        ).single { it.violationType == ViolationType.VALIDATED_NETWORK }
        assertEquals("NETWORK_MONITOR", nonWifi.rawMetadata["source"])
    }

    // --- Tier / weight / ordering ---

    @Test
    fun `transceiver is logged only while validated network and ethernet alarm`() {
        val breaches = NetworkDetector.resolveBreaches(
            hasInternet = true, hasValidated = true,
            hasWifiTransport = true, hasCellularTransport = false,
            hasEthernetTransport = true, hasBluetoothTransport = false
        )

        assertEquals(
            listOf(
                ViolationType.VALIDATED_NETWORK,
                ViolationType.WIFI_TRANSCEIVER_ENABLED,
                ViolationType.OTG_ETHERNET_ATTACHED
            ),
            breaches.map { it.violationType }
        )
        assertEquals(
            listOf(ResponseTier.ALARM_STREAK, ResponseTier.LOG_ONLY, ResponseTier.ALARM_STREAK),
            breaches.map { it.tier }
        )
        assertEquals(listOf(1, 1, 1), breaches.map { it.weight })
    }

    @Test
    fun `breach ids are unique and timestamps are present`() {
        val breaches = NetworkDetector.resolveBreaches(
            hasInternet = true, hasValidated = true,
            hasWifiTransport = true, hasCellularTransport = false,
            hasEthernetTransport = true, hasBluetoothTransport = false
        )

        assertTrue(
            "every breach must carry a unique id",
            breaches.map { it.id }.distinct().size == breaches.size
        )
        assertTrue("every breach must carry a timestamp", breaches.all { it.timestamp > 0L })
    }

    // --- Empty state ---

    @Test
    fun `no capabilities and no transports fire nothing`() {
        assertTrue(
            NetworkDetector.resolveBreaches(
                hasInternet = false, hasValidated = false,
                hasWifiTransport = false, hasCellularTransport = false,
                hasEthernetTransport = false, hasBluetoothTransport = false
            ).isEmpty()
        )
    }

    // --- Exhaustive truth table over all 64 combinations ---

    @Test
    fun `exhaustive truth table - every combination of capabilities and transports`() {
        for (hasInternet in BOOLS) {
            for (hasValidated in BOOLS) {
                for (hasWifi in BOOLS) {
                    for (hasCellular in BOOLS) {
                        for (hasEthernet in BOOLS) {
                            for (hasBluetooth in BOOLS) {
                                val input = "internet=$hasInternet validated=$hasValidated " +
                                    "wifi=$hasWifi cellular=$hasCellular ethernet=$hasEthernet bluetooth=$hasBluetooth"

                                val actual = NetworkDetector.resolveBreaches(
                                    hasInternet, hasValidated,
                                    hasWifi, hasCellular, hasEthernet, hasBluetooth
                                )

                                val expected = expectedBreaches(
                                    hasInternet, hasValidated,
                                    hasWifi, hasCellular, hasEthernet, hasBluetooth
                                )

                                assertEquals(
                                    "violation types for $input",
                                    expected.map { it.violationType },
                                    actual.map { it.violationType }
                                )
                                assertEquals(
                                    "tiers for $input",
                                    expected.map { it.tier },
                                    actual.map { it.tier }
                                )
                                assertEquals(
                                    "metadata for $input",
                                    expected.map { it.rawMetadata },
                                    actual.map { it.rawMetadata }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Independent reference model of the intended decision table, so the
     * exhaustive test above compares the implementation against a separately
     * written spec rather than against itself.
     */
    private fun expectedBreaches(
        hasInternet: Boolean,
        hasValidated: Boolean,
        hasWifi: Boolean,
        hasCellular: Boolean,
        hasEthernet: Boolean,
        hasBluetooth: Boolean
    ): List<BreachEvent> {
        val hasValidatedInternet = hasInternet && hasValidated

        val transportStr = when {
            hasWifi -> "WIFI"
            hasCellular -> "CELLULAR"
            hasEthernet -> "ETHERNET"
            hasBluetooth -> "BLUETOOTH"
            else -> "OTHER"
        }
        val source = if (hasWifi) "WIFI_MONITOR" else "NETWORK_MONITOR"

        val expected = mutableListOf<BreachEvent>()

        if (hasValidatedInternet) {
            expected += breach(
                ViolationType.VALIDATED_NETWORK,
                mapOf("transport" to transportStr, "source" to source)
            )
        }

        if (hasWifi) {
            expected += breach(
                ViolationType.WIFI_TRANSCEIVER_ENABLED,
                mapOf("transport" to "WIFI", "source" to source)
            )
        }

        if (hasEthernet && (hasInternet || hasValidated)) {
            expected += breach(
                ViolationType.OTG_ETHERNET_ATTACHED,
                mapOf("transport" to transportStr, "source" to source)
            )
        }

        return expected
    }

    private fun breach(
        violationType: ViolationType,
        rawMetadata: Map<String, String>
    ): BreachEvent = BreachEvent(
        id = "expected",
        timestamp = 0L,
        violationType = violationType,
        tier = violationType.defaultTier,
        weight = violationType.defaultWeight,
        rawMetadata = rawMetadata
    )

    private companion object {
        val BOOLS: List<Boolean> = listOf(false, true)
    }
}
