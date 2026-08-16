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

package com.airgate.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.airgate.domain.model.BreachEvent
import com.airgate.ui.alarm.AlarmActivity

/**
 * Surfaces security alarms to the owner: the high-priority full-screen notification
 * and the direct [AlarmActivity] launches (both the live-alarm screen and the
 * wipe countdown screen).
 *
 * The notification path is gated on the app being allowed to post notifications:
 * Android 13+ defaults to denied, and a full-screen intent never appears when
 * notifications are blocked. The fallback direct activity launch is a background
 * activity start, which Android 10+ silently suppresses — it must never be the only
 * surface relied on. Both conditions are recorded honestly here (instead of
 * swallowed) so a silent alarm is a diagnosable event; the app-level guarantee that
 * the owner can still see the alarm lives in the persistent pending-alarm state
 * raised by [ThreatEngine].
 *
 * The notification-posting and activity-launch side effects are injectable so the
 * flow decisions and failure logging are unit-testable in a pure JVM (the real
 * platform posting/launching behavior is covered by the instrumented suite).
 */
open class AlarmNotifier(
    private val context: Context,
    private val notificationsAllowed: () -> Boolean = {
        // Fail closed: if the notification state cannot be determined the alarm
        // must not assume the notification path works (Android 13+ defaults to
        // denied). The direct activity launch is still attempted regardless.
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.areNotificationsEnabled() ?: false
        }.getOrDefault(false)
    },
    private val postNotification: (code: Int, category: String, description: String, isCountdown: Boolean) -> Unit =
        { code, category, description, isCountdown ->
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Airgate Critical Security Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)

            val fullScreenIntent = Intent(context, AlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (category.isNotEmpty()) {
                    putExtra("breach_category", category)
                }
                if (description.isNotEmpty()) {
                    putExtra("breach_description", description)
                }
                if (isCountdown) {
                    putExtra("is_countdown", true)
                }
            }
            val pendingIntent = PendingIntent.getActivity(
                context, code, fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = if (isCountdown) COUNTDOWN_TITLE else "AIRGAP BREACH: $category"
            val text = if (isCountdown) COUNTDOWN_DESCRIPTION else description
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pendingIntent, true)
                .setAutoCancel(true)

            manager.notify(code, builder.build())
        },
    private val launchActivity: (category: String, description: String, isCountdown: Boolean) -> Unit =
        { category, description, isCountdown ->
            val intent = Intent(context, AlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (category.isNotEmpty()) {
                    putExtra("breach_category", category)
                }
                if (description.isNotEmpty()) {
                    putExtra("breach_description", description)
                }
                if (isCountdown) {
                    putExtra("is_countdown", true)
                }
            }
            context.startActivity(intent)
        },
    private val logWarning: (String) -> Unit = { message -> safeLog { Log.w(TAG, message) } },
    private val logError: (String, Throwable) -> Unit = { message, throwable -> safeLog { Log.e(TAG, message, throwable) } }
) {
    open fun launch(event: BreachEvent) {
        val category = event.violationType.scoringGroup.displayName
        val description = event.violationType.description
        // Derive requestCode / notification id from the breach so distinct events
        // never overwrite each other's notifications.
        val code = event.id.hashCode() and 0x7fffffff

        // 1. High Priority Notification with FullScreenIntent (Android 10+ background
        // activity start requirement). POST_NOTIFICATIONS is default-off on 13+;
        // never rely on the FSI path when notifications are disabled for this app.
        postAlarmNotification(code, category, description, isCountdown = false)

        // 2. Direct activity launch attempt. On Android 10+ this is a background
        // activity start and is silently suppressed when the app has no visible
        // window — record that honestly rather than pretending it surfaced.
        launchAlarmActivity(category, description, isCountdown = false)
    }

    open fun launchCountdown() {
        // The countdown is the owner's last window to cancel the wipe, so it gets
        // its own full-screen-intent notification (with a dedicated id) — never rely
        // on the BAL-prone direct launch alone to surface the countdown.
        postAlarmNotification(COUNTDOWN_NOTIFICATION_ID, "", "", isCountdown = true)
        launchAlarmActivity("", "", isCountdown = true)
    }

    /**
     * Surfaces a wipe that the platform refused: the device was never erased, so
     * the owner must see the failure instead of believing the wipe succeeded.
     */
    open fun launchWipeFailure() {
        launchAlarmActivity(
            WIPE_FAILED_CATEGORY,
            "The wipe was rejected by the system; device data has not been erased.",
            isCountdown = false
        )
    }

    private fun postAlarmNotification(code: Int, category: String, description: String, isCountdown: Boolean) {
        if (notificationsAllowed()) {
            runCatching { postNotification(code, category, description, isCountdown) }
                .onFailure { logError("Failed to post alarm notification", it) }
        } else {
            logWarning("Alarm notification not posted: notifications are disabled for this app")
        }
    }

    private fun launchAlarmActivity(category: String, description: String, isCountdown: Boolean) {
        runCatching { launchActivity(category, description, isCountdown) }
            .onFailure { logError("Failed to launch alarm activity from the background", it) }
    }

    companion object {
        private const val TAG = "AlarmNotifier"
        const val CHANNEL_ID = "airgate_alarm_channel"
        const val WIPE_FAILED_CATEGORY = "WIPE FAILED"
        const val COUNTDOWN_TITLE = "AIRGAP WIPE IMMINENT"
        const val COUNTDOWN_DESCRIPTION = "Disarm with your Armed PIN to cancel the pending wipe."
        // Dedicated, fixed id for the single active wipe countdown notification. It is
        // deliberately outside the breach-derived id space in practice; a collision with
        // a breach id would only ever replace one alarm surface with the other.
        const val COUNTDOWN_NOTIFICATION_ID = 0x5EED

        /**
         * Logging is best-effort diagnostics: android.util.Log is unmocked in pure
         * JVM tests and throws there, and logging must never be the reason an alarm
         * path fails on a device. Any logging failure is swallowed, never the alarm.
         */
        private inline fun safeLog(block: () -> Unit) {
            try {
                block()
            } catch (_: RuntimeException) {
                // Unmocked Log in JVM tests — logging is best-effort.
            }
        }
    }
}
