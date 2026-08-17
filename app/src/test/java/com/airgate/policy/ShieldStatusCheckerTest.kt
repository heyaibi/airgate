/*
 * Copyright (C) 2026 The Airgate project contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 */

package com.airgate.policy

import android.content.ContextWrapper
import com.airgate.dhizuku.DhizukuAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShieldStatusCheckerTest {

    private val values = listOf(false, true, null)

    @Test
    fun `wireless decision covers every readable and unreadable input`() {
        for (airplaneOn in values) {
            for (bluetoothOn in values) {
                for (wifiLocked in listOf(false, true)) {
                    for (bluetoothLocked in listOf(false, true)) {
                        for (bluetoothSharingLocked in listOf(false, true)) {
                            for (nfcBeamLocked in listOf(false, true)) {
                                for (tetheringLocked in listOf(false, true)) {
                                    for (cellularLocked in listOf(false, true)) {
                                        val restrictions = ShieldWirelessRestrictions(
                                            wifiLocked,
                                            bluetoothLocked,
                                            bluetoothSharingLocked,
                                            nfcBeamLocked,
                                            tetheringLocked,
                                            cellularLocked
                                        )
                                        val result = ShieldStatusChecker.resolveWirelessBlockade(
                                            airplaneOn,
                                            bluetoothOn,
                                            restrictions
                                        )
                                        val hasOpenState = airplaneOn == false || bluetoothOn == true ||
                                            !wifiLocked || !bluetoothLocked || !bluetoothSharingLocked ||
                                            !nfcBeamLocked || !tetheringLocked || !cellularLocked
                                        val hasUnknownState = airplaneOn == null || bluetoothOn == null
                                        assertEquals(
                                            when {
                                                hasOpenState -> "Exposed"
                                                hasUnknownState -> "Unknown"
                                                else -> "Blocked"
                                            },
                                            result.status
                                        )
                                        assertEquals(
                                            !hasOpenState && !hasUnknownState,
                                            result.isOk
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `wireless unavailable policy is unknown and never green`() {
        val result = ShieldStatusChecker.resolveWirelessBlockade(
            airplaneOn = true,
            bluetoothOn = false,
            restrictions = null
        )

        assertEquals("Unknown", result.status)
        assertFalse(result.isOk)
        assertTrue(result.subtitle.contains("policy unavailable"))
    }

    @Test
    fun `unsupported wifi policy is unknown and never green`() {
        val result = ShieldStatusChecker.resolveWirelessBlockade(
            airplaneOn = true,
            bluetoothOn = false,
            restrictions = ShieldWirelessRestrictions(null, true, true, true, true, true)
        )

        assertEquals("Unknown", result.status)
        assertFalse(result.isOk)
        assertTrue(result.subtitle.contains("wifi policy unavailable"))
    }

    @Test
    fun `production check preserves layer order and fails closed on unavailable policy`() {
        val checker = ShieldStatusChecker(
            context = ContextWrapper(null),
            dhizukuAvailableReader = { true },
            restrictionsReader = { null },
            airplaneModeReader = { true },
            bluetoothOnReader = { false }
        )

        val result = checker.check()

        assertEquals(3, result.size)
        assertEquals("Dhizuku Device Owner", result[0].title)
        assertEquals("Wireless Transceiver Blockade", result[1].title)
        assertEquals("USB & ADB Guard", result[2].title)
        assertEquals("Enforced", result[0].status)
        assertEquals("Unknown", result[1].status)
        assertEquals("Unknown", result[2].status)
        assertFalse(result[1].isOk)
        assertFalse(result[2].isOk)
    }

    @Test
    fun `production check converts dhizuku reader failure to not granted`() {
        val checker = ShieldStatusChecker(
            context = ContextWrapper(null),
            dhizukuAvailableReader = { error("unavailable") },
            restrictionsReader = { null },
            airplaneModeReader = { null },
            bluetoothOnReader = { null }
        )

        val result = checker.check().first()

        assertEquals("Unavailable", result.status)
        assertFalse(result.isOk)
    }

    @Test
    fun `production check distinguishes an untrusted server from unavailable authority`() {
        val untrusted = ShieldStatusChecker(
            context = ContextWrapper(null),
            dhizukuAvailabilityReader = { DhizukuAvailability.UNTRUSTED_SERVER },
            restrictionsReader = { null },
            airplaneModeReader = { null },
            bluetoothOnReader = { null }
        ).check().first()
        val unavailable = ShieldStatusChecker(
            context = ContextWrapper(null),
            dhizukuAvailabilityReader = { DhizukuAvailability.UNAVAILABLE },
            restrictionsReader = { null },
            airplaneModeReader = { null },
            bluetoothOnReader = { null }
        ).check().first()

        assertEquals("Untrusted", untrusted.status)
        assertTrue(untrusted.subtitle.contains("not trusted"))
        assertFalse(untrusted.isOk)
        assertEquals("Unavailable", unavailable.status)
        assertTrue(unavailable.subtitle.contains("unavailable"))
        assertFalse(unavailable.isOk)
    }

    @Test
    fun `usb decision covers all restriction combinations`() {
        for (usbTransferLocked in listOf(false, true)) {
            for (debuggingLocked in listOf(false, true)) {
                val result = ShieldStatusChecker.resolveUsbAdbGuard(
                    ShieldUsbRestrictions(usbTransferLocked, debuggingLocked)
                )
                val expectedOk = usbTransferLocked && debuggingLocked
                assertEquals(if (expectedOk) "Secured" else "At Risk", result.status)
                assertEquals(expectedOk, result.isOk)
            }
        }
    }

    @Test
    fun `usb unavailable policy is unknown and never green`() {
        val result = ShieldStatusChecker.resolveUsbAdbGuard(null)

        assertEquals("Unknown", result.status)
        assertFalse(result.isOk)
        assertTrue(result.subtitle.contains("policy unavailable"))
    }

}
