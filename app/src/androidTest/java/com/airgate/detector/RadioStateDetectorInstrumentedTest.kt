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
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Framework-coupled tests for [RadioStateDetector] on a real device/emulator:
 *
 *  1. the registered intent filter matches the trusted protected system
 *     broadcasts and never matches the spoofable FM radio actions;
 *  2. an actual broadcast of an FM action (which any installed app can send) is
 *     delivered to a matching control receiver but never reaches the detector —
 *     proving the filter, not the delivery mechanism, is what blocks the spoof;
 *  3. the airplane-mode and Bluetooth handling still fires the expected breaches
 *     through the real [Settings] read and intent extras;
 *  4. the periodic poll ([RadioStateDetector.checkRadioState]) reads the live
 *     airplane-mode setting, reports an already-violating state exactly once per
 *     episode, and shares the episode latch with the broadcast path.
 */
@RunWith(AndroidJUnit4::class)
class RadioStateDetectorInstrumentedTest {

    private class RecordingListener : SignalListener {
        val breaches = mutableListOf<BreachEvent>()
        override fun onBreachDetected(event: BreachEvent) {
            breaches.add(event)
        }
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun filterMatchesTrustedActionsOnly_neverFmActions() {
        val filter = RadioStateDetector(context, RecordingListener()).getIntentFilter()

        for (action in TRUSTED_ACTIONS) {
            assertTrue(
                "filter must contain trusted action $action",
                filter.hasAction(action)
            )
        }

        assertFalse(
            "filter must not contain the caf FM action",
            filter.hasAction(FM_CAF_ACTION)
        )
        assertFalse(
            "filter must not contain the aosp hardware FM action",
            filter.hasAction(FM_AOSP_ACTION)
        )
    }

    @Test
    fun spoofedFmBroadcasts_areDeliverableButNeverReachTheDetector() {
        val listener = RecordingListener()
        val detector = RadioStateDetector(context, listener)

        // Control receivers that DO match each FM action: they must be delivered,
        // proving that any installed app can send FM broadcasts and that only the
        // detector's own filter is what keeps them out.
        val cafControlFired = CountDownLatch(1)
        val cafControl = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                cafControlFired.countDown()
            }
        }
        ContextCompat.registerReceiver(
            context,
            cafControl,
            IntentFilter(FM_CAF_ACTION),
            ContextCompat.RECEIVER_EXPORTED
        )

