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

package com.airgate.policy

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.usb.UsbManager
import android.os.UserManager
import android.provider.Settings
import com.airgate.dhizuku.DhizukuManager

data class ShieldLayerStatus(
    val title: String,
    val subtitle: String,
    val status: String,
    val isOk: Boolean
)

/**
 * Performs live detection of the three shield layers shown on the dashboard.
 * Labels and statuses are derived from the device's actual state rather than
 * being hardcoded in the UI.
 */
@SuppressLint("InlinedApi")
class ShieldStatusChecker(private val context: Context) {

    fun check(): List<ShieldLayerStatus> {
        val resolver = context.contentResolver
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        val restrictions = userManager.userRestrictions

        return listOf(
            checkDhizuku(),
            checkWirelessBlockade(resolver, restrictions),
            checkUsbAdbGuard(resolver, restrictions)
        )
    }

    private fun checkDhizuku(): ShieldLayerStatus {
        val granted = runCatching { DhizukuManager(context).isDhizukuAvailable() }.getOrDefault(false)
        return ShieldLayerStatus(
            title = "Dhizuku Device Owner",
            subtitle = if (granted) {
                "Policy enforcement & safe wipe authority"
            } else {
                "Device owner authority not granted"
            },
            status = if (granted) "Enforced" else "Not Granted",
            isOk = granted
        )
    }

    private fun checkWirelessBlockade(
        resolver: android.content.ContentResolver,
        restrictions: android.os.Bundle
    ): ShieldLayerStatus {
        val airplaneOn = Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        val bluetoothOff = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true
        }.getOrDefault(true)
        val wifiLocked = restrictions.getBoolean(UserManager.DISALLOW_CHANGE_WIFI_STATE, false)
        val bluetoothLocked = restrictions.getBoolean(UserManager.DISALLOW_BLUETOOTH, false)
        val tetheringLocked = restrictions.getBoolean(UserManager.DISALLOW_CONFIG_TETHERING, false) &&
                restrictions.getBoolean(UserManager.DISALLOW_WIFI_TETHERING, false)
        val cellularLocked = restrictions.getBoolean(UserManager.DISALLOW_DATA_ROAMING, false) &&
                restrictions.getBoolean(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, false)

        val ok = airplaneOn && bluetoothOff && wifiLocked && bluetoothLocked && tetheringLocked && cellularLocked

        val openChannels = buildList {
            if (!airplaneOn) add("airplane mode off")
            if (!bluetoothOff) add("bluetooth on")
            if (!wifiLocked) add("wifi changeable")
            if (!bluetoothLocked) add("bluetooth unlocked")
            if (!tetheringLocked) add("tethering allowed")
            if (!cellularLocked) add("cellular config open")
        }

        return ShieldLayerStatus(
            title = "Wireless Transceiver Blockade",
            subtitle = if (ok) {
                "Wi-Fi, Cellular, FM Radio & Bluetooth interfaces disabled"
            } else {
                "Open: ${openChannels.joinToString(" · ")}"
            },
            status = if (ok) "Blocked" else "Exposed",
            isOk = ok
        )
    }

    private fun checkUsbAdbGuard(
        resolver: android.content.ContentResolver,
        restrictions: android.os.Bundle
    ): ShieldLayerStatus {
        val adbDisabled = Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 0
        val usbTransferLocked = restrictions.getBoolean(UserManager.DISALLOW_USB_FILE_TRANSFER, false)
        val debuggingLocked = restrictions.getBoolean(UserManager.DISALLOW_DEBUGGING_FEATURES, false)
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val noHostDevice = runCatching { usbManager.deviceList.isEmpty() }.getOrDefault(true)

        val ok = adbDisabled && usbTransferLocked && debuggingLocked && noHostDevice

        val openChannels = buildList {
            if (!adbDisabled) add("adb enabled")
            if (!usbTransferLocked) add("usb file transfer allowed")
            if (!debuggingLocked) add("debugging enabled")
            if (!noHostDevice) add("usb device attached")
        }

        return ShieldLayerStatus(
            title = "USB & ADB Guard",
            subtitle = if (ok) {
                "Data host-links and debugging blocked"
            } else {
                "Open: ${openChannels.joinToString(" · ")}"
            },
            status = if (ok) "Secured" else "At Risk",
            isOk = ok
        )
    }
}
