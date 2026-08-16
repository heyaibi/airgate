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

import com.airgate.detector.UsbDetector.Companion.resolveUsbState
import com.airgate.detector.UsbDetector.Companion.usbFunctionBreach
import com.airgate.domain.model.ResponseTier
import com.airgate.domain.model.ScoringGroup
import com.airgate.domain.model.ViolationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-logic tests for [UsbDetector]. The [UsbDetector.resolveUsbState] decision function is
 * free of Android framework calls, so every branch and combination is exercised on the JVM.
 *
 * The central semantic under test: a data-function extra is NOT a breach by itself — AOSP
 * `getChargingFunctions()` emits MTP/ADB extras during pure charging and an unlocked phone
 * advertises MTP at boot with no cable. A real data session always comes with the device-mode
 * data link up (`connected`/`configured`), so function-based breaches are gated on that link.
 * Host-mode enumeration (OTG) is a separate path driven by `host_connected` + the device list.
 */
class UsbDetectorTest {

    // --- Function catalogue -----------------------------------------------

    @Test
    fun `function set covers every AOSP usb state data function plus the legacy tail`() {
        assertEquals(
            "every data function the Android USB stack can broadcast must be enumerated",
            setOf(
                "rndis", "mtp", "ptp", "adb", "accessory", "midi",
                "audio_source", "ncm", "uvc", "mass_storage"
            ),
            UsbFunction.entries.map { it.extraKey }.toSet()
        )
    }

    @Test
    fun `function catalogue is exactly the ten data functions, no stray keys`() {
        assertEquals(10, UsbFunction.entries.size)
    }

    // --- Silent states ----------------------------------------------------

    @Test
    fun `disconnected with no function is silent`() {
        assertEquals(
            UsbStateDecision.None,
            resolveUsbState(
                connected = false, configured = false, hostConnected = false,
                functionEnabled = allFunctionsOff(), hostDeviceListPresent = false
            )
        )
    }

    @Test
    fun `charge-only session is silent`() {
        // Cable present but no data function and no host enumeration: a power-only
        // charger. This is the one connected state that must never alarm.
        assertEquals(
            UsbStateDecision.None,
            resolveUsbState(
                connected = true, configured = true, hostConnected = false,
                functionEnabled = allFunctionsOff(), hostDeviceListPresent = false
            )
        )
    }

    @Test
    fun `connected but not configured with no function is silent`() {
        assertEquals(
            UsbStateDecision.None,
            resolveUsbState(
                connected = true, configured = false, hostConnected = false,
                functionEnabled = allFunctionsOff(), hostDeviceListPresent = false
            )
        )
    }

    @Test
    fun `disconnected with a host device list is silent`() {
        // The host-device backstop is gated on a live host connection: a stale device
        // list while neither device-mode nor host-mode connected is not an enumeration.
        assertEquals(
            UsbStateDecision.None,
            resolveUsbState(
                connected = false, configured = false, hostConnected = false,
                functionEnabled = allFunctionsOff(), hostDeviceListPresent = true
            )
        )
    }

    // --- The data-link gate (charging / boot-default protection) ----------

    @Test
    fun `every function extra without a data link is silent - charging fallback and boot default`() {
        // AOSP getChargingFunctions() substitutes MTP/ADB extras when the function set is
        // NONE (pure charging), and an unlocked phone advertises MTP with no cable at boot.
        // Function extras without the device-mode data link must NEVER report.
        for (connected in BOOLS) {
            for (configured in BOOLS) {
                if (connected || configured) continue // requires NO data link
                for (function in UsbFunction.entries) {
                    val decision = resolveUsbState(
                        connected = connected, configured = configured, hostConnected = false,
                        functionEnabled = functionsEnabled(function),
                        hostDeviceListPresent = false
                    )
                    assertEquals(
                        "function=$function connected=$connected configured=$configured " +
                            "with no data link must be silent",
                        UsbStateDecision.None,
                        decision
                    )
                }
            }
        }
    }

