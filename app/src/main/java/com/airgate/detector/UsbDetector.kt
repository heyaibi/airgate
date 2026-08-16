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
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.airgate.domain.model.BreachEvent
import com.airgate.domain.model.ViolationType
import java.util.UUID

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
                        BreachEvent(
                            id = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            violationType = ViolationType.USB_HOST_LINK,
                            tier = ViolationType.USB_HOST_LINK.defaultTier,
                            weight = ViolationType.USB_HOST_LINK.defaultWeight,
                            rawMetadata = mapOf("deviceName" to device.deviceName, "vendorId" to device.vendorId.toString())
                        )
                    )
                }
            }
            UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                val accessory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
                }
                if (accessory != null) {
                    listener.onBreachDetected(
                        BreachEvent(
                            id = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            violationType = ViolationType.USB_HOST_LINK,
                            tier = ViolationType.USB_HOST_LINK.defaultTier,
                            weight = ViolationType.USB_HOST_LINK.defaultWeight,
                            rawMetadata = mapOf("accessory" to accessory.model.orEmpty())
                        )
                    )
                }
            }
            "android.hardware.usb.action.USB_STATE" -> {
                val connected = intent.getBooleanExtra("connected", false)
                val configured = intent.getBooleanExtra("configured", false)
                val mtp = intent.getBooleanExtra("mtp", false)
                val ptp = intent.getBooleanExtra("ptp", false)
                val adb = intent.getBooleanExtra("adb", false)
                val rndis = intent.getBooleanExtra("rndis", false)

                if (rndis) {
                    listener.onBreachDetected(
                        BreachEvent(
                            id = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            violationType = ViolationType.TETHERING_RNDIS,
                            tier = ViolationType.TETHERING_RNDIS.defaultTier,
                            weight = ViolationType.TETHERING_RNDIS.defaultWeight,
                            rawMetadata = mapOf("rndis" to "true")
                        )
                    )
                } else if (mtp || ptp || adb) {
                    listener.onBreachDetected(
                        BreachEvent(
                            id = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            violationType = ViolationType.USB_FUNCTION_NOT_NONE,
                            tier = ViolationType.USB_FUNCTION_NOT_NONE.defaultTier,
                            weight = ViolationType.USB_FUNCTION_NOT_NONE.defaultWeight,
                            rawMetadata = mapOf("mtp" to mtp.toString(), "ptp" to ptp.toString(), "adb" to adb.toString())
                        )
                    )
                } else if (configured || connected) {
                    // Check if it's pure charge-only or actual host enumeration
                    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                    val deviceList = try { usbManager.deviceList } catch (e: Exception) { emptyMap() }
                    if (deviceList.isNotEmpty()) {
                        listener.onBreachDetected(
                            BreachEvent(
                                id = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                violationType = ViolationType.USB_HOST_LINK,
                                tier = ViolationType.USB_HOST_LINK.defaultTier,
                                weight = ViolationType.USB_HOST_LINK.defaultWeight,
                                rawMetadata = mapOf("devices" to deviceList.keys.joinToString(","))
                            )
                        )
                    }
                }
            }
        }
    }

    fun getIntentFilter(): IntentFilter {
        return IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction("android.hardware.usb.action.USB_STATE")
        }
    }
}