        val aospControlFired = CountDownLatch(1)
        val aospControl = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                aospControlFired.countDown()
            }
        }
        ContextCompat.registerReceiver(
            context,
            aospControl,
            IntentFilter(FM_AOSP_ACTION),
            ContextCompat.RECEIVER_EXPORTED
        )

        ContextCompat.registerReceiver(
            context,
            detector,
            detector.getIntentFilter(),
            ContextCompat.RECEIVER_EXPORTED
        )

        try {
            sendFmBroadcasts()

            // If the control receivers registered and delivered, the latches fire;
            // a timeout means the send mechanism itself failed, which would make a
            // "no breach" result meaningless.
            assertTrue(
                "control receiver must receive the caf FM broadcast (delivery must work)",
                cafControlFired.await(5, TimeUnit.SECONDS)
            )
            assertTrue(
                "control receiver must receive the aosp hardware FM broadcast (delivery must work)",
                aospControlFired.await(5, TimeUnit.SECONDS)
            )
            SystemClock.sleep(500)

            assertTrue(
                "spoofed FM broadcasts must never reach the detector",
                listener.breaches.isEmpty()
            )
        } finally {
            context.unregisterReceiver(cafControl)
            context.unregisterReceiver(aospControl)
            context.unregisterReceiver(detector)
        }
    }

    @Test
    fun airplaneModeChanged_firesBreachOnlyWhenAirplaneIsOff() {
        val listener = RecordingListener()
        val detector = RadioStateDetector(context, listener)
        val resolver = context.contentResolver

        // Primary wiring check (no permission required): onReceive must read the
        // real airplane-mode setting and fire exactly when airplane mode is off.
        val current = Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0)
        detector.onReceive(context, airplaneModeChanged())
        assertEquals(
            "onReceive must reflect the real airplane-mode setting ($current)",
            if (current == 0) listOf(ViolationType.AIRPLANE_MODE_OFF) else emptyList(),
            listener.breaches.map { it.violationType }
        )

        // Stronger check when WRITE_SECURE_SETTINGS is provisioned (it throws
        // SecurityException when absent): force both airplane-mode states and
        // verify each branch end to end, then restore the original value.
        provisionWriteSecureSettings()
        val original = current
        val writable = runCatching {
            Settings.Global.putInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 1)
        }.getOrDefault(false)
        try {
            if (writable) {
                listener.breaches.clear()
                detector.onReceive(context, airplaneModeChanged())
                assertTrue("airplane on must not fire a breach", listener.breaches.isEmpty())

                assertTrue(Settings.Global.putInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0))
                listener.breaches.clear()
                detector.onReceive(context, airplaneModeChanged())
                assertEquals(listOf(ViolationType.AIRPLANE_MODE_OFF), listener.breaches.map { it.violationType })
            }
        } finally {
            runCatching { Settings.Global.putInt(resolver, Settings.Global.AIRPLANE_MODE_ON, original) }
        }
    }

    @Test
    fun bluetoothStateChanged_reportsOncePerOnEpisodeAcrossBroadcasts() {
        val listener = RecordingListener()
        val detector = RadioStateDetector(context, listener)

        detector.onReceive(context, bluetoothStateChanged(BluetoothAdapter.STATE_ON))
        assertEquals(listOf(ViolationType.BLUETOOTH_ACTIVITY), listener.breaches.map { it.violationType })
        assertEquals(listOf(ResponseTier.ALARM_STREAK), listener.breaches.map { it.tier })

        // The ON episode is latched: later state broadcasts for the same episode
        // (TURNING_ON / repeated ON) must not duplicate the report.
        detector.onReceive(context, bluetoothStateChanged(BluetoothAdapter.STATE_TURNING_ON))
        detector.onReceive(context, bluetoothStateChanged(BluetoothAdapter.STATE_ON))
        assertEquals(
            "the on episode must be reported exactly once across broadcasts",
            1,
            listener.breaches.count { it.violationType == ViolationType.BLUETOOTH_ACTIVITY }
        )

        // OFF opens the latch; a fresh ON is a new episode and reports again.
        detector.onReceive(context, bluetoothStateChanged(BluetoothAdapter.STATE_OFF))
        assertEquals(
            "BT OFF must not fire a breach",
            1,
            listener.breaches.count { it.violationType == ViolationType.BLUETOOTH_ACTIVITY }
        )

        detector.onReceive(context, bluetoothStateChanged(BluetoothAdapter.STATE_ON))
        assertEquals(
            "a fresh ON after OFF must report a new episode",
            2,
            listener.breaches.count { it.violationType == ViolationType.BLUETOOTH_ACTIVITY }
        )
    }

    @Test
    fun bluetoothDiscoveryEvents_areLoggedOnly() {
        val listener = RecordingListener()
        val detector = RadioStateDetector(context, listener)

        for (action in DISCOVERY_ACTIONS) {
            listener.breaches.clear()
            detector.onReceive(context, Intent(action))
            assertEquals(
                "$action must yield exactly one BLUETOOTH_ACTIVITY breach",
                listOf(ViolationType.BLUETOOTH_ACTIVITY),
                listener.breaches.map { it.violationType }
            )
            assertEquals(
                "$action must be logged only, never alarm/streak",
                listOf(ResponseTier.LOG_ONLY),
                listener.breaches.map { it.tier }
            )
        }
    }

    @Test
    fun checkRadioState_airplanePoll_isDeterministicPerEpisode() {
        val listener = RecordingListener()
        val detector = RadioStateDetector(context, listener)
        val resolver = context.contentResolver

        provisionWriteSecureSettings()
        val original = Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0)
        val writable = runCatching {
            Settings.Global.putInt(resolver, Settings.Global.AIRPLANE_MODE_ON, original)
        }.getOrDefault(false)
        try {
            if (!writable) return // airplane poll needs WRITE_SECURE_SETTINGS to be exercised deterministically

            fun airplaneBreaches() = listener.breaches.filter {
                it.violationType == ViolationType.AIRPLANE_MODE_OFF
            }

            // Airplane ON: the poll must not fire.
            assertTrue(Settings.Global.putInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 1))
            detector.checkRadioState()
            assertEquals(
                "airplane on must not fire an AIRPLANE_MODE_OFF breach",
                emptyList<ViolationType>(),
                airplaneBreaches().map { it.violationType }
            )

            // Airplane OFF: the very first poll observation (the initial-state case
            // at service start) must fire exactly one poll-sourced breach.
            assertTrue(Settings.Global.putInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0))
            detector.checkRadioState()
            assertEquals(
                "airplane off must fire a poll-sourced AIRPLANE_MODE_OFF breach",
                listOf(ViolationType.AIRPLANE_MODE_OFF),
                airplaneBreaches().map { it.violationType }
            )
            assertEquals("RADIO_POLL", airplaneBreaches().single().rawMetadata["source"])

            // Sustained OFF on later ticks must not re-fire (episode latched).
            detector.checkRadioState()
            detector.checkRadioState()
            assertEquals(1, listener.breaches.count { it.violationType == ViolationType.AIRPLANE_MODE_OFF })

            // Back ON then OFF again: a new episode reports once.
            assertTrue(Settings.Global.putInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 1))
            detector.checkRadioState()
            assertEquals(1, listener.breaches.count { it.violationType == ViolationType.AIRPLANE_MODE_OFF })
            assertTrue(Settings.Global.putInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0))
            detector.checkRadioState()
            assertEquals(
                "a fresh OFF after ON must report a new episode",
                2,
                listener.breaches.count { it.violationType == ViolationType.AIRPLANE_MODE_OFF }
            )
        } finally {
            runCatching { Settings.Global.putInt(resolver, Settings.Global.AIRPLANE_MODE_ON, original) }
        }
    }

    @Test
    fun checkRadioState_neverThrows_andEmitsOnlyWellFormedPollBreaches() {
        // The exact emissions depend on the device's live radio state (not
        // asserted here); what is stable regardless of state is that the poll
        // never throws, emits only correctly-formed poll breaches, and reports
        // each signal at most once per poll call (the once-per-episode latch).
        // The deterministic airplane-poll behavior is pinned by
        // [checkRadioState_airplanePoll_isDeterministicPerEpisode].
        val listener = RecordingListener()
        val detector = RadioStateDetector(context, listener)

        repeat(3) { detector.checkRadioState() }

        for (breach in listener.breaches) {
            when (breach.violationType) {
                ViolationType.BLUETOOTH_ACTIVITY -> {
                    assertEquals(ResponseTier.ALARM_STREAK, breach.tier)
                    assertEquals("BLUETOOTH", breach.rawMetadata["wireless_interface"])
                    assertEquals("RADIO_POLL", breach.rawMetadata["source"])
                    assertTrue(!breach.rawMetadata["state"].isNullOrEmpty())
                }
                ViolationType.AIRPLANE_MODE_OFF -> {
                    assertEquals(ResponseTier.ALARM_STREAK, breach.tier)
                    assertEquals("RADIO_POLL", breach.rawMetadata["source"])
                }
                else -> throw AssertionError("unexpected poll breach ${breach.violationType}")
            }
        }
        assertTrue(
            "bluetooth must be reported at most once per poll",
            listener.breaches.count { it.violationType == ViolationType.BLUETOOTH_ACTIVITY } <= 3
        )
        assertTrue(
            "airplane must be reported at most once per poll",
            listener.breaches.count { it.violationType == ViolationType.AIRPLANE_MODE_OFF } <= 3
        )
    }

    /**
     * Best-effort grant of WRITE_SECURE_SETTINGS so the deterministic
     * airplane-mode tests run on every device/emulator (including CI), not just
     * on locally-provisioned images. No-op when the image refuses the grant;
     * the tests' `writable` checks then skip gracefully.
     */
    private fun provisionWriteSecureSettings() {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(
                "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
            )
        try {
            java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        } finally {
            pfd.close()
        }
        Thread.sleep(300)
    }

    private fun sendFmBroadcasts() {
        val extrasVariants = listOf(
            mapOf("state" to 1),
            mapOf("is_on" to true),
            mapOf("state" to 1, "is_on" to true)
        )
        for (action in listOf(FM_CAF_ACTION, FM_AOSP_ACTION)) {
            for (extras in extrasVariants) {
                val intent = Intent(action)
                for ((key, value) in extras) {
                    when (value) {
                        is Int -> intent.putExtra(key, value)
                        is Boolean -> intent.putExtra(key, value)
                    }
                }
                context.sendBroadcast(intent)
            }
        }
    }

    private fun airplaneModeChanged(): Intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)

    private fun bluetoothStateChanged(state: Int): Intent =
        Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, state)

    private companion object {
        const val FM_CAF_ACTION = "com.caf.fmradio.FM_STATE_CHANGED"
        const val FM_AOSP_ACTION = "android.hardware.fm.action.FM_STATE_CHANGED"

        val TRUSTED_ACTIONS: List<String> = listOf(
            Intent.ACTION_AIRPLANE_MODE_CHANGED,
            BluetoothAdapter.ACTION_STATE_CHANGED,
            BluetoothDevice.ACTION_FOUND,
            BluetoothAdapter.ACTION_DISCOVERY_STARTED,
            BluetoothDevice.ACTION_BOND_STATE_CHANGED
        )

        val DISCOVERY_ACTIONS: List<String> = listOf(
            BluetoothDevice.ACTION_FOUND,
            BluetoothAdapter.ACTION_DISCOVERY_STARTED,
            BluetoothDevice.ACTION_BOND_STATE_CHANGED
        )
    }
}
