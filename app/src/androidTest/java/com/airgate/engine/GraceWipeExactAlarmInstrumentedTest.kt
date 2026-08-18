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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airgate.data.crypto.PinManager
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.SecurityState
import com.airgate.receiver.GraceWipeReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * On-device verification of the exact-alarm contract against the real
 * [android.app.AlarmManager]:
 *
 * - With SCHEDULE_EXACT_ALARM access granted, the scheduler arms a real exact
 *   alarm and reports EXACT_SCHEDULED.
 * - A persisted countdown is reconciled (re-armed for the remaining grace) by
 *   the real scheduler when the access is granted.
 * - An elapsed countdown is reconciled to the wipe by the real scheduler.
 *
 * The revoked-access paths (EXACT_UNAVAILABLE, fail-closed reconcile) are
 * covered exhaustively on the JVM: the platform kills the app process on
 * revocation, so a live revoke cannot be observed inside a running test process.
 */
@RunWith(AndroidJUnit4::class)
class GraceWipeExactAlarmInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        grantExactAlarmAccess()
        val prefs = context.getSharedPreferences("airgate_secure_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        cancelGraceWipeAlarm()
    }

    @After
    fun tearDown() {
        cancelGraceWipeAlarm()
        val prefs = context.getSharedPreferences("airgate_secure_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        grantExactAlarmAccess()
    }

    @Test
    fun canScheduleExactAlarms_isTrueWhenAccessIsGranted() {
        grantExactAlarmAccess()
        val repository = SecurityStateRepository(context)

        assertTrue("exact-alarm access must be granted in this test", repository.canScheduleExactAlarms())
    }

    @Test
    fun scheduleDelay_registersARealExactAlarmWhenAccessIsGranted() {
        grantExactAlarmAccess()
        val scheduler = GraceWipeScheduler(context)
        try {
            val result = scheduler.scheduleDelay(60_000L)

            assertEquals(GraceWipeScheduler.WipeScheduleResult.EXACT_SCHEDULED, result)
            assertTrue(
                "a real exact alarm must be registered after scheduleDelay",
                isGraceWipeAlarmPending()
            )
        } finally {
            scheduler.cancel()
        }
    }

    @Test
    fun schedule_registersARealExactAlarmWhenAccessIsGranted() {
        grantExactAlarmAccess()
        val scheduler = GraceWipeScheduler(context)
        try {
            val result = scheduler.schedule(AppConfig(graceWindowSeconds = 30))

            assertEquals(GraceWipeScheduler.WipeScheduleResult.EXACT_SCHEDULED, result)
            assertTrue(isGraceWipeAlarmPending())
        } finally {
            scheduler.cancel()
        }
    }

    @Test
    fun reconcilePendingWipe_rearmsAPersistedCountdownWhenAccessIsGranted() {
        grantExactAlarmAccess()
        val prefs = realPrefs()
        try {
            val repository = armedCountdown(prefs)
            repository.setWipeDeadline(repository.getMonotonicNow() + 2_000L)
            assertTrue("the countdown must persist a future deadline", repository.getWipeRemainingMs() in 1..2_000)

            val engine = ThreatEngine(context, repository, DhizukuManager(context))
            engine.reconcilePendingWipe()

            assertEquals(
                "a still-future countdown must be re-armed, not wiped",
                SecurityState.COUNTDOWN_WIPE,
                repository.getSecurityState()
            )
            assertTrue("the re-armed wipe must be a real registered alarm", isGraceWipeAlarmPending())
        } finally {
            cancelGraceWipeAlarm()
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun reconcilePendingWipe_executesTheWipeWhenTheDeadlineElapsed() {
        grantExactAlarmAccess()
        val prefs = realPrefs()
        try {
            val repository = armedCountdown(prefs)
            repository.setWipeDeadline(repository.getMonotonicNow() - 1_000L)
            assertTrue(repository.getWipeRemainingMs() == 0L)

            val engine = ThreatEngine(context, repository, DhizukuManager(context))
            engine.reconcilePendingWipe()

            assertEquals(SecurityState.WIPING, repository.getSecurityState())
            assertEquals(0L, repository.getWipeDeadline())
        } finally {
            prefs.edit().clear().commit()
        }
    }

    // --- helpers ---

    private fun realPrefs(): android.content.SharedPreferences {
        val prefs = context.getSharedPreferences("airgate_secure_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return prefs
    }

    private fun armedCountdown(prefs: android.content.SharedPreferences): SecurityStateRepository {
        val repository = SecurityStateRepository(prefs, null, notificationsAllowedProvider = { true })
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        val config = repository.saveConfig(
            AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 2)
        )
        assertTrue("test must arm the watchdog", config.isEnabled)
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        return repository
    }

    private fun grantExactAlarmAccess() {
        runShell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
    }

    private fun runShell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            .use { pipe ->
                InputStreamReader(FileInputStream(pipe.fileDescriptor)).readText()
            }
    }

    private fun isGraceWipeAlarmPending(): Boolean {
        val intent = Intent(context, GraceWipeReceiver::class.java).apply {
            action = GraceWipeReceiver.ACTION
            putExtra(GraceWipeReceiver.EXTRA_DEADLINE, 0L)
        }
        return PendingIntent.getBroadcast(
            context, 3001, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) != null
    }

    private fun cancelGraceWipeAlarm() {
        GraceWipeScheduler(context).cancel()
        val intent = Intent(context, GraceWipeReceiver::class.java).apply {
            action = GraceWipeReceiver.ACTION
            putExtra(GraceWipeReceiver.EXTRA_DEADLINE, 0L)
        }
        PendingIntent.getBroadcast(
            context, 3001, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )?.cancel()
    }
}
