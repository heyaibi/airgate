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
import android.content.Intent
import android.hardware.usb.UsbManager
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

/**
 * Framework-coupled tests for [UsbDetector] on a real device/emulator:
 *
 *  1. the registered intent filter matches the two reliably-delivered USB actions
 *     (device attach broadcast + USB_STATE sticky broadcast) and never
 *     [UsbManager.ACTION_USB_ACCESSORY_ATTACHED], which AOSP delivers only as an activity
 *     intent and a receiver could never legitimately receive;
 *  2. an [UsbDetector.ACTION_USB_STATE] broadcast reporting a data function *on a live
 *     data link* fires the expected breach through the real intent-extra reading — the
 *     accessory/MIDI/audio-source/NCM/UVC cases being the gap the host-device list backstop
 *     can never see;
 *  3. the same function extras *without* a data link are silent (AOSP emits MTP/ADB extras
 *     during pure charging and on boot defaults, so the data-link gate is what separates a
 *     real session from charging);
 *  4. a charge-only / disconnected / host-connected-without-device USB_STATE broadcast
 *     stays silent;
 *  5. the host attach broadcast handles a missing device extra without firing.
 *
 * The [UsbDetector.ACTION_USB_STATE] broadcast is protected (only the system can send it),
 * so — like the airplane-mode/bluetooth branches — the deterministic wiring is exercised by
 * invoking [UsbDetector.onReceive] directly rather than by `sendBroadcast`.
 */
@RunWith(AndroidJUnit4::class)
class UsbDetectorInstrumentedTest {

    private class RecordingListener : SignalListener {
        val breaches = mutableListOf<BreachEvent>()
        override fun onBreachDetected(event: BreachEvent) {
            breaches.add(event)
        }
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    // --- Intent filter -----------------------------------------------------

    @Test
    fun filterContainsDeviceAttachAndUsbState_neverAccessoryAttach() {
        val filter = UsbDetector(context, RecordingListener()).getIntentFilter()

        assertTrue("filter must contain ACTION_USB_DEVICE_ATTACHED", filter.hasAction(UsbManager.ACTION_USB_DEVICE_ATTACHED))
        assertTrue("filter must contain ACTION_USB_STATE", filter.hasAction(UsbDetector.ACTION_USB_STATE))
        assertFalse(
            "filter must not contain ACTION_USB_ACCESSORY_ATTACHED (activity intent, never broadcast)",
            filter.hasAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
        )
    }

    // --- Accessory / MIDI / UVC / NCM / audio-source / mass-storage --------

    @Test
    fun usbState_accessoryFunction_onLiveLink_firesDataFunctionBreach() {
        val listener = RecordingListener()
        val detector = UsbDetector(context, listener)

        detector.onReceive(context, usbState(functions = mapOf("accessory" to true)))

        assertEquals(listOf(ViolationType.USB_FUNCTION_NOT_NONE), listener.breaches.map { it.violationType })
        assertEquals(listOf(ResponseTier.ALARM_STREAK), listener.breaches.map { it.tier })
        assertEquals("accessory", listener.breaches.single().rawMetadata["functions"])
    }

    @Test
    fun usbState_midiFunction_onLiveLink_firesDataFunctionBreach() {
        val listener = RecordingListener()
        val detector = UsbDetector(context, listener)

        detector.onReceive(context, usbState(functions = mapOf("midi" to true)))

        assertEquals(listOf(ViolationType.USB_FUNCTION_NOT_NONE), listener.breaches.map { it.violationType })
        assertEquals("midi", listener.breaches.single().rawMetadata["functions"])
    }

    @Test
    fun usbState_audioSourceFunction_onLiveLink_firesDataFunctionBreach() {
        val listener = RecordingListener()
        val detector = UsbDetector(context, listener)

        detector.onReceive(context, usbState(functions = mapOf("audio_source" to true)))

        assertEquals(listOf(ViolationType.USB_FUNCTION_NOT_NONE), listener.breaches.map { it.violationType })
        assertEquals("audio_source", listener.breaches.single().rawMetadata["functions"])
    }

    @Test
    fun usbState_ncmFunction_onLiveLink_firesDataFunctionBreach() {
        val listener = RecordingListener()
        val detector = UsbDetector(context, listener)

        detector.onReceive(context, usbState(functions = mapOf("ncm" to true)))

        assertEquals(listOf(ViolationType.USB_FUNCTION_NOT_NONE), listener.breaches.map { it.violationType })
        assertEquals("ncm", listener.breaches.single().rawMetadata["functions"])
    }

