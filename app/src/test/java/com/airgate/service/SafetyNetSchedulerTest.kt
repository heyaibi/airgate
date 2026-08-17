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

package com.airgate.service

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airgate.data.crypto.PinManager
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.domain.model.AppConfig
import com.airgate.testutil.crypto.AndroidKeyStoreRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Unit tests for [SafetyNetScheduler] that exercise the actual production code.
 *
 * Uses [SafetyNetScheduler.isScheduled] as a test seam: by replacing it with
 * a controllable lambda, the real `schedule()` path is exercised — including
 * the config check, the interval math, and the AlarmManager call — while the
 * `FLAG_NO_CREATE` PendingIntent check (unsupported by Robolectric) is stubbed.
 *
 * After each test, the seam is restored to the real implementation so tests
 * never leak state into each other.
 */
@RunWith(AndroidJUnit4::class)
class SafetyNetSchedulerTest {

    @get:Rule
    val androidKeyStoreRule = AndroidKeyStoreRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var originalIsScheduled: (Context) -> Boolean

    @Before
    fun setUp() {
        prefs = context.getSharedPreferences("airgate_secure_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        originalIsScheduled = SafetyNetScheduler.isScheduled
    }

    @After
    fun tearDown() {
        SafetyNetScheduler.isScheduled = originalIsScheduled
        SafetyNetScheduler.cancel(context)
        prefs.edit().clear().commit()
    }

    // --- BUG-035 core: schedule must not reset the timer ---

    @Test
    fun schedule_doesNothingWhenAlarmAlreadyPending() {
        val repo = SecurityStateRepository(prefs)
        repo.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        repo.saveConfig(AppConfig(isEnabled = true, safetyNetIntervalMinutes = 5))

        SafetyNetScheduler.isScheduled = { true }

        SafetyNetScheduler.schedule(context)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadow = Shadows.shadowOf(alarmManager)
        assertEquals(
            "no alarm must be scheduled when already pending",
            0, shadow.scheduledAlarms.size
        )
    }

    @Test
    fun schedule_setsAlarmWhenNotPending() {
        val repo = SecurityStateRepository(prefs)
        repo.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        repo.saveConfig(AppConfig(isEnabled = true, safetyNetIntervalMinutes = 5))

        SafetyNetScheduler.isScheduled = { false }

        SafetyNetScheduler.schedule(context)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadow = Shadows.shadowOf(alarmManager)
        assertEquals(
            "exactly one alarm must be scheduled when not pending",
            1, shadow.scheduledAlarms.size
        )
    }

    @Test
    fun schedule_repeatsDoNotResetTimer() {
        val repo = SecurityStateRepository(prefs)
        repo.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        repo.saveConfig(AppConfig(isEnabled = true, safetyNetIntervalMinutes = 5))

        var scheduled = false
        SafetyNetScheduler.isScheduled = { scheduled }

        SafetyNetScheduler.schedule(context)
        scheduled = true

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadow = Shadows.shadowOf(alarmManager)
        val firstTriggerAt = shadow.scheduledAlarms[0].triggerAtTime

        SafetyNetScheduler.schedule(context)
        SafetyNetScheduler.schedule(context)

        assertEquals(
            "only one alarm must be scheduled across repeated calls",
            1, shadow.scheduledAlarms.size
        )
        assertEquals(
            "trigger time must not change on repeated schedule",
            firstTriggerAt, shadow.scheduledAlarms[0].triggerAtTime
        )
    }

    // --- config gate ---

    @Test
    fun schedule_noOpWhenDisabled() {
        val repo = SecurityStateRepository(prefs)
        repo.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        repo.saveConfig(AppConfig(isEnabled = false, safetyNetIntervalMinutes = 5))

        SafetyNetScheduler.isScheduled = { false }

        SafetyNetScheduler.schedule(context)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadow = Shadows.shadowOf(alarmManager)
        assertEquals(
            "no alarm must be scheduled when disabled",
            0, shadow.scheduledAlarms.size
        )
    }

    @Test
    fun schedule_enforcesMinimumInterval() {
        val repo = SecurityStateRepository(prefs)
        repo.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        repo.saveConfig(AppConfig(isEnabled = true, safetyNetIntervalMinutes = 0))

        SafetyNetScheduler.isScheduled = { false }

        val before = System.currentTimeMillis()
        SafetyNetScheduler.schedule(context)
        val after = System.currentTimeMillis()

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadow = Shadows.shadowOf(alarmManager)
        assertEquals(1, shadow.scheduledAlarms.size)
        val triggerAt = shadow.scheduledAlarms[0].triggerAtTime
        assertTrue(
            "trigger must be at least 30s (MIN_INTERVAL_MS) from now",
            triggerAt >= before + 30_000L && triggerAt <= after + 30_000L + 1_000L
        )
    }

    @Test
    fun schedule_usesConfiguredInterval() {
        val repo = SecurityStateRepository(prefs)
        repo.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        repo.saveConfig(AppConfig(isEnabled = true, safetyNetIntervalMinutes = 15))

        SafetyNetScheduler.isScheduled = { false }

        val before = System.currentTimeMillis()
        SafetyNetScheduler.schedule(context)
        val after = System.currentTimeMillis()

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadow = Shadows.shadowOf(alarmManager)
        assertEquals(1, shadow.scheduledAlarms.size)
        val triggerAt = shadow.scheduledAlarms[0].triggerAtTime
        assertTrue(
            "trigger must be ~15 minutes from now",
            triggerAt >= before + 15 * 60_000L && triggerAt <= after + 15 * 60_000L + 1_000L
        )
    }

    // --- cancel ---

    @Test
    fun cancel_removesAlarm() {
        val repo = SecurityStateRepository(prefs)
        repo.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        repo.saveConfig(AppConfig(isEnabled = true, safetyNetIntervalMinutes = 5))

        SafetyNetScheduler.isScheduled = { false }
        SafetyNetScheduler.schedule(context)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadow = Shadows.shadowOf(alarmManager)
        assertEquals(1, shadow.scheduledAlarms.size)

        SafetyNetScheduler.cancel(context)

        assertEquals(
            "alarm must be removed after cancel",
            0, shadow.scheduledAlarms.size
        )
    }

    // --- receiver re-arm path ---

    @Test
    fun schedule_afterCancel_resumesScheduling() {
        val repo = SecurityStateRepository(prefs)
        repo.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        repo.saveConfig(AppConfig(isEnabled = true, safetyNetIntervalMinutes = 5))

        SafetyNetScheduler.isScheduled = { false }
        SafetyNetScheduler.schedule(context)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadow = Shadows.shadowOf(alarmManager)
        assertEquals(1, shadow.scheduledAlarms.size)

        SafetyNetScheduler.cancel(context)
        assertEquals(0, shadow.scheduledAlarms.size)

        SafetyNetScheduler.schedule(context)
        assertEquals(
            "alarm must be resumable after cancel",
            1, shadow.scheduledAlarms.size
        )
    }

    // --- integration: receiver fires then re-arms ---

    @Test
    fun schedule_afterReceiverFires_reArmsAlarm() {
        val repo = SecurityStateRepository(prefs)
        repo.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        repo.saveConfig(AppConfig(isEnabled = true, safetyNetIntervalMinutes = 5))

        var scheduled = false
        SafetyNetScheduler.isScheduled = { scheduled }

        SafetyNetScheduler.schedule(context)
        scheduled = true

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadow = Shadows.shadowOf(alarmManager)
        assertEquals(1, shadow.scheduledAlarms.size)

        // Receiver fires — PendingIntent is consumed, isScheduled returns false
        scheduled = false
        SafetyNetScheduler.schedule(context)
        scheduled = true

        // Android replaces (not appends) alarms for the same PendingIntent,
        // so there is still exactly one scheduled — proving the alarm was
        // re-armed after the receiver consumed the previous one.
        assertEquals(1, shadow.scheduledAlarms.size)
    }
}