    @Test
    fun `every function extra on a live data link reports`() {
        // A real data session (MTP/PTP/ADB/accessory/MIDI/audio source/NCM/UVC/mass storage)
        // always has the device-mode gadget link up. Each individual function must report.
        for (function in UsbFunction.entries) {
            val decision = resolveUsbState(
                connected = true, configured = true, hostConnected = false,
                functionEnabled = functionsEnabled(function),
                hostDeviceListPresent = false
            )

            assertTrue("$function on a live link must report", decision is UsbStateDecision.Report)
            assertEquals(
                "$function on a live link",
                if (function == UsbFunction.RNDIS) ViolationType.TETHERING_RNDIS else ViolationType.USB_FUNCTION_NOT_NONE,
                (decision as UsbStateDecision.Report).violationType
            )
            assertEquals(listOf(function), decision.activeFunctions)
        }
    }

    @Test
    fun `rndis on a live link reports TETHERING_RNDIS`() {
        val decision = resolveUsbState(
            connected = true, configured = true, hostConnected = false,
            functionEnabled = functionsEnabled(UsbFunction.RNDIS),
            hostDeviceListPresent = false
        )

        assertTrue(decision is UsbStateDecision.Report)
        val report = decision as UsbStateDecision.Report
        assertEquals(ViolationType.TETHERING_RNDIS, report.violationType)
        assertEquals(listOf(UsbFunction.RNDIS), report.activeFunctions)
    }

    @Test
    fun `mtp with connected-only link reports`() {
        val decision = resolveUsbState(
            connected = true, configured = false, hostConnected = false,
            functionEnabled = functionsEnabled(UsbFunction.MTP),
            hostDeviceListPresent = false
        )

        assertTrue(decision is UsbStateDecision.Report)
        assertEquals(ViolationType.USB_FUNCTION_NOT_NONE, (decision as UsbStateDecision.Report).violationType)
    }

    @Test
    fun `mtp with configured-only link reports`() {
        val decision = resolveUsbState(
            connected = false, configured = true, hostConnected = false,
            functionEnabled = functionsEnabled(UsbFunction.MTP),
            hostDeviceListPresent = false
        )

        assertTrue(decision is UsbStateDecision.Report)
        assertEquals(ViolationType.USB_FUNCTION_NOT_NONE, (decision as UsbStateDecision.Report).violationType)
    }

    // --- Host-mode path (host_connected) ----------------------------------

    @Test
    fun `host-connected with a device list reports USB_HOST_LINK`() {
        // The sticky broadcast redelivered on service start with host_connected=true and an
        // already-enumerated OTG device: the "already open when monitoring started" case.
        val decision = resolveUsbState(
            connected = false, configured = false, hostConnected = true,
            functionEnabled = allFunctionsOff(), hostDeviceListPresent = true
        )

        assertTrue(decision is UsbStateDecision.Report)
        assertEquals(ViolationType.USB_HOST_LINK, (decision as UsbStateDecision.Report).violationType)
    }

    @Test
    fun `host-connected without a device list is silent`() {
        assertEquals(
            UsbStateDecision.None,
            resolveUsbState(
                connected = false, configured = false, hostConnected = true,
                functionEnabled = allFunctionsOff(), hostDeviceListPresent = false
            )
        )
    }

    @Test
    fun `host-connected with a data-link and a device list reports USB_HOST_LINK`() {
        val decision = resolveUsbState(
            connected = true, configured = false, hostConnected = true,
            functionEnabled = allFunctionsOff(), hostDeviceListPresent = true
        )

        assertTrue(decision is UsbStateDecision.Report)
        assertEquals(ViolationType.USB_HOST_LINK, (decision as UsbStateDecision.Report).violationType)
    }

    @Test
    fun `host-connected with charging-fallback function extras must NOT report a function breach`() {
        // In host mode AOSP may still emit the MTP/ADB charging-fallback extras, but there
        // is no device-mode data link: those extras are artifacts, not a data session.
        // If a host device is enumerated the USB_HOST_LINK backstop reports; a bare
        // host connection must never be misread as a function breach.
        val decision = resolveUsbState(
            connected = false, configured = false, hostConnected = true,
            functionEnabled = functionsEnabled(UsbFunction.MTP),
            hostDeviceListPresent = false
        )

        assertEquals(
            "host-mode MTP artifact without a device list must be silent",
            UsbStateDecision.None,
            decision
        )
    }