    @Test
    fun usbState_uvcFunction_onLiveLink_firesDataFunctionBreach() {
        val listener = RecordingListener()
        val detector = UsbDetector(context, listener)

        detector.onReceive(context, usbState(functions = mapOf("uvc" to true)))

        assertEquals(listOf(ViolationType.USB_FUNCTION_NOT_NONE), listener.breaches.map { it.violationType })
        assertEquals("uvc", listener.breaches.single().rawMetadata["functions"])
    }

    @Test
    fun usbState_massStorageFunction_onLiveLink_firesDataFunctionBreach() {
        val listener = RecordingListener()
        val detector = UsbDetector(context, listener)

        detector.onReceive(context, usbState(functions = mapOf("mass_storage" to true)))

        assertEquals(listOf(ViolationType.USB_FUNCTION_NOT_NONE), listener.breaches.map { it.violationType })
        assertEquals("mass_storage", listener.breaches.single().rawMetadata["functions"])
    }

    @Test
    fun usbState_combinedAccessoryAndMidi_listBothInMetadata() {
        val listener = RecordingListener()
        val detector = UsbDetector(context, listener)

        detector.onReceive(
            context,
            usbState(functions = mapOf("accessory" to true, "midi" to true))
        )

        assertEquals(listOf(ViolationType.USB_FUNCTION_NOT_NONE), listener.breaches.map { it.violationType })
        assertEquals("accessory,midi", listener.breaches.single().rawMetadata["functions"])
    }

    // --- Existing function branches still work on a live link ---------------

    @Test
    fun usbState_rndisFunction_onLiveLink_firesTetheringBreach() {
        val listener = RecordingListener()
        val detector = UsbDetector(context, listener)

        detector.onReceive(context, usbState(functions = mapOf("rndis" to true)))

        assertEquals(listOf(ViolationType.TETHERING_RNDIS), listener.breaches.map { it.violationType })
        assertEquals(listOf(ResponseTier.ALARM_STREAK), listener.breaches.map { it.tier })
        assertEquals("rndis", listener.breaches.single().rawMetadata["functions"])
    }

    @Test
    fun usbState_mtpFunction_onLiveLink_firesDataFunctionBreach() {
        val listener = RecordingListener()
        val detector = UsbDetector(context, listener)

        detector.onReceive(context, usbState(functions = mapOf("mtp" to true)))

        assertEquals(listOf(ViolationType.USB_FUNCTION_NOT_NONE), listener.breaches.map { it.violationType })
        assertEquals("mtp", listener.breaches.single().rawMetadata["functions"])
    }

    @Test
    fun usbState_ptpFunction_onLiveLink_firesDataFunctionBreach() {
        val listener = RecordingListener()
        val detector = UsbDetector(context, listener)

        detector.onReceive(context, usbState(functions = mapOf("ptp" to true)))

        assertEquals(listOf(ViolationType.USB_FUNCTION_NOT_NONE), listener.breaches.map { it.violationType })
        assertEquals("ptp", listener.breaches.single().rawMetadata["functions"])
    }

    @Test
    fun usbState_adbFunction_onLiveLink_firesDataFunctionBreach() {
        val listener = RecordingListener()
        val detector = UsbDetector(context, listener)

        detector.onReceive(context, usbState(functions = mapOf("adb" to true)))

        assertEquals(listOf(ViolationType.USB_FUNCTION_NOT_NONE), listener.breaches.map { it.violationType })
        assertEquals("adb", listener.breaches.single().rawMetadata["functions"])
    }

    // --- The data-link gate (charging / boot-default protection) -----------

    @Test
    fun usbState_functionExtra_withoutDataLink_isSilent() {
        // AOSP emits MTP/ADB extras during pure charging (getChargingFunctions) and on boot
        // defaults of an unlocked phone. A function extra without the device-mode data link
        // must never fire — this is the charge-only guarantee, tested through the real
        // intent-extra reading for every data function.
        val listener = RecordingListener()
        val detector = UsbDetector(context, listener)

        for (function in ALL_FUNCTIONS) {
            listener.breaches.clear()
            detector.onReceive(
                context,
                usbState(connected = false, configured = false, functions = mapOf(function to true))
            )
            assertTrue(
                "function extra '$function' without a data link must be silent",
                listener.breaches.isEmpty()
            )
        }
    }

