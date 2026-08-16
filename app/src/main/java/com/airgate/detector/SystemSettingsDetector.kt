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
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.provider.Settings
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.domain.model.BreachEvent
import com.airgate.domain.model.ViolationType
import java.util.UUID
import kotlin.math.abs

class SystemSettingsDetector(
    private val context: Context,
    private val listener: SignalListener,
    private val repository: SecurityStateRepository
) : BroadcastReceiver() {

    // Track the last observed state so the 10s poll only fires a breach on a
    // false→true transition instead of spamming a breach (and re-running hardening).
    private var lastAdbEnabled: Boolean? = null
    private var lastDevOptionsEnabled: Boolean? = null
    private var lastUsbDevicesPresent: Boolean? = null

    // Wall clock vs. monotonic clock offset. elapsedRealtime() cannot be adjusted
    // by the user, so a jump in the differential equals the magnitude of a manual
    // clock change; NTP/carrier micro-corrections stay within the tolerance gate.
    private var lastClockOffsetMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                // clockSkewTolerance gate: only raise a breach when the wall clock
                // actually moved by more than the configured tolerance. Automatic
                // NTP/carrier sync and pure timezone changes shift the epoch by ~0
                // (or nothing at all) and must not alarm.
                val toleranceMs =
                    repository.getConfig().clockSkewToleranceMinutes.coerceAtLeast(1) * 60_000L
                val currentOffsetMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
                val skewMs = abs(currentOffsetMs - lastClockOffsetMs)
                lastClockOffsetMs = currentOffsetMs

                if (skewMs > toleranceMs) {
                    listener.onBreachDetected(
                        BreachEvent(
                            id = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            violationType = ViolationType.SYSTEM_CLOCK_CHANGED,
                            tier = ViolationType.SYSTEM_CLOCK_CHANGED.defaultTier,
                            weight = ViolationType.SYSTEM_CLOCK_CHANGED.defaultWeight,
                            rawMetadata = mapOf(
                                "action" to intent.action.orEmpty(),
                                "skewMs" to skewMs.toString(),
                                "toleranceMs" to toleranceMs.toString()
                            )
                        )
                    )
                }
            }
            "android.intent.action.SIM_STATE_CHANGED" -> {
                // An air-gapped device must have NO SIM. Only raise a breach when a SIM
                // is actually present on a slot; ABSENT/NOT_READY fire on boot, airplane
                // mode and radio restarts, which the user cannot control.
                val simState = intent.getStringExtra("ss")
                if (isSimPresentState(simState)) {
                    listener.onBreachDetected(
                        BreachEvent(
                            id = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            violationType = ViolationType.SIM_STATE_CHANGED,
                            tier = ViolationType.SIM_STATE_CHANGED.defaultTier,
                            weight = ViolationType.SIM_STATE_CHANGED.defaultWeight,
                            rawMetadata = mapOf("action" to intent.action.orEmpty(), "sim_state" to simState.orEmpty())
                        )
                    )
                }
            }
        }
    }

    fun getIntentFilter(): IntentFilter {
        return IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction("android.intent.action.SIM_STATE_CHANGED")
        }
    }

    fun checkSettingsState() {
        val config = repository.getConfig()

        val devEnabled = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
        ) != 0

        val adbEnabled = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.ADB_ENABLED, 0
        ) != 0

        // When the owner has turned OFF the debugging block, developer options and
        // ADB are deliberately authorized for recovery/install. No breach is fired
        // for them here — firing would re-trigger reactive hardening, which would
        // force adb_enabled back to 0 and leave the device unreachable. Keep the
        // transition trackers in sync regardless.
        if (config.blockDebuggingFeatures) {
            // Fire only on a false→true transition so sustained ADB/dev-options cannot
            // re-trigger the breach + reactive hardening every 10s.
            if (adbEnabled && lastAdbEnabled != true) {
                listener.onBreachDetected(
                    BreachEvent(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        violationType = ViolationType.ADB_ENABLED_FLIP,
                        tier = ViolationType.ADB_ENABLED_FLIP.defaultTier,
                        weight = ViolationType.ADB_ENABLED_FLIP.defaultWeight,
                        rawMetadata = mapOf("adb_enabled" to "1")
                    )
                )
            } else if (devEnabled && lastDevOptionsEnabled != true && !adbEnabled) {
                listener.onBreachDetected(
                    BreachEvent(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        violationType = ViolationType.DEVELOPER_OPTIONS_TOGGLE,
                        tier = ViolationType.DEVELOPER_OPTIONS_TOGGLE.defaultTier,
                        weight = ViolationType.DEVELOPER_OPTIONS_TOGGLE.defaultWeight,
                        rawMetadata = mapOf("dev_options" to "1")
                    )
                )
            }
        }
        lastAdbEnabled = adbEnabled
        lastDevOptionsEnabled = devEnabled

        // Active poll for USB connections (Host link / OTG / ADB connection).
        // Transition-track the poll: fire only on the false→true attach transition,
        // never on every 10s tick while a device stays attached.
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = try { usbManager.deviceList } catch (e: Exception) { emptyMap() }
        val usbDevicesPresent = deviceList.isNotEmpty()
        if (usbDevicesPresent && lastUsbDevicesPresent != true) {
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
        lastUsbDevicesPresent = usbDevicesPresent
    }
}