    // --- Combined functions -----------------------------------------------

    @Test
    fun `multiple non-rndis functions report USB_FUNCTION_NOT_NONE listing every active function`() {
        val active = listOf(
            UsbFunction.MTP, UsbFunction.ADB, UsbFunction.ACCESSORY, UsbFunction.MIDI,
            UsbFunction.AUDIO_SOURCE, UsbFunction.NCM, UsbFunction.UVC,
            UsbFunction.MASS_STORAGE, UsbFunction.PTP
        )
        val decision = resolveUsbState(
            connected = true, configured = true, hostConnected = false,
            functionEnabled = functionsEnabled(*active.toTypedArray()),
            hostDeviceListPresent = false
        )

        assertTrue(decision is UsbStateDecision.Report)
        val report = decision as UsbStateDecision.Report
        assertEquals(ViolationType.USB_FUNCTION_NOT_NONE, report.violationType)
        assertEquals(
            "active functions must be listed in catalogue order",
            active.sortedBy { it.ordinal },
            report.activeFunctions
        )
    }

    @Test
    fun `rndis combined with other functions keeps TETHERING_RNDIS priority`() {
        val active = listOf(UsbFunction.RNDIS, UsbFunction.MTP, UsbFunction.MIDI)
        val decision = resolveUsbState(
            connected = true, configured = true, hostConnected = false,
            functionEnabled = functionsEnabled(*active.toTypedArray()),
            hostDeviceListPresent = false
        )

        assertTrue(decision is UsbStateDecision.Report)
        val report = decision as UsbStateDecision.Report
        assertEquals(ViolationType.TETHERING_RNDIS, report.violationType)
        assertEquals(active, report.activeFunctions)
    }

    @Test
    fun `rndis plus accessory and midi together still reports the tethering type`() {
        val decision = resolveUsbState(
            connected = true, configured = true, hostConnected = false,
            functionEnabled = functionsEnabled(
                UsbFunction.RNDIS, UsbFunction.ACCESSORY, UsbFunction.MIDI
            ),
            hostDeviceListPresent = false
        )

        assertTrue(decision is UsbStateDecision.Report)
        assertEquals(ViolationType.TETHERING_RNDIS, (decision as UsbStateDecision.Report).violationType)
    }

    @Test
    fun `accessory and midi combined report USB_FUNCTION_NOT_NONE`() {
        val decision = resolveUsbState(
            connected = true, configured = true, hostConnected = false,
            functionEnabled = functionsEnabled(UsbFunction.ACCESSORY, UsbFunction.MIDI),
            hostDeviceListPresent = false
        )

        assertTrue(decision is UsbStateDecision.Report)
        val report = decision as UsbStateDecision.Report
        assertEquals(ViolationType.USB_FUNCTION_NOT_NONE, report.violationType)
        assertEquals(listOf(UsbFunction.ACCESSORY, UsbFunction.MIDI), report.activeFunctions)
    }

    // --- Unknown / malformed extras ---------------------------------------

    @Test
    fun `non-function extras in the broadcast are ignored`() {
        // The sticky broadcast also carries "none"/"data_unlocked"-style keys and
        // false-valued function extras; neither may ever produce a breach.
        val noise = allFunctionsOff() +
            ("none" to true) +
            ("data_unlocked" to true) +
            ("charging" to true)
        assertEquals(
            UsbStateDecision.None,
            resolveUsbState(
                connected = true, configured = true, hostConnected = false,
                functionEnabled = noise, hostDeviceListPresent = false
            )
        )
    }

    @Test
    fun `a false-valued function extra is not active`() {
        val decision = resolveUsbState(
            connected = true, configured = true, hostConnected = false,
            functionEnabled = mapOf("mtp" to false, "accessory" to false, "midi" to false),
            hostDeviceListPresent = false
        )
        assertEquals(UsbStateDecision.None, decision)
    }

    // --- Breach construction ----------------------------------------------

