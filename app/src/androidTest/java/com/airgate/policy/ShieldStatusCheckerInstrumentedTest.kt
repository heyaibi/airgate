/*
 * Copyright (C) 2026 The Airgate project contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 */

package com.airgate.policy

import android.os.Bundle
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShieldStatusCheckerInstrumentedTest {

    @Test
    fun platformRestrictionKeys_produceSecurePolicyStatuses() {
        val restrictions = ShieldWirelessRestrictions(
            wifiLocked = true,
            bluetoothLocked = true,
            bluetoothSharingLocked = true,
            nfcBeamLocked = true,
            tetheringLocked = true,
            cellularLocked = true
        )
        val wireless = ShieldStatusChecker.resolveWirelessBlockade(true, false, restrictions)
        val usb = ShieldStatusChecker.resolveUsbAdbGuard(
            ShieldUsbRestrictions(true, true)
        )

        assertEquals("Blocked", wireless.status)
        assertEquals("Secured", usb.status)
        assertTrue(wireless.isOk)
        assertTrue(usb.isOk)
    }

    @Test
    fun unavailableRuntimeObservation_isUnknownAndNotGreen() {
        val result = ShieldStatusChecker.resolveWirelessBlockade(
            airplaneOn = true,
            bluetoothOn = null,
            restrictions = ShieldWirelessRestrictions(true, true, true, true, true, true)
        )

        assertEquals("Unknown", result.status)
        assertFalse(result.isOk)
        assertTrue(result.subtitle.contains("bluetooth state unavailable"))
    }

    @Test
    fun openPolicy_isExposedAndNotGreen() {
        val result = ShieldStatusChecker.resolveUsbAdbGuard(
            ShieldUsbRestrictions(
                usbTransferLocked = false,
                debuggingLocked = true
            )
        )

        assertEquals("At Risk", result.status)
        assertFalse(result.isOk)
        assertTrue(result.subtitle.contains("USB file transfer allowed"))
    }

    @Test
    fun platformRestrictionBundle_isMappedToTheExpectedPolicyChecks() {
        val bundle = Bundle().apply {
            putBoolean(UserManager.DISALLOW_CHANGE_WIFI_STATE, true)
            putBoolean(UserManager.DISALLOW_BLUETOOTH, true)
            putBoolean(UserManager.DISALLOW_BLUETOOTH_SHARING, true)
            putBoolean(UserManager.DISALLOW_OUTGOING_BEAM, true)
            putBoolean(UserManager.DISALLOW_CONFIG_TETHERING, true)
            putBoolean(UserManager.DISALLOW_WIFI_TETHERING, true)
            putBoolean(UserManager.DISALLOW_DATA_ROAMING, true)
            putBoolean(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, true)
            putBoolean(UserManager.DISALLOW_USB_FILE_TRANSFER, true)
            putBoolean(UserManager.DISALLOW_DEBUGGING_FEATURES, true)
        }

        val wireless = ShieldStatusChecker.readWirelessRestrictions(bundle, android.os.Build.VERSION.SDK_INT)
        val usb = ShieldStatusChecker.readUsbRestrictions(bundle)

        assertTrue(wireless.wifiLocked == true)
        assertTrue(wireless.bluetoothLocked)
        assertTrue(wireless.bluetoothSharingLocked)
        assertTrue(wireless.nfcBeamLocked)
        assertTrue(wireless.tetheringLocked)
        assertTrue(wireless.cellularLocked)
        assertTrue(usb.usbTransferLocked)
        assertTrue(usb.debuggingLocked)
    }

    @Test
    fun legacyApi_doesNotRequireUnavailableWifiRestriction() {
        val bundle = Bundle().apply {
            putBoolean(UserManager.DISALLOW_BLUETOOTH, true)
            putBoolean(UserManager.DISALLOW_BLUETOOTH_SHARING, true)
            putBoolean(UserManager.DISALLOW_OUTGOING_BEAM, true)
            putBoolean(UserManager.DISALLOW_CONFIG_TETHERING, true)
            putBoolean(UserManager.DISALLOW_DATA_ROAMING, true)
            putBoolean(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, true)
        }

        val restrictions = ShieldStatusChecker.readWirelessRestrictions(bundle, 32)
        val result = ShieldStatusChecker.resolveWirelessBlockade(true, false, restrictions)

        assertEquals("Unknown", result.status)
        assertFalse(result.isOk)
        assertTrue(result.subtitle.contains("wifi policy unavailable"))
    }

    @Test
    fun broadTetheringRestriction_isSufficientWithoutNewerWifiTetheringKey() {
        val bundle = Bundle().apply {
            putBoolean(UserManager.DISALLOW_CHANGE_WIFI_STATE, true)
            putBoolean(UserManager.DISALLOW_BLUETOOTH, true)
            putBoolean(UserManager.DISALLOW_BLUETOOTH_SHARING, true)
            putBoolean(UserManager.DISALLOW_OUTGOING_BEAM, true)
            putBoolean(UserManager.DISALLOW_CONFIG_TETHERING, true)
            putBoolean(UserManager.DISALLOW_DATA_ROAMING, true)
            putBoolean(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, true)
        }

        val restrictions = ShieldStatusChecker.readWirelessRestrictions(bundle, 33)
        val result = ShieldStatusChecker.resolveWirelessBlockade(true, false, restrictions)

        assertEquals("Blocked", result.status)
        assertTrue(result.isOk)
    }
}
