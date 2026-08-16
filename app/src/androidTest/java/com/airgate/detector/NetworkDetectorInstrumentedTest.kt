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
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airgate.domain.model.BreachEvent
import com.airgate.domain.model.ResponseTier
import com.airgate.domain.model.ViolationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Framework-coupled tests for [NetworkDetector] on a real device/emulator:
 *
 *  1. the capability-extraction seam ([NetworkDetector.resolveBreaches]) maps a
 *     real [NetworkCapabilities] object — including a Wi-Fi transport that is
 *     NOT validated — to the correct breach set;
 *  2. the registration lifecycle (start/stop) round-trips against the real
 *     [ConnectivityManager] without throwing;
 *  3. when the device has an active internet network, the registered callback is
 *     delivered and the resulting breaches are consistent with the capabilities
 *     of that network.
 */
@RunWith(AndroidJUnit4::class)
class NetworkDetectorInstrumentedTest {

    private class RecordingListener : SignalListener {
        val breaches = mutableListOf<BreachEvent>()
        val fired = CountDownLatch(1)
        override fun onBreachDetected(event: BreachEvent) {
            synchronized(this) {
                breaches.add(event)
            }
            fired.countDown()
        }
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    // --- Capability-extraction seam over real NetworkCapabilities objects ---

    @Test
    fun realCaps_wifiUnvalidated_firesTransceiverOnly() {
        val caps = capsWith(
            transports = intArrayOf(NetworkCapabilities.TRANSPORT_WIFI),
            capabilities = intArrayOf(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        )

        val breaches = NetworkDetector.resolveBreaches(caps)

        assertEquals(
            "an unvalidated Wi-Fi link must fire the transceiver breach",
            listOf(ViolationType.WIFI_TRANSCEIVER_ENABLED),
            breaches.map { it.violationType }
        )
        assertEquals(
            "and the transceiver breach must stay LOG_ONLY",
            listOf(ResponseTier.LOG_ONLY),
            breaches.map { it.tier }
        )
        assertEquals("WIFI", breaches.single().rawMetadata["transport"])
        assertEquals("WIFI_MONITOR", breaches.single().rawMetadata["source"])
    }

    @Test
    fun realCaps_wifiValidated_firesTransceiverAndValidatedNetwork() {
        val caps = capsWith(
            transports = intArrayOf(NetworkCapabilities.TRANSPORT_WIFI),
            capabilities = intArrayOf(
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            )
        )

        val breaches = NetworkDetector.resolveBreaches(caps)

        assertEquals(
            listOf(ViolationType.VALIDATED_NETWORK, ViolationType.WIFI_TRANSCEIVER_ENABLED),
            breaches.map { it.violationType }
        )
        assertEquals(
            listOf(ResponseTier.ALARM_STREAK, ResponseTier.LOG_ONLY),
            breaches.map { it.tier }
        )
    }

    @Test
    fun realCaps_cellularValidated_firesValidatedNetworkOnly() {
        val caps = capsWith(
            transports = intArrayOf(NetworkCapabilities.TRANSPORT_CELLULAR),
            capabilities = intArrayOf(
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            )
        )

        val breaches = NetworkDetector.resolveBreaches(caps)

        assertEquals(
            listOf(ViolationType.VALIDATED_NETWORK),
            breaches.map { it.violationType }
        )
        assertEquals("CELLULAR", breaches.single().rawMetadata["transport"])
        assertEquals("NETWORK_MONITOR", breaches.single().rawMetadata["source"])
    }

    @Test
    fun realCaps_cellularUnvalidated_firesNothing() {
        val caps = capsWith(
            transports = intArrayOf(NetworkCapabilities.TRANSPORT_CELLULAR),
            capabilities = intArrayOf(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        )

        assertTrue(
            "an unvalidated cellular link is neither a validated network nor a Wi-Fi transceiver",
            NetworkDetector.resolveBreaches(caps).isEmpty()
        )
    }

    @Test
    fun realCaps_ethernetWithInternet_firesOtgEthernet() {
        val caps = capsWith(
            transports = intArrayOf(NetworkCapabilities.TRANSPORT_ETHERNET),
            capabilities = intArrayOf(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        )

        val breaches = NetworkDetector.resolveBreaches(caps)

        assertEquals(listOf(ViolationType.OTG_ETHERNET_ATTACHED), breaches.map { it.violationType })
        assertEquals(ResponseTier.ALARM_STREAK, breaches.single().tier)
        assertEquals("ETHERNET", breaches.single().rawMetadata["transport"])
    }

    @Test
    fun realCaps_empty_firesNothing() {
        assertTrue(NetworkDetector.resolveBreaches(NetworkCapabilities()).isEmpty())
    }

    // --- Wi-Fi radio-state episode latch against the real framework state ---

    @Test
    fun radioStatePoll_seamReportsOnlyEnabled_withRealConstants() {
        val enabled = NetworkDetector.resolveRadioStatePoll(
            previousReported = false,
            wifiState = WifiManager.WIFI_STATE_ENABLED
        )
        assertTrue("an enabled radio must be reported", enabled.shouldEmit)
        assertTrue(enabled.nextReported)

        for (state in listOf(
            WifiManager.WIFI_STATE_DISABLING,
            WifiManager.WIFI_STATE_DISABLED,
            WifiManager.WIFI_STATE_ENABLING,
            WifiManager.WIFI_STATE_UNKNOWN
        )) {
            val result = NetworkDetector.resolveRadioStatePoll(false, state)
            assertFalse("state $state must never report", result.shouldEmit)
        }
    }

    @Test
    fun radioStatePoll_seamRecoversFromTransientUnknown_withRealConstants() {
        val unknownWhileLatched = NetworkDetector.resolveRadioStatePoll(
            previousReported = true,
            wifiState = WifiManager.WIFI_STATE_UNKNOWN
        )
        assertFalse(unknownWhileLatched.shouldEmit)
        assertTrue("an unknown read must not clear a latched episode", unknownWhileLatched.nextReported)

        val afterRecovery = NetworkDetector.resolveRadioStatePoll(
            unknownWhileLatched.nextReported,
            WifiManager.WIFI_STATE_ENABLED
        )
        assertFalse("recovery to the same episode must not re-report", afterRecovery.shouldEmit)
    }

    @Test
    fun radioPollBreach_matchesTheRealConstants() {
        val breach = NetworkDetector.radioPollBreach(WifiManager.WIFI_STATE_ENABLED)

        assertEquals(ViolationType.WIFI_TRANSCEIVER_ENABLED, breach.violationType)
        assertEquals(ResponseTier.LOG_ONLY, breach.tier)
        assertEquals("WIFI_POLL", breach.rawMetadata["source"])
        assertEquals(
            WifiManager.WIFI_STATE_ENABLED.toString(),
            breach.rawMetadata["state"]
        )
    }

    @Test
    fun checkWifiRadioState_readsTheRealWifiManagerRadioState() {
        // The radio state on a test device is deliberately not asserted against a
        // live snapshot: Wi-Fi toggles on/off during emulator boot, so a snapshot
        // read *after* the detector's own read could disagree and make the test
        // flaky. The on-device contract that is stable regardless of state:
        // the poll reads the real WifiManager without throwing, emits only
        // correctly-formed WIFI_TRANSCEIVER_ENABLED breaches, and never emits more
        // than one per poll. The exact per-state decision table and the episode
        // latch are covered exhaustively in the JVM suite.
        val listener = RecordingListener()
        val detector = NetworkDetector(context, listener)

        detector.checkWifiRadioState()
        detector.checkWifiRadioState()
        detector.checkWifiRadioState()

        assertTrue(
            "the poll must emit at most one breach per poll (three polls, one per episode)",
            listener.breaches.size <= 3
        )
        for (breach in listener.breaches) {
            assertEquals(ViolationType.WIFI_TRANSCEIVER_ENABLED, breach.violationType)
            assertEquals(ResponseTier.LOG_ONLY, breach.tier)
            assertEquals("WIFI_POLL", breach.rawMetadata["source"])
            assertTrue("the poll breach must carry the observed state", !breach.rawMetadata["state"].isNullOrEmpty())
        }
    }

    // --- Registration lifecycle against the real ConnectivityManager ---

    @Test
    fun registrationLifecycle_roundTripsWithoutThrowing() {
        val detector = NetworkDetector(context, RecordingListener())

        detector.startMonitoring()
        detector.startMonitoring() // double-start must be harmless
        detector.stopMonitoring()
        detector.stopMonitoring() // double-stop must be harmless
        detector.stopMonitoring() // stopping a never-started instance must be harmless too
    }

    @Test
    fun activeNetwork_callbackDeliversBreachesConsistentWithItsCapabilities() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // The emulator exposes several networks (cellular + Wi-Fi + virtual), and
        // the framework delivers the current state of every matching network to a
        // freshly registered callback. So instead of comparing against a snapshot
        // read before registration, a control callback records the capabilities the
        // framework actually delivers, and the detector's breaches must be exactly
        // derivable from one of those delivered snapshots.
        val controlCaps = mutableListOf<NetworkCapabilities>()
        val controlGotCaps = CountDownLatch(1)
        val control = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                synchronized(controlCaps) {
                    controlCaps.add(NetworkCapabilities(caps))
                }
                controlGotCaps.countDown()
            }
        }
        val request = NetworkRequestBuilder().internetCapabilityOnly()

        val listener = RecordingListener()
        val detector = NetworkDetector(context, listener)

        try {
            cm.registerNetworkCallback(request, control)
            detector.startMonitoring()

            val anyDelivered = listener.fired.await(10, TimeUnit.SECONDS) ||
                controlGotCaps.await(10, TimeUnit.SECONDS)

            // No matching (INTERNET-capable) network: neither callback may fire,
            // and registration must not have crashed. Otherwise every delivered
            // breach set must be derivable from a delivered capabilities snapshot.
            if (!anyDelivered) {
                assertTrue("no internet network must not deliver breaches", listener.breaches.isEmpty())
                synchronized(controlCaps) {
                    assertTrue("no internet network must not deliver capabilities", controlCaps.isEmpty())
                }
                return
            }

            SystemClock.sleep(500) // let the control callback capture the same tick

            val delivered = listener.breaches.map { it.violationType }.toSet()
            assertTrue("the detector callback must deliver at least one breach", delivered.isNotEmpty())

            val derivable = synchronized(controlCaps) {
                controlCaps.any { caps ->
                    NetworkDetector.resolveBreaches(caps).map { it.violationType }.toSet() == delivered
                }
            }
            assertTrue(
                "delivered breaches $delivered must be derivable from a delivered capabilities snapshot " +
                    "(control captured ${synchronized(controlCaps) { controlCaps.size }} snapshot(s))",
                derivable
            )
        } finally {
            detector.stopMonitoring()
            cm.unregisterNetworkCallback(control)
        }
    }

    /**
     * Builds a real [NetworkCapabilities] instance. The public SDK hides the
     * mutators `addTransportType`/`addCapability` (they are @hide in AOSP), so
     * the instance is constructed on-device and the mutators are invoked through
     * the project's [HiddenApiBypass] dependency — the same mechanism the
     * Dhizuku bridge uses to reach restricted APIs. The object itself is a real
     * framework instance, so `hasTransport`/`hasCapability` (public) behave
     * exactly as they do for system-provided capabilities.
     */
    private fun capsWith(
        transports: IntArray,
        capabilities: IntArray
    ): NetworkCapabilities {
        val caps = NetworkCapabilities()
        for (transport in transports) {
            HiddenApiBypass.invoke(
                NetworkCapabilities::class.java, caps, "addTransportType", transport
            )
        }
        for (capability in capabilities) {
            HiddenApiBypass.invoke(
                NetworkCapabilities::class.java, caps, "addCapability", capability
            )
        }
        return caps
    }

    /** Mirrors the request NetworkDetector builds in [NetworkDetector.startMonitoring]. */
    private class NetworkRequestBuilder {
        fun internetCapabilityOnly(): NetworkRequest {
            return NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        }
    }
}
