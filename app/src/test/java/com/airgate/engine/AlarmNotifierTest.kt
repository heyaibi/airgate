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

import com.airgate.domain.model.BreachEvent
import com.airgate.domain.model.ResponseTier
import com.airgate.domain.model.ViolationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Flow-and-logging tests for [AlarmNotifier]. The notification-posting and
 * activity-launch side effects are injected so the decision logic (post only when
 * notifications are allowed, always attempt the activity launch, and record every
 * real failure instead of swallowing it) is exercised in a pure JVM. The actual
 * platform behavior — the notifications appear when POST_NOTIFICATIONS is granted
 * and not when it is revoked — is covered by the instrumented suite.
 */
class AlarmNotifierTest {

    private val context = android.content.ContextWrapper(null)

    private data class NotificationPost(val code: Int, val category: String, val description: String, val isCountdown: Boolean)

    private data class ActivityLaunch(val category: String, val description: String, val isCountdown: Boolean)

    @Test
    fun `launch posts the alarm notification when notifications are allowed`() {
        val event = event()
        val posted = mutableListOf<NotificationPost>()
        val launches = mutableListOf<ActivityLaunch>()
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<Pair<String, Throwable>>()

        val notifier = AlarmNotifier(
            context,
            notificationsAllowed = { true },
            postNotification = { code, category, description, isCountdown ->
                posted.add(NotificationPost(code, category, description, isCountdown))
            },
            launchActivity = { category, description, isCountdown ->
                launches.add(ActivityLaunch(category, description, isCountdown))
            },
            logWarning = { warnings.add(it) },
            logError = { m, t -> errors.add(m to t) }
        )

        notifier.launch(event)

        val expectedCode = event.id.hashCode() and 0x7fffffff
        assertEquals(
            listOf(NotificationPost(expectedCode, event.violationType.scoringGroup.displayName, event.violationType.description, false)),
            posted
        )
        assertEquals(1, launches.size)
        assertEquals(ActivityLaunch(event.violationType.scoringGroup.displayName, event.violationType.description, false), launches[0])
        assertTrue(errors.isEmpty())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `launch skips the notification and logs a warning when notifications are disabled`() {
        val event = event()
        val posted = mutableListOf<NotificationPost>()
        val launches = mutableListOf<ActivityLaunch>()
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<Pair<String, Throwable>>()

        val notifier = AlarmNotifier(
            context,
            notificationsAllowed = { false },
            postNotification = { code, category, description, isCountdown ->
                posted.add(NotificationPost(code, category, description, isCountdown))
            },
            launchActivity = { category, description, isCountdown ->
                launches.add(ActivityLaunch(category, description, isCountdown))
            },
            logWarning = { warnings.add(it) },
            logError = { m, t -> errors.add(m to t) }
        )

        notifier.launch(event)

        // The notification path is skipped entirely — this is the silent-alarm
        // condition being recorded rather than silently swallowed.
        assertTrue(posted.isEmpty())
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("notifications are disabled"))
        // The direct activity attempt is still made.
        assertEquals(1, launches.size)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `launch logs an error when posting the notification fails`() {
        val event = event()
        val launches = mutableListOf<ActivityLaunch>()
        val errors = mutableListOf<Pair<String, Throwable>>()
        val boom = IllegalStateException("notify failed")

        val notifier = AlarmNotifier(
            context,
            notificationsAllowed = { true },
            postNotification = { _, _, _, _ -> throw boom },
            launchActivity = { category, description, isCountdown ->
                launches.add(ActivityLaunch(category, description, isCountdown))
            },
            logWarning = {},
            logError = { m, t -> errors.add(m to t) }
        )

        notifier.launch(event)

        // A failed notification post must be recorded, not swallowed; the activity
        // launch attempt still runs.
        assertEquals(1, errors.size)
        assertEquals(boom, errors[0].second)
        assertTrue(errors[0].first.contains("post alarm notification"))
        assertEquals(1, launches.size)
    }

    @Test
    fun `launch logs an error when the activity launch is blocked`() {
        val event = event()
        val errors = mutableListOf<Pair<String, Throwable>>()

        val notifier = AlarmNotifier(
            context,
            notificationsAllowed = { false },
            postNotification = { _, _, _, _ -> },
            launchActivity = { _, _, _ -> throw IllegalStateException("Background activity start blocked") },
            logWarning = {},
            logError = { m, t -> errors.add(m to t) }
        )

        notifier.launch(event)

        // A silently-suppressed background activity start is the core of the
        // silent-alarm defect; the failure must be recorded, never swallowed.
        assertEquals(1, errors.size)
        assertTrue(errors[0].first.contains("launch alarm activity"))
        assertTrue(errors[0].second is IllegalStateException)
    }

    @Test
    fun `launchCountdown posts a countdown notification with its dedicated id when allowed`() {
        val posted = mutableListOf<NotificationPost>()
        val launches = mutableListOf<ActivityLaunch>()
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<Pair<String, Throwable>>()

        val notifier = AlarmNotifier(
            context,
            notificationsAllowed = { true },
            postNotification = { code, category, description, isCountdown ->
                posted.add(NotificationPost(code, category, description, isCountdown))
            },
            launchActivity = { category, description, isCountdown ->
                launches.add(ActivityLaunch(category, description, isCountdown))
            },
            logWarning = { warnings.add(it) },
            logError = { m, t -> errors.add(m to t) }
        )

        notifier.launchCountdown()

        // The countdown gets its own full-screen notification with the dedicated id.
        assertEquals(1, posted.size)
        assertEquals(AlarmNotifier.COUNTDOWN_NOTIFICATION_ID, posted[0].code)
        assertTrue(posted[0].isCountdown)
        assertTrue(posted[0].category.isEmpty())
        // The direct activity launch is still attempted.
        assertEquals(1, launches.size)
        assertTrue(launches[0].isCountdown)
        assertTrue(errors.isEmpty())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `launchCountdown skips the notification and logs a warning when notifications are disabled`() {
        val posted = mutableListOf<NotificationPost>()
        val launches = mutableListOf<ActivityLaunch>()
        val warnings = mutableListOf<String>()

        val notifier = AlarmNotifier(
            context,
            notificationsAllowed = { false },
            postNotification = { code, category, description, isCountdown ->
                posted.add(NotificationPost(code, category, description, isCountdown))
            },
            launchActivity = { category, description, isCountdown ->
                launches.add(ActivityLaunch(category, description, isCountdown))
            },
            logWarning = { warnings.add(it) },
            logError = { _, _ -> }
        )

        notifier.launchCountdown()

        assertTrue(posted.isEmpty())
        assertEquals(1, warnings.size)
        // The direct activity attempt is still made.
        assertEquals(1, launches.size)
        assertTrue(launches[0].isCountdown)
    }

    @Test
    fun `launchCountdown logs an error when posting the countdown notification fails`() {
        val launches = mutableListOf<ActivityLaunch>()
        val errors = mutableListOf<Pair<String, Throwable>>()
        val boom = IllegalStateException("countdown notify failed")

        val notifier = AlarmNotifier(
            context,
            notificationsAllowed = { true },
            postNotification = { _, _, _, _ -> throw boom },
            launchActivity = { category, description, isCountdown ->
                launches.add(ActivityLaunch(category, description, isCountdown))
            },
            logWarning = {},
            logError = { m, t -> errors.add(m to t) }
        )

        notifier.launchCountdown()

        assertEquals(1, errors.size)
        assertEquals(boom, errors[0].second)
        // The countdown activity launch still runs even if its notification failed.
        assertEquals(1, launches.size)
        assertTrue(launches[0].isCountdown)
    }

    @Test
    fun `launchCountdown logs an error when the countdown launch is blocked`() {
        val errors = mutableListOf<Pair<String, Throwable>>()

        val notifier = AlarmNotifier(
            context,
            notificationsAllowed = { false },
            postNotification = { _, _, _, _ -> },
            launchActivity = { _, _, _ -> throw IllegalStateException("blocked") },
            logWarning = {},
            logError = { m, t -> errors.add(m to t) }
        )

        notifier.launchCountdown()

        assertEquals(1, errors.size)
        assertTrue(errors[0].first.contains("launch alarm activity"))
    }

    @Test
    fun `launchWipeFailure launches the failure activity`() {
        val launches = mutableListOf<ActivityLaunch>()

        val notifier = AlarmNotifier(
            context,
            notificationsAllowed = { false },
            postNotification = { _, _, _, _ -> },
            launchActivity = { category, description, isCountdown ->
                launches.add(ActivityLaunch(category, description, isCountdown))
            },
            logWarning = {},
            logError = { _, _ -> }
        )

        notifier.launchWipeFailure()

        assertEquals(1, launches.size)
        assertEquals(AlarmNotifier.WIPE_FAILED_CATEGORY, launches[0].category)
        assertFalse(launches[0].isCountdown)
    }

    @Test
    fun `launchWipeFailure logs an error when the failure alarm launch is blocked`() {
        val errors = mutableListOf<Pair<String, Throwable>>()

        val notifier = AlarmNotifier(
            context,
            notificationsAllowed = { false },
            postNotification = { _, _, _, _ -> },
            launchActivity = { _, _, _ -> throw IllegalStateException("blocked") },
            logWarning = {},
            logError = { m, t -> errors.add(m to t) }
        )

        notifier.launchWipeFailure()

        assertEquals(1, errors.size)
        assertTrue(errors[0].first.contains("launch alarm activity"))
    }

    @Test
    fun `the notification id derives from the breach so distinct events do not collide`() {
        val eventA = event()
        val eventB = event()
        val posted = mutableListOf<Int>()

        val notifier = AlarmNotifier(
            context,
            notificationsAllowed = { true },
            postNotification = { code, _, _, _ -> posted.add(code) },
            launchActivity = { _, _, _ -> },
            logWarning = {},
            logError = { _, _ -> }
        )
        notifier.launch(eventA)
        notifier.launch(eventB)

        assertEquals(eventA.id.hashCode() and 0x7fffffff, posted[0])
        assertEquals(eventB.id.hashCode() and 0x7fffffff, posted[1])
        assertFalse(eventA.id == eventB.id && posted[0] == posted[1])
    }

    private fun event(): BreachEvent = BreachEvent(
        id = UUID.randomUUID().toString(),
        timestamp = System.currentTimeMillis(),
        violationType = ViolationType.VALIDATED_NETWORK,
        tier = ResponseTier.ALARM,
        weight = 1
    )
}
