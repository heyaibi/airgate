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
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import com.airgate.dhizuku.DhizukuManager
import com.airgate.dhizuku.DhizukuAvailability

data class ShieldLayerStatus(
    val title: String,
    val subtitle: String,
    val status: String,
    val isOk: Boolean
)

internal data class ShieldWirelessRestrictions(
    val wifiLocked: Boolean?,
    val bluetoothLocked: Boolean,
    val bluetoothSharingLocked: Boolean,
    val nfcBeamLocked: Boolean,
    val tetheringLocked: Boolean,
    val cellularLocked: Boolean
)

internal data class ShieldUsbRestrictions(
    val usbTransferLocked: Boolean,
    val debuggingLocked: Boolean
)

/**
 * Reports the three shield layers shown on the dashboard. Policy restrictions
 * are authoritative for enforcement; unavailable runtime observations fail closed.
 */
@SuppressLint("InlinedApi")
class ShieldStatusChecker(
    private val context: Context,
    private val dhizukuAvailableReader: (() -> Boolean)? = null,
    private val dhizukuAvailabilityReader: (() -> DhizukuAvailability)? = null,
    private val restrictionsReader: () -> android.os.Bundle? = {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        runCatching { userManager?.userRestrictions }.getOrNull()
    },
    private val airplaneModeReader: () -> Boolean? = {
        runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON)
        }.getOrNull()?.let { it != 0 }
    },
    private val bluetoothOnReader: () -> Boolean? = {
        runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
        }.getOrNull()
    },
    private val apiLevelReader: () -> Int = { Build.VERSION.SDK_INT }
) {

    fun check(): List<ShieldLayerStatus> {
        val restrictions = restrictionsReader()

        return listOf(
            checkDhizuku(),
            checkWirelessBlockade(restrictions),
            checkUsbAdbGuard(restrictions)
        )
    }

    private fun checkDhizuku(): ShieldLayerStatus {
        val availability = runCatching {
            dhizukuAvailabilityReader?.invoke() ?: dhizukuAvailableReader?.let {
                if (it()) DhizukuAvailability.AUTHORIZED
                else DhizukuAvailability.UNAVAILABLE
            } ?: DhizukuManager(context).use { it.getDhizukuAvailability() }
        }.getOrDefault(DhizukuAvailability.UNAVAILABLE)
        val granted = availability == DhizukuAvailability.AUTHORIZED
        val subtitle = when (availability) {
            DhizukuAvailability.AUTHORIZED -> "Policy enforcement & safe wipe authority"
            DhizukuAvailability.UNAVAILABLE -> "Device owner authority unavailable"
            DhizukuAvailability.UNTRUSTED_SERVER -> "Dhizuku server identity is not trusted"
        }
        val status = when (availability) {
            DhizukuAvailability.AUTHORIZED -> "Enforced"
            DhizukuAvailability.UNAVAILABLE -> "Unavailable"
            DhizukuAvailability.UNTRUSTED_SERVER -> "Untrusted"
        }
        return ShieldLayerStatus(
            title = "Dhizuku Device Owner",
            subtitle = subtitle,
            status = status,
            isOk = granted
        )
    }

    private fun checkWirelessBlockade(restrictions: android.os.Bundle?): ShieldLayerStatus {
        return resolveWirelessBlockade(
            airplaneOn = airplaneModeReader(),
            bluetoothOn = bluetoothOnReader(),
            restrictions = restrictions?.let { readWirelessRestrictions(it, apiLevelReader()) }
        )
    }

    private fun checkUsbAdbGuard(restrictions: android.os.Bundle?): ShieldLayerStatus =
        resolveUsbAdbGuard(restrictions?.let { readUsbRestrictions(it) })

    internal companion object {
        fun resolveWirelessBlockade(
            airplaneOn: Boolean?,
            bluetoothOn: Boolean?,
            restrictions: ShieldWirelessRestrictions?
        ): ShieldLayerStatus {
            val openChannels = buildList {
                when (airplaneOn) {
                    false -> add("airplane mode off")
                    null -> add("airplane state unavailable")
                    true -> Unit
                }
                when (bluetoothOn) {
                    true -> add("bluetooth on")
                    null -> add("bluetooth state unavailable")
                    false -> Unit
                }
                if (restrictions == null) {
                    add("wireless policy unavailable")
                } else {
                    when (restrictions.wifiLocked) {
                        false -> add("wifi changeable")
                        null -> add("wifi policy unavailable")
                        true -> Unit
                    }
                    if (!restrictions.bluetoothLocked) add("bluetooth policy open")
                    if (!restrictions.bluetoothSharingLocked) add("bluetooth sharing allowed")
                    if (!restrictions.nfcBeamLocked) add("NFC beam allowed")
                    if (!restrictions.tetheringLocked) add("tethering allowed")
                    if (!restrictions.cellularLocked) add("cellular policy open")
                }
            }
            val isOpen = airplaneOn == false || bluetoothOn == true ||
                restrictions?.let {
                    it.wifiLocked == false || !it.bluetoothLocked || !it.bluetoothSharingLocked ||
                        !it.nfcBeamLocked || !it.tetheringLocked || !it.cellularLocked
                } == true
            val hasUnknown = airplaneOn == null || bluetoothOn == null || restrictions == null ||
                restrictions.wifiLocked == null
            return ShieldLayerStatus(
                title = "Wireless Transceiver Blockade",
                subtitle = when {
                    isOpen -> "Open: ${openChannels.joinToString(" · ")}"
                    hasUnknown -> "Unknown: ${openChannels.joinToString(" · ")}"
                    else -> "Policy blocked; airplane mode on and Bluetooth off"
                },
                status = when {
                    isOpen -> "Exposed"
                    hasUnknown -> "Unknown"
                    else -> "Blocked"
                },
                isOk = !isOpen && !hasUnknown
            )
        }

        internal fun readWirelessRestrictions(
            bundle: android.os.Bundle,
            apiLevel: Int
        ): ShieldWirelessRestrictions =
            ShieldWirelessRestrictions(
                wifiLocked = if (apiLevel >= Build.VERSION_CODES.TIRAMISU) {
                    bundle.getBoolean(UserManager.DISALLOW_CHANGE_WIFI_STATE)
                } else {
                    null
                },
                bluetoothLocked = bundle.getBoolean(UserManager.DISALLOW_BLUETOOTH),
                bluetoothSharingLocked = bundle.getBoolean(UserManager.DISALLOW_BLUETOOTH_SHARING),
                nfcBeamLocked = bundle.getBoolean(UserManager.DISALLOW_OUTGOING_BEAM),
                tetheringLocked = bundle.getBoolean(UserManager.DISALLOW_CONFIG_TETHERING),
                cellularLocked = bundle.getBoolean(UserManager.DISALLOW_DATA_ROAMING) &&
                    bundle.getBoolean(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
            )

        internal fun readUsbRestrictions(bundle: android.os.Bundle): ShieldUsbRestrictions =
            ShieldUsbRestrictions(
                usbTransferLocked = bundle.getBoolean(UserManager.DISALLOW_USB_FILE_TRANSFER),
                debuggingLocked = bundle.getBoolean(UserManager.DISALLOW_DEBUGGING_FEATURES)
            )

        fun resolveUsbAdbGuard(restrictions: ShieldUsbRestrictions?): ShieldLayerStatus {
            val openChannels = buildList {
                if (restrictions == null) {
                    add("USB and debugging policy unavailable")
                } else {
                    if (!restrictions.usbTransferLocked) add("USB file transfer allowed")
                    if (!restrictions.debuggingLocked) add("debugging enabled")
                }
            }
            val isUnknown = restrictions == null
            val isOpen = openChannels.isNotEmpty() && !isUnknown
            return ShieldLayerStatus(
                title = "USB & ADB Guard",
                subtitle = when {
                    isOpen -> "Open: ${openChannels.joinToString(" · ")}"
                    isUnknown -> "Unknown: ${openChannels.joinToString(" · ")}"
                    else -> "USB file transfer and debugging policy blocked"
                },
                status = when {
                    isOpen -> "At Risk"
                    isUnknown -> "Unknown"
                    else -> "Secured"
                },
                isOk = !isOpen && !isUnknown
            )
        }
    }
}
