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

package com.airgate.receiver

import android.content.Context
import com.airgate.data.crypto.JvmPrefsCrypto
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.SecurityState
import com.airgate.engine.GraceWipeScheduler
import com.airgate.testutil.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM guard tests for [GraceWipeReceiver]. The deadline decision runs on
 * an explicit monotonic [now] parameter — never the wall clock — so a rolled-back
 * wall clock cannot cancel an elapsed wipe. When the alarm fires before the
 * deadline, the receiver re-arms the remaining delay instead of dropping the
 * wipe (a spent one-shot alarm must not lose the wipe).
 */
class GraceWipeReceiverTest {

    private class DummyContext : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.airgate"
        override fun getSystemService(name: String): Any? = null
    }

    private class RecordingGraceWipeScheduler : GraceWipeScheduler(DummyContext(), { 0L }) {
        val scheduleDelays = mutableListOf<Long>()
        override fun scheduleDelay(delayMs: Long) {
            scheduleDelays.add(delayMs)
        }
    }

    private class RecordingRearmLogger {
        val rearmedMs = mutableListOf<Long>()
        val log: (Long) -> Unit = { rearmedMs.add(it) }
    }

    private val context = DummyContext()
    private val prefs = InMemorySharedPreferences()

    private fun countdownRepository(): SecurityStateRepository {
        val repository = SecurityStateRepository(prefs, JvmPrefsCrypto(), { true }) { 0L }
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        repository.saveConfig(
            AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 0)
        )
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        return repository
    }

    private fun receiver() = GraceWipeReceiver()

    /** A receiver that records any re-arm onto [scheduler] instead of touching AlarmManager. */
    private fun receiverWith(
        scheduler: RecordingGraceWipeScheduler,
        logger: RecordingRearmLogger = RecordingRearmLogger()
    ) = GraceWipeReceiver(schedulerProvider = { scheduler }, rearmLogger = logger.log)

    @Test
    fun disabledConfig_skipsTheWipeEvenWhenTheDeadlineHasElapsed() {
        val repository = countdownRepository()
        repository.saveConfig(AppConfig(isEnabled = false, dryRunMode = true))
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)

        receiver().executeIfDeadlineReached(context, repository, deadline = 0L, now = 100_000L)

        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
    }

    @Test
    fun stateNoLongerCountdown_skipsTheWipeEvenWhenTheDeadlineHasElapsed() {
        val repository = countdownRepository()
        repository.setSecurityState(SecurityState.ALARM_ACTIVE)

        receiver().executeIfDeadlineReached(context, repository, deadline = 0L, now = 100_000L)

        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
    }

    @Test
    fun deadlineInTheFuture_rearmsTheRemainingDelayAndSkipsTheWipe() {
        val repository = countdownRepository()
        val scheduler = RecordingGraceWipeScheduler()
        val logger = RecordingRearmLogger()

        receiverWith(scheduler, logger)
            .executeIfDeadlineReached(context, repository, deadline = 150_000L, now = 100_000L)

        assertEquals(listOf(50_000L), scheduler.scheduleDelays)
        assertEquals(listOf(50_000L), logger.rearmedMs)
        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
    }

    @Test
    fun deadlineInTheFuture_rearmsExactlyTheRemainingTime() {
        val repository = countdownRepository()
        val scheduler = RecordingGraceWipeScheduler()
        val logger = RecordingRearmLogger()

        receiverWith(scheduler, logger)
            .executeIfDeadlineReached(context, repository, deadline = 160_000L, now = 150_000L)

        assertEquals(listOf(10_000L), scheduler.scheduleDelays)
        assertEquals(listOf(10_000L), logger.rearmedMs)
    }

    @Test
    fun deadlineExactlyAtNow_executesTheWipeAndDoesNotRearm() {
        val repository = countdownRepository()
        val scheduler = RecordingGraceWipeScheduler()
        val logger = RecordingRearmLogger()

        receiverWith(scheduler, logger)
            .executeIfDeadlineReached(context, repository, deadline = 100_000L, now = 100_000L)

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertEquals(emptyList<Long>(), scheduler.scheduleDelays)
        assertEquals(emptyList<Long>(), logger.rearmedMs)
    }

    @Test
    fun elapsedDeadline_executesTheWipeAndDoesNotRearm() {
        val repository = countdownRepository()
        val scheduler = RecordingGraceWipeScheduler()
        val logger = RecordingRearmLogger()

        receiverWith(scheduler, logger)
            .executeIfDeadlineReached(context, repository, deadline = 50_000L, now = 100_000L)

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertEquals(emptyList<Long>(), scheduler.scheduleDelays)
        assertEquals(emptyList<Long>(), logger.rearmedMs)
    }

    @Test
    fun zeroDeadline_neverBlocksTheWipeAndDoesNotRearm() {
        val repository = countdownRepository()
        val scheduler = RecordingGraceWipeScheduler()
        val logger = RecordingRearmLogger()

        receiverWith(scheduler, logger)
            .executeIfDeadlineReached(context, repository, deadline = 0L, now = 100_000L)

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertEquals(emptyList<Long>(), scheduler.scheduleDelays)
        assertEquals(emptyList<Long>(), logger.rearmedMs)
    }

    @Test
    fun theGuardConsultsTheSuppliedMonotonicNowNotAWallClock() {
        // The deadline is 150_000 on the monotonic timeline. At monotonic now
        // 100_000 it has not elapsed, so the wipe must be skipped (and re-armed).
        // A guard that consulted the wall clock (whose reading is ~1.7e12 and thus
        // "past" 150_000) would wrongly execute the wipe here.
        val repository = countdownRepository()
        val scheduler = RecordingGraceWipeScheduler()
        val logger = RecordingRearmLogger()

        receiverWith(scheduler, logger)
            .executeIfDeadlineReached(context, repository, deadline = 150_000L, now = 100_000L)

        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
        assertEquals(listOf(50_000L), scheduler.scheduleDelays)
        assertEquals(listOf(50_000L), logger.rearmedMs)
    }

    @Test
    fun disabledConfig_doesNotRearmOnEarlyFire() {
        val repository = countdownRepository()
        repository.saveConfig(AppConfig(isEnabled = false, dryRunMode = true))
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        val scheduler = RecordingGraceWipeScheduler()
        val logger = RecordingRearmLogger()

        receiverWith(scheduler, logger)
            .executeIfDeadlineReached(context, repository, deadline = 150_000L, now = 100_000L)

        assertEquals(emptyList<Long>(), scheduler.scheduleDelays)
        assertEquals(emptyList<Long>(), logger.rearmedMs)
    }

    @Test
    fun stateNoLongerCountdown_doesNotRearmOnEarlyFire() {
        val repository = countdownRepository()
        repository.setSecurityState(SecurityState.ALARM_ACTIVE)
        val scheduler = RecordingGraceWipeScheduler()
        val logger = RecordingRearmLogger()

        receiverWith(scheduler, logger)
            .executeIfDeadlineReached(context, repository, deadline = 150_000L, now = 100_000L)

        assertEquals(emptyList<Long>(), scheduler.scheduleDelays)
        assertEquals(emptyList<Long>(), logger.rearmedMs)
    }

    // --- rearmRemainingDelay directly ---

    @Test
    fun rearmRemainingDelay_positiveRemaining_schedulesThatDelay() {
        val scheduler = RecordingGraceWipeScheduler()
        val logger = RecordingRearmLogger()

        receiverWith(scheduler, logger).rearmRemainingDelay(context, deadline = 160_000L, now = 100_000L)

        assertEquals(listOf(60_000L), scheduler.scheduleDelays)
        assertEquals(listOf(60_000L), logger.rearmedMs)
    }

    @Test
    fun rearmRemainingDelay_zeroRemaining_schedulesNothing() {
        val scheduler = RecordingGraceWipeScheduler()
        val logger = RecordingRearmLogger()

        receiverWith(scheduler, logger).rearmRemainingDelay(context, deadline = 100_000L, now = 100_000L)

        assertEquals(emptyList<Long>(), scheduler.scheduleDelays)
        assertEquals(emptyList<Long>(), logger.rearmedMs)
    }

    @Test
    fun rearmRemainingDelay_negativeRemaining_schedulesNothing() {
        val scheduler = RecordingGraceWipeScheduler()
        val logger = RecordingRearmLogger()

        receiverWith(scheduler, logger).rearmRemainingDelay(context, deadline = 100_000L, now = 150_000L)

        assertEquals(emptyList<Long>(), scheduler.scheduleDelays)
        assertEquals(emptyList<Long>(), logger.rearmedMs)
    }
}