    @Test
    fun usbState_chargeOnly_isSilent_whenNoHostDeviceIsEnumerated() {
        val listener = RecordingListener()
        val detector = UsbDetector(context, listener)

        // Cable present, no data function: the only legitimate report here would be a
        // genuine host-mode enumeration (USB_HOST_LINK) if an OTG device happened to be
        // attached; a data-function breach must never fire. On a normal device/emulator
        // with no OTG device, nothing fires at all.
        detector.onReceive(context, usbState(connected = true, configured = true))

        assertTrue(
            "charge-only must never produce a function or tethering breach",
            listener.breaches.none {
                it.violationType == ViolationType.USB_FUNCTION_NOT_NONE ||
                    it.violationType == ViolationType.TETHERING_RNDIS
            }
        )
        if (listener.breaches.isNotEmpty()) {
            assertEquals(listOf(ViolationType.USB_HOST_LINK), listener.breaches.map { it.violationType })
        }
    }

    @Test
    fun usbState_disconnected_isSilent() {
        val listener = RecordingListener()
        val detector = UsbDetector(context, listener)

        detector.onReceive(context, usbState(connected = false, configured = false))

        assertTrue("a disconnected USB_STATE broadcast must not fire", listener.breaches.isEmpty())
    }

    @Test
    fun usbState_noExtrasAtAll_isSilent() {
        val listener = RecordingListener()
        val detector = UsbDetector(context, listener)

        detector.onReceive(context, Intent(UsbDetector.ACTION_USB_STATE))

        assertTrue("an empty USB_STATE broadcast must not fire", listener.breaches.isEmpty())
    }

    @Test
    fun usbState_hostConnectedWithoutDeviceList_isSilent() {
        val listener = RecordingListener()
        val detector = UsbDetector(context, listener)

        detector.onReceive(
            context,
            usbState(connected = false, configured = false, hostConnected = true)
        )

        assertTrue(
            "host_connected without an enumerated device must not fire",
            listener.breaches.isEmpty()
        )
    }

    @Test
    fun usbState_hostConnectedWithFunctionExtra_isSilent() {
        // In host mode the broadcast may still carry the MTP/ADB charging-fallback extras,
        // but there is no device-mode data link: the function report must stay off. On a
        // device with no OTG device enumerated, nothing fires.
        val listener = RecordingListener()
        val detector = UsbDetector(context, listener)

        detector.onReceive(
            context,
            usbState(connected = false, configured = false, hostConnected = true, functions = mapOf("mtp" to true))
        )

        assertTrue(
            "a host-mode MTP artifact must not fire a function breach",
            listener.breaches.none {
                it.violationType == ViolationType.USB_FUNCTION_NOT_NONE ||
                    it.violationType == ViolationType.TETHERING_RNDIS
            }
        )
    }

    // --- Host attach broadcast ---------------------------------------------

    @Test
    fun usbDeviceAttached_withoutDeviceExtra_isSilent() {
        val listener = RecordingListener()
        val detector = UsbDetector(context, listener)

        detector.onReceive(context, Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED))

        assertTrue("a device-attach broadcast without a device must not fire", listener.breaches.isEmpty())
    }

    @Test
    fun unrelatedAction_isSilent() {
        val listener = RecordingListener()
        val detector = UsbDetector(context, listener)

        detector.onReceive(context, Intent("com.example.UNRELATED_EVENT"))

        assertTrue("an unrelated action must not fire", listener.breaches.isEmpty())
    }

    // --- Helpers -----------------------------------------------------------

    private fun usbState(
        connected: Boolean = true,
        configured: Boolean = true,
        hostConnected: Boolean = false,
        functions: Map<String, Boolean> = emptyMap()
    ): Intent = Intent(UsbDetector.ACTION_USB_STATE).apply {
        putExtra(UsbDetector.USB_CONNECTED_EXTRA, connected)
        putExtra(UsbDetector.USB_CONFIGURED_EXTRA, configured)
        putExtra(UsbDetector.USB_HOST_CONNECTED_EXTRA, hostConnected)
        for ((function, enabled) in functions) {
            putExtra(function, enabled)
        }
    }

    private companion object {
        val ALL_FUNCTIONS: List<String> = listOf(
            "rndis", "mtp", "ptp", "adb", "accessory", "midi",
            "audio_source", "ncm", "uvc", "mass_storage"
        )
    }
}