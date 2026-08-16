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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.airgate.domain.model.BreachEvent
import com.airgate.domain.model.ViolationType
import java.util.UUID

/**
 * Every USB gadget function the Android USB stack can activate while in device mode
 * (device/peripheral), as carried by boolean extras on [UsbDetector.ACTION_USB_STATE].
 * Verified against AOSP `UsbManager.java` / `UsbDeviceManager.updateUsbStateBroadcastIfNeeded()`,
 * which emits one `putExtra(function, true)` per active function via `usbFunctionsToString()`.
 * Each of these is a real data channel a connected host can use to pull data off the phone —
 * including accessory and MIDI, which the host-device list backstop can never see (deviceList
 * is host-mode only). UVC (video) is current AOSP; mass_storage is legacy-tail coverage for the
 * oldest supported API levels and can never appear on modern releases.
 */
enum class UsbFunction(val extraKey: String) {
    RNDIS("rndis"),
    MTP("mtp"),
    PTP("ptp"),
    ADB("adb"),
    ACCESSORY("accessory"),
    MIDI("midi"),
    AUDIO_SOURCE("audio_source"),
    NCM("ncm"),
    UVC("uvc"),
    MASS_STORAGE("mass_storage")
}

/**
 * Outcome of one [UsbDetector.ACTION_USB_STATE] observation: silence, or a breach to emit
 * (with the active functions for metadata). Host-link breaches still need the real device
 * names, so the receiver builds their metadata from the device list; the function breaches
 * are fully built by [UsbDetector.usbFunctionBreach].
 */
sealed class UsbStateDecision {
    data object None : UsbStateDecision()
    data class Report(
        val violationType: ViolationType,
        val activeFunctions: List<UsbFunction>
    ) : UsbStateDecision()
}

/**
 * Detects USB data sessions. Two broadcast families feed it:
 *
 *  1. [UsbManager.ACTION_USB_DEVICE_ATTACHED] fires when a USB device enumerates while the
 *     phone is the USB *host* (OTG) — reported as [ViolationType.USB_HOST_LINK].
 *  2. [ACTION_USB_STATE] is the system's sticky device-mode state broadcast. It carries a
 *     boolean extra for *every* currently active USB function, so it is the authoritative
 *     signal for accessory/MIDI gadget sessions (where [UsbManager.deviceList] is empty
 *     because the phone is the peripheral, not the host), and for host-mode enumeration that
 *     was already present when monitoring started (via `host_connected` + the device list).
 *
 * [UsbManager.ACTION_USB_ACCESSORY_ATTACHED] is deliberately not registered: AOSP delivers it
 * only as an *activity* intent ([UsbProfileGroupSettingsManager.accessoryAttached] →
 * `startActivityAsUser`), never as a broadcast, so a receiver could never legitimately fire
 * on it. Accessory sessions are instead caught by the `accessory` function extra on the
 * sticky [ACTION_USB_STATE] broadcast.
 *
 * The decision logic is a pure, framework-free function ([resolveUsbState]) so every branch
 * is exercisable on the JVM; the receiver only performs the framework reads (intent extras,
 * [UsbManager.deviceList]) around it.
 */
class UsbDetector(
    private val context: Context,
    private val listener: SignalListener
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (device != null) {
                    listener.onBreachDetected(
                        hostLinkBreach(
                            violationType = ViolationType.USB_HOST_LINK,
                            rawMetadata = mapOf(
                                "deviceName" to device.deviceName,
                                "vendorId" to device.vendorId.toString()
                            )
                        )
                    )
                }
            }
            ACTION_USB_STATE -> onUsbState(intent)
        }
    }

    private fun onUsbState(intent: Intent) {
        val connected = intent.getBooleanExtra(USB_CONNECTED_EXTRA, false)
        val configured = intent.getBooleanExtra(USB_CONFIGURED_EXTRA, false)
        val hostConnected = intent.getBooleanExtra(USB_HOST_CONNECTED_EXTRA, false)
        val functionEnabled = UsbFunction.entries.associate { it.extraKey to intent.getBooleanExtra(it.extraKey, false) }

        // The host-device list is the backstop for host-mode enumeration (the
        // DEVICE_ATTACHED broadcast is the primary host path). Read before deciding so the
        // metadata is complete; on device-only sessions the list is empty, which the
        // decision treats as no host enumeration.
        val deviceList = readDeviceList()

        when (val decision = resolveUsbState(connected, configured, hostConnected, functionEnabled, deviceList.isNotEmpty())) {
            is UsbStateDecision.Report -> {
                val breach = when (decision.violationType) {
                    ViolationType.USB_HOST_LINK -> hostLinkBreach(
                        violationType = decision.violationType,
                        rawMetadata = mapOf("devices" to deviceList.keys.joinToString(","))
                    )
                    else -> usbFunctionBreach(decision)
                }
                listener.onBreachDetected(breach)
            }
            UsbStateDecision.None -> Unit
        }
    }

    private fun readDeviceList(): Map<String, UsbDevice> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return try {
            usbManager.deviceList
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun getIntentFilter(): IntentFilter {
        return IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(ACTION_USB_STATE)
        }
    }

    companion object {
        /** The system's sticky device-mode USB state broadcast action. */
        const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"

        /** Sticky-broadcast extras for connection state (not @hide-visible in the public SDK). */
        const val USB_CONNECTED_EXTRA = "connected"
        const val USB_CONFIGURED_EXTRA = "configured"
        const val USB_HOST_CONNECTED_EXTRA = "host_connected"

        /**
         * Pure decision logic for one [ACTION_USB_STATE] broadcast. Free of Android
         * framework calls so every branch is unit-testable.
         *
         * A data function is only reported when the device-mode data link is actually
         * present ([connected] or [configured]). This gate is essential: AOSP
         * `UsbDeviceManager.getChargingFunctions()` substitutes MTP (or ADB) as the function
         * extras whenever the current function set is NONE, and an unlocked phone with the
         * default USB mode advertises MTP even with no cable — so function extras alone do
         * NOT mean a data session. A real session (MTP/PTP/ADB/accessory/MIDI/audio
         * source/NCM/UVC) always comes with the gadget data link up. Without the gate,
         * pure charging and every boot of an unlocked phone would false-positive.
         *
         * Priority: with the link up, any active data function reports — RNDIS keeps its
         * dedicated [ViolationType.TETHERING_RNDIS], everything else reports as
         * [ViolationType.USB_FUNCTION_NOT_NONE].
         *
         * With no data function on a live link, a present host-mode connection
         * ([hostConnected], e.g. OTG) with a non-empty device list ([hostDeviceListPresent])
         * reports [ViolationType.USB_HOST_LINK]. This covers host enumeration that was
         * already present when monitoring started — the sticky broadcast redelivers it with
         * `host_connected` set, while `connected`/`configured` (device-mode extras) are
         * false. A charge-only session (no data link, no host device) stays silent, as does
         * a disconnected state.
         */
        internal fun resolveUsbState(
            connected: Boolean,
            configured: Boolean,
            hostConnected: Boolean,
            functionEnabled: Map<String, Boolean>,
            hostDeviceListPresent: Boolean
        ): UsbStateDecision {
            val dataLink = connected || configured
            val active = UsbFunction.entries.filter { functionEnabled[it.extraKey] == true }
            return when {
                dataLink && active.isNotEmpty() -> UsbStateDecision.Report(
                    violationType = if (UsbFunction.RNDIS in active) {
                        ViolationType.TETHERING_RNDIS
                    } else {
                        ViolationType.USB_FUNCTION_NOT_NONE
                    },
                    activeFunctions = active
                )
                (dataLink || hostConnected) && hostDeviceListPresent ->
                    UsbStateDecision.Report(ViolationType.USB_HOST_LINK, emptyList())
                else -> UsbStateDecision.None
            }
        }

        /**
         * Builds the function-sourced breach ([ViolationType.TETHERING_RNDIS] or
         * [ViolationType.USB_FUNCTION_NOT_NONE]) so its shape is testable on the JVM.
         */
        internal fun usbFunctionBreach(decision: UsbStateDecision.Report): BreachEvent {
            require(decision.violationType == ViolationType.TETHERING_RNDIS ||
                decision.violationType == ViolationType.USB_FUNCTION_NOT_NONE
            )
            return BreachEvent(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                violationType = decision.violationType,
                tier = decision.violationType.defaultTier,
                weight = decision.violationType.defaultWeight,
                rawMetadata = mapOf(
                    "functions" to decision.activeFunctions.joinToString(",") { it.extraKey }
                )
            )
        }

        private fun hostLinkBreach(
            violationType: ViolationType,
            rawMetadata: Map<String, String>
        ): BreachEvent = BreachEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            violationType = violationType,
            tier = violationType.defaultTier,
            weight = violationType.defaultWeight,
            rawMetadata = rawMetadata
        )
    }
}