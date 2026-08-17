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
import android.os.SystemClock
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
        // Debugging policy is verified by PostureAudit, and USB data sessions are
        // owned by UsbDetector. Neither subsystem can safely infer state from the
        // redacted settings or host-only device list exposed to third-party apps.
    }
}
