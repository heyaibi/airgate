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

import android.net.wifi.WifiManager
import com.airgate.domain.model.BreachEvent
import com.airgate.domain.model.ResponseTier
import com.airgate.domain.model.ScoringGroup
import com.airgate.domain.model.ViolationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // --- Wi-Fi radio-state episode latch (the unconnected-radio backstop) ---

    @Test
    fun `an already-on radio at the first poll is reported`() {
        // The core gap this closes: the network callback never fires for a radio
        // that is on but unconnected. The first observation (previous == false)
        // must detect an already-live radio as the violation it is.
        val result = NetworkDetector.resolveRadioStatePoll(
            previousReported = false,
            wifiState = WifiManager.WIFI_STATE_ENABLED
        )

        assertTrue("an already-on radio must be reported", result.shouldEmit)
        assertTrue("reporting the episode must latch it", result.nextReported)
    }

    @Test
    fun `radio enabled after a previous disabled observation is reported`() {
        val result = NetworkDetector.resolveRadioStatePoll(
            previousReported = false,
            wifiState = WifiManager.WIFI_STATE_ENABLED
        )

        assertTrue(result.shouldEmit)
        assertTrue(result.nextReported)
    }

    @Test
    fun `radio staying enabled is never re-reported on a later poll`() {
        val sustained = NetworkDetector.resolveRadioStatePoll(
            previousReported = true,
            wifiState = WifiManager.WIFI_STATE_ENABLED
        )

        assertFalse("a sustained radio-on state must not re-report", sustained.shouldEmit)
        assertTrue("the episode stays latched", sustained.nextReported)
    }

    @Test
    fun `radio turning off then back on is reported again`() {
        var latch = false
        var result = NetworkDetector.resolveRadioStatePoll(latch, WifiManager.WIFI_STATE_ENABLED)
        assertTrue(result.shouldEmit)
        latch = result.nextReported

        result = NetworkDetector.resolveRadioStatePoll(latch, WifiManager.WIFI_STATE_DISABLED)
        assertFalse(result.shouldEmit)
        latch = result.nextReported
        assertFalse("a definitive off must clear the episode", latch)

        result = NetworkDetector.resolveRadioStatePoll(latch, WifiManager.WIFI_STATE_ENABLED)
        assertTrue("a fresh disabled→enabled transition must report again", result.shouldEmit)
    }

    @Test
    fun `a transient unknown read never forgets an on radio`() {
        // A failed/unknown read is not evidence the radio turned off: the episode
        // must stay latched so the next ENABLED read (same physical episode) is
        // not reported twice.
        val unknown = NetworkDetector.resolveRadioStatePoll(
            previousReported = true,
            wifiState = WifiManager.WIFI_STATE_UNKNOWN
        )
        assertFalse(unknown.shouldEmit)
        assertTrue("an unknown read must not clear a latched episode", unknown.nextReported)

        val afterRecovery = NetworkDetector.resolveRadioStatePoll(
            previousReported = unknown.nextReported,
            wifiState = WifiManager.WIFI_STATE_ENABLED
        )
        assertFalse("recovery to the same episode must not re-report", afterRecovery.shouldEmit)
    }

    @Test
    fun `a transient unknown read after off still recovers and reports the next on`() {
        val unknown = NetworkDetector.resolveRadioStatePoll(
            previousReported = false,
            wifiState = WifiManager.WIFI_STATE_UNKNOWN
        )
        assertFalse(unknown.shouldEmit)
        assertFalse(unknown.nextReported)

        val recovered = NetworkDetector.resolveRadioStatePoll(
            previousReported = unknown.nextReported,
            wifiState = WifiManager.WIFI_STATE_ENABLED
        )
        assertTrue("a fresh on after an unknown read must report", recovered.shouldEmit)
    }

    @Test
    fun `radio disabled or transitioning never reports regardless of previous state`() {
        for (previous in BOOLS) {
            for (state in NON_ENABLED_STATES) {
                val result = NetworkDetector.resolveRadioStatePoll(previous, state)
                assertFalse(
                    "state $state with previous=$previous must never report",
                    result.shouldEmit
                )
            }
        }
    }

    @Test
    fun `only definitive states move the latch`() {
        // DISABLED clears; the transitional/unknown states keep the previous value.
        assertEquals(false, NetworkDetector.resolveRadioStatePoll(true, WifiManager.WIFI_STATE_DISABLED).nextReported)
        assertEquals(false, NetworkDetector.resolveRadioStatePoll(false, WifiManager.WIFI_STATE_DISABLED).nextReported)
        assertEquals(true, NetworkDetector.resolveRadioStatePoll(true, WifiManager.WIFI_STATE_DISABLING).nextReported)
        assertEquals(false, NetworkDetector.resolveRadioStatePoll(false, WifiManager.WIFI_STATE_DISABLING).nextReported)
        assertEquals(true, NetworkDetector.resolveRadioStatePoll(true, WifiManager.WIFI_STATE_ENABLING).nextReported)
        assertEquals(false, NetworkDetector.resolveRadioStatePoll(false, WifiManager.WIFI_STATE_ENABLING).nextReported)
        assertEquals(true, NetworkDetector.resolveRadioStatePoll(true, WifiManager.WIFI_STATE_UNKNOWN).nextReported)
        assertEquals(false, NetworkDetector.resolveRadioStatePoll(false, WifiManager.WIFI_STATE_UNKNOWN).nextReported)
    }

    // --- Wi-Fi radio-state callback path (connected-network transceiver) ---

    @Test
    fun `wifi transport present with nothing reported is reported`() {
        val result = NetworkDetector.resolveRadioStateCallback(
            previousReported = false,
            wifiTransportPresent = true
        )

        assertTrue(result.shouldEmit)
        assertTrue(result.nextReported)
    }

    @Test
    fun `wifi transport present when already reported is not re-reported`() {
        val result = NetworkDetector.resolveRadioStateCallback(
            previousReported = true,
            wifiTransportPresent = true
        )

        assertFalse("a connected network must not re-report an open episode", result.shouldEmit)
        assertTrue(result.nextReported)
    }

    @Test
    fun `no wifi transport never reports and never clears the episode`() {
        val fresh = NetworkDetector.resolveRadioStateCallback(false, wifiTransportPresent = false)
        assertFalse(fresh.shouldEmit)
        assertFalse(fresh.nextReported)

        val latched = NetworkDetector.resolveRadioStateCallback(true, wifiTransportPresent = false)
        assertFalse(latched.shouldEmit)
        assertTrue("a transport-absent snapshot is not evidence the radio is off", latched.nextReported)
    }

    // --- Cross-mechanism coordination (one report per radio-on episode) ---

    @Test
    fun `a radio-on episode is reported exactly once across poll then callback`() {
        var latch = false

        // Poll observes the radio switch turning on first.
        var result = NetworkDetector.resolveRadioStatePoll(latch, WifiManager.WIFI_STATE_ENABLED)
        assertTrue("the poll must report the episode", result.shouldEmit)
        latch = result.nextReported

        // The network connects; the callback observes Wi-Fi transport for the same
        // episode and must not duplicate the report.
        result = NetworkDetector.resolveRadioStateCallback(latch, wifiTransportPresent = true)
        assertFalse("the callback must not duplicate the poll's report", result.shouldEmit)
        latch = result.nextReported

        // Later polls on the same episode stay silent too.
        result = NetworkDetector.resolveRadioStatePoll(latch, WifiManager.WIFI_STATE_ENABLED)
        assertFalse(result.shouldEmit)
        latch = result.nextReported

        // The radio turns off (definitive) and back on: a new episode.
        result = NetworkDetector.resolveRadioStatePoll(latch, WifiManager.WIFI_STATE_DISABLED)
        latch = result.nextReported
        result = NetworkDetector.resolveRadioStateCallback(latch, wifiTransportPresent = true)
        assertTrue("a fresh episode after off must be reported once", result.shouldEmit)
    }

    @Test
    fun `a radio-on episode is reported exactly once across callback then poll`() {
        var latch = false

        // The device is already connected when the service starts: the callback
        // observes Wi-Fi transport first.
        var result = NetworkDetector.resolveRadioStateCallback(latch, wifiTransportPresent = true)
        assertTrue("the callback must report the episode", result.shouldEmit)
        latch = result.nextReported

        // The first poll then sees the radio switch on for the same episode.
        result = NetworkDetector.resolveRadioStatePoll(latch, WifiManager.WIFI_STATE_ENABLED)
        assertFalse("the poll must not duplicate the callback's report", result.shouldEmit)
        latch = result.nextReported

        // Repeated transport snapshots stay silent.
        result = NetworkDetector.resolveRadioStateCallback(latch, wifiTransportPresent = true)
        assertFalse(result.shouldEmit)
        latch = result.nextReported
    }

    // --- Poll breach construction ---

    @Test
    fun `poll breach is log-only in the wireless group with the WIFI_POLL source`() {
        val breach = NetworkDetector.radioPollBreach(WifiManager.WIFI_STATE_ENABLED)

        assertEquals(ViolationType.WIFI_TRANSCEIVER_ENABLED, breach.violationType)
        assertEquals(ResponseTier.LOG_ONLY, breach.tier)
        assertEquals(ScoringGroup.WIRELESS, breach.violationType.scoringGroup)
        assertEquals(1, breach.weight)
        assertEquals("WIFI_POLL", breach.rawMetadata["source"])
        assertEquals(
            WifiManager.WIFI_STATE_ENABLED.toString(),
            breach.rawMetadata["state"]
        )
    }

    @Test
    fun `poll breach carries a unique id and a timestamp`() {
        val first = NetworkDetector.radioPollBreach(WifiManager.WIFI_STATE_ENABLED)
        val second = NetworkDetector.radioPollBreach(WifiManager.WIFI_STATE_ENABLED)

        assertTrue("each poll breach must carry a unique id", first.id != second.id)
        assertTrue("each poll breach must carry a timestamp", first.timestamp > 0L)
    }

    @Test
    fun `exhaustive radio-state poll table - every state and previous combination`() {
        for (previous in BOOLS) {
            for (state in ALL_POLL_STATES) {
                val result = NetworkDetector.resolveRadioStatePoll(previous, state)
                val message = "state=$state previous=$previous"

                assertEquals(
                    message,
                    state == WifiManager.WIFI_STATE_ENABLED && !previous,
                    result.shouldEmit
                )
                assertEquals(
                    message,
                    when (state) {
                        WifiManager.WIFI_STATE_ENABLED -> true
                        WifiManager.WIFI_STATE_DISABLED -> false
                        else -> previous
                    },
                    result.nextReported
                )
            }
        }
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

        /** Every state that must never be treated as a live radio. */
        val NON_ENABLED_STATES: List<Int> = listOf(
            WifiManager.WIFI_STATE_DISABLING,
            WifiManager.WIFI_STATE_DISABLED,
            WifiManager.WIFI_STATE_ENABLING,
            WifiManager.WIFI_STATE_UNKNOWN
        )

        /** Every real state plus malformed values the framework can never return. */
        val ALL_POLL_STATES: List<Int> = NON_ENABLED_STATES + listOf(
            WifiManager.WIFI_STATE_ENABLED,
            99,
            -1,
            Int.MAX_VALUE
        )
    }
}
