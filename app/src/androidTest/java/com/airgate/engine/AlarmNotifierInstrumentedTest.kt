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

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airgate.domain.model.BreachEvent
import com.airgate.domain.model.ResponseTier
import com.airgate.domain.model.ViolationType
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.UUID

/**
 * On-device verification of the alarm-notification surface. The full-screen
 * notification is the primary way a wipe countdown reaches the owner, and it only
 * exists when the app is allowed to post notifications.
 *
 * With POST_NOTIFICATIONS granted (via `pm grant`, which does not disturb a running
 * process) the alarm notification with its full-screen intent must actually be
 * posted. The "not allowed" branch is exercised on-device by injecting the decision
 * (`notificationsAllowed = { false }`) rather than revoking the permission: revoking
 * POST_NOTIFICATIONS mid-instrumentation force-stops the app process that is running
 * the test. The decision logic behind both branches is covered exhaustively in the
 * JVM suite.
 */
@RunWith(AndroidJUnit4::class)
class AlarmNotifierInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun grantNotificationsAndClear() {
        setNotificationsPermission(granted = true)
        notificationManager.cancelAll()
    }

    @After
    fun clearNotifications() {
        notificationManager.cancelAll()
    }

    @Test
    fun alarmNotification_isPostedWhenNotificationsAreGranted() {
        val event = event()
        val notifier = AlarmNotifier(
            context,
            notificationsAllowed = {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                    ?.areNotificationsEnabled() ?: false
            },
            launchActivity = { _, _, _ -> }
        )

        notifier.launch(event)

        val code = event.id.hashCode() and 0x7fffffff
        val posted = awaitNotification(code)
        assertTrue(
            "an alarm notification with id $code must be posted when notifications are granted",
            posted != null
        )
        assertTrue(
            "the alarm notification must carry its full-screen intent",
            posted?.fullScreenIntent != null
        )
        val extras = posted?.extras
        assertTrue(
            "the alarm notification must carry the breach category",
            extras?.getString(Notification.EXTRA_TITLE)?.contains(event.violationType.scoringGroup.displayName) == true
        )
        assertTrue(
            "the alarm notification must carry the breach description",
            extras?.getString(Notification.EXTRA_TEXT)?.contains(event.violationType.description) == true
        )
    }

    @Test
    fun alarmNotification_isNotPosted_whenNotificationPathDecidesDisabled() {
        // Mirrors a revoked POST_NOTIFICATIONS: the notifier decides notifications
        // are not allowed, so nothing may be posted against the real notification
        // system even though notifications are currently grantable.
        val event = event()
        val notifier = AlarmNotifier(
            context,
            notificationsAllowed = { false },
            launchActivity = { _, _, _ -> }
        )

        notifier.launch(event)

        val code = event.id.hashCode() and 0x7fffffff
        assertFalse(
            "no alarm notification may appear when the notification path is decided disabled",
            notificationManager.activeNotifications.any { it.id == code }
        )
    }

    @Test
    fun countdownNotification_isPostedWhenNotificationsAreGranted() {
        val notifier = AlarmNotifier(
            context,
            notificationsAllowed = {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                    ?.areNotificationsEnabled() ?: false
            },
            launchActivity = { _, _, _ -> }
        )

        notifier.launchCountdown()

        val posted = awaitNotification(AlarmNotifier.COUNTDOWN_NOTIFICATION_ID)
        assertTrue(
            "a countdown notification with id ${AlarmNotifier.COUNTDOWN_NOTIFICATION_ID} must be posted when notifications are granted",
            posted != null
        )
        assertTrue(
            "the countdown notification must carry its full-screen intent",
            posted?.fullScreenIntent != null
        )
        val extras = posted?.extras
        assertTrue(
            "the countdown notification must carry the wipe-imminent title",
            extras?.getString(Notification.EXTRA_TITLE)?.contains("WIPE IMMINENT") == true
        )
        assertTrue(
            "the countdown notification must instruct the owner to disarm",
            extras?.getString(Notification.EXTRA_TEXT)?.contains("Armed PIN") == true
        )
    }

    @Test
    fun countdownNotification_isNotPosted_whenNotificationPathDecidesDisabled() {
        // Mirrors a revoked POST_NOTIFICATIONS: the notifier decides notifications
        // are not allowed, so the countdown notification must not appear.
        val notifier = AlarmNotifier(
            context,
            notificationsAllowed = { false },
            launchActivity = { _, _, _ -> }
        )

        notifier.launchCountdown()

        val posted = notificationManager.activeNotifications.any { it.id == AlarmNotifier.COUNTDOWN_NOTIFICATION_ID }
        assertFalse(
            "no countdown notification may appear when the notification path is decided disabled",
            posted
        )
    }

    @Test
    fun distinctBreaches_postDistinctNotifications() {
        val eventA = event()
        val eventB = event()
        val notifier = AlarmNotifier(
            context,
            notificationsAllowed = {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                    ?.areNotificationsEnabled() ?: false
            },
            launchActivity = { _, _, _ -> }
        )

        notifier.launch(eventA)
        notifier.launch(eventB)

        val codeA = eventA.id.hashCode() and 0x7fffffff
        val codeB = eventB.id.hashCode() and 0x7fffffff
        val postedA = awaitNotification(codeA)
        val postedB = awaitNotification(codeB)
        assertTrue("notification for event A must be present", postedA != null)
        assertTrue("notification for event B must be present", postedB != null)
        assertFalse("distinct breaches must not share one notification id", codeA == codeB)
    }

    private fun event(): BreachEvent = BreachEvent(
        id = UUID.randomUUID().toString(),
        timestamp = System.currentTimeMillis(),
        violationType = ViolationType.VALIDATED_NETWORK,
        tier = ResponseTier.ALARM_STREAK,
        weight = 1
    )

    /**
     * Polls for a posted notification instead of asserting on a single snapshot:
     * [NotificationManager.notify] reaches system_server over binder and the
     * active-notification list is not guaranteed to reflect it immediately on a
     * loaded emulator, which makes a synchronous query flaky. Absent an appearance
     * within the timeout the last observed snapshot is returned so the caller's
     * assertion message still shows the definitive state.
     */
    private fun awaitNotification(id: Int, timeoutMillis: Long = 10_000): Notification? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var latest: Notification? = null
        while (System.currentTimeMillis() < deadline) {
            latest = notificationManager.activeNotifications.firstOrNull { it.id == id }?.notification
            if (latest != null) return latest
            SystemClock.sleep(50)
        }
        return latest
    }

    private fun setNotificationsPermission(granted: Boolean) {
        val pkg = context.packageName
        val permission = "android.permission.POST_NOTIFICATIONS"
        val command = if (granted) "pm grant $pkg $permission" else "pm revoke $pkg $permission"
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        try {
            FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        } finally {
            pfd.close()
        }
        if (granted) {
            // The grant reaches the notification service over a package broadcast;
            // wait until the app actually observes it so the "notifications allowed"
            // decision in the code under test reflects the granted state.
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && !notificationManager.areNotificationsEnabled()) {
                SystemClock.sleep(50)
            }
        } else {
            Thread.sleep(500)
        }
    }
}