    @Test
    fun `rndis breach is alarm-streak in the USB group with the active functions metadata`() {
        val breach = usbFunctionBreach(
            UsbStateDecision.Report(ViolationType.TETHERING_RNDIS, listOf(UsbFunction.RNDIS))
        )

        assertEquals(ViolationType.TETHERING_RNDIS, breach.violationType)
        assertEquals(ResponseTier.ALARM_STREAK, breach.tier)
        assertEquals(ScoringGroup.USB, breach.violationType.scoringGroup)
        assertEquals(1, breach.weight)
        assertEquals("rndis", breach.rawMetadata["functions"])
    }

    @Test
    fun `function breach is alarm-streak in the USB group with every active function in metadata`() {
        val active = listOf(UsbFunction.ACCESSORY, UsbFunction.MIDI, UsbFunction.UVC)
        val breach = usbFunctionBreach(
            UsbStateDecision.Report(ViolationType.USB_FUNCTION_NOT_NONE, active)
        )

        assertEquals(ViolationType.USB_FUNCTION_NOT_NONE, breach.violationType)
        assertEquals(ResponseTier.ALARM_STREAK, breach.tier)
        assertEquals(ScoringGroup.USB, breach.violationType.scoringGroup)
        assertEquals(1, breach.weight)
        assertEquals("accessory,midi,uvc", breach.rawMetadata["functions"])
    }

    @Test
    fun `each breach carries a unique id and a timestamp`() {
        val first = usbFunctionBreach(
            UsbStateDecision.Report(ViolationType.USB_FUNCTION_NOT_NONE, listOf(UsbFunction.MIDI))
        )
        val second = usbFunctionBreach(
            UsbStateDecision.Report(ViolationType.USB_FUNCTION_NOT_NONE, listOf(UsbFunction.MIDI))
        )

        assertNotEquals("each breach must carry a unique id", first.id, second.id)
        assertTrue("breaches must carry a timestamp", first.timestamp > 0L && second.timestamp > 0L)
    }

    @Test
    fun `function breach construction rejects a host-link decision`() {
        try {
            usbFunctionBreach(
                UsbStateDecision.Report(ViolationType.USB_HOST_LINK, emptyList())
            )
            fail("a USB_HOST_LINK decision must not be built as a function breach")
        } catch (expected: IllegalArgumentException) {
            // Guard against mis-wiring the two breach builders.
        }
    }

    // --- Exhaustive decision table ----------------------------------------

    @Test
    fun `exhaustive table - every connection, host and function combination`() {
        // Pins the whole decision surface: every connected/configured/host/device-list
        // combination with every single function enabled (and with none enabled).
        val allFunctionMaps = listOf(allFunctionsOff()) + UsbFunction.entries.map { functionsEnabled(it) }
        for (connected in BOOLS) {
            for (configured in BOOLS) {
                for (hostConnected in BOOLS) {
                    for (hostDevice in BOOLS) {
                        for (functions in allFunctionMaps) {
                            val decision = resolveUsbState(connected, configured, hostConnected, functions, hostDevice)
                            val active = UsbFunction.entries.filter { functions[it.extraKey] == true }
                            val dataLink = connected || configured
                            val message =
                                "connected=$connected configured=$configured host=$hostConnected " +
                                    "hostDevice=$hostDevice active=$active"

                            val expected: UsbStateDecision = when {
                                dataLink && active.isNotEmpty() -> UsbStateDecision.Report(
                                    violationType = if (UsbFunction.RNDIS in active) {
                                        ViolationType.TETHERING_RNDIS
                                    } else {
                                        ViolationType.USB_FUNCTION_NOT_NONE
                                    },
                                    activeFunctions = active
                                )
                                (dataLink || hostConnected) && hostDevice ->
                                    UsbStateDecision.Report(ViolationType.USB_HOST_LINK, emptyList())
                                else -> UsbStateDecision.None
                            }

                            assertEquals(message, expected, decision)
                        }
                    }
                }
            }
        }
    }

    // --- Helpers ----------------------------------------------------------

    private fun allFunctionsOff(): Map<String, Boolean> =
        UsbFunction.entries.associate { it.extraKey to false }

    private fun functionsEnabled(vararg functions: UsbFunction): Map<String, Boolean> {
        val enabled = functions.map { it.extraKey }.toSet()
        return UsbFunction.entries.associate { it.extraKey to (it.extraKey in enabled) }
    }

    private companion object {
        val BOOLS: List<Boolean> = listOf(false, true)
    }
}