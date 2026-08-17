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
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.SecurityState
import com.airgate.engine.GraceWipeScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.airgate.testutil.crypto.AndroidKeyStoreRule
import org.junit.Rule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * JVM verification (Robolectric) of the grace-wipe deadline guard against the real
 * monotonic clock. A scheduled wipe only executes once its monotonic deadline
 * has genuinely elapsed; a wall-clock rollback cannot make an elapsed deadline
 * look unreached because the guard never consults the wall clock.
 */
@RunWith(AndroidJUnit4::class)
class GraceWipeReceiverStorageTest {

    @get:Rule
    val androidKeyStoreRule = AndroidKeyStoreRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private fun armCountdownRepository(): SecurityStateRepository {
        val prefs = context.getSharedPreferences(
            "grace_wipe_it_${System.nanoTime()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        val repository = SecurityStateRepository(prefs)
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        repository.saveConfig(
            AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 0)
        )
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        return repository
    }

    private fun receive(repository: SecurityStateRepository, deadline: Long, action: String = GraceWipeReceiver.ACTION) {
        val receiver = object : GraceWipeReceiver() {
            override fun createRepository(context: Context): SecurityStateRepository = repository
        }
        val intent = Intent(context, GraceWipeReceiver::class.java)
            .setAction(action)
            .putExtra(GraceWipeReceiver.EXTRA_DEADLINE, deadline)
        receiver.onReceive(context, intent)
    }

    /** A scheduler that records re-arms instead of touching the real AlarmManager. */
    private class RecordingGraceWipeScheduler : GraceWipeScheduler(
        ApplicationProvider.getApplicationContext(),
        { 0L }
    ) {
        val scheduleDelays = mutableListOf<Long>()
        override fun scheduleDelay(delayMs: Long) {
            scheduleDelays.add(delayMs)
        }
    }

    /**
     * Delivers the wipe broadcast with a future (not-yet-elapsed) deadline through
     * a receiver whose re-arm lands on [scheduler], so the early-fire path is
     * exercised end to end through the real onReceive/background-thread wiring.
     */
    private fun receiveEarly(
        repository: SecurityStateRepository,
        scheduler: RecordingGraceWipeScheduler,
        deadline: Long
    ) {
        val receiver = object : GraceWipeReceiver(schedulerProvider = { scheduler }) {
            override fun createRepository(context: Context): SecurityStateRepository = repository
        }
        val intent = Intent(context, GraceWipeReceiver::class.java)
            .setAction(GraceWipeReceiver.ACTION)
            .putExtra(GraceWipeReceiver.EXTRA_DEADLINE, deadline)
        receiver.onReceive(context, intent)
    }

    /**
     * onReceive enqueues the wipe onto a background thread and returns before the
     * wipe executes (it must never run on the main thread), so the WIPING outcome
     * is awaited rather than asserted synchronously.
     */
    private fun awaitWipe(repository: SecurityStateRepository, timeoutMillis: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (repository.getSecurityState() != SecurityState.WIPING && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    /**
     * A wipe that is legitimately skipped must stay skipped: give any would-be
     * delayed execution a chance to fire, then assert the state is unchanged.
     */
    private fun awaitSettled(repository: SecurityStateRepository, expected: SecurityState) {
        Thread.sleep(300)
        assertEquals(expected, repository.getSecurityState())
    }

    @Test
    fun wrongAction_isIgnored() {
        val repository = armCountdownRepository()
        receive(repository, deadline = 0L, action = "com.example.OTHER_ACTION")
        awaitSettled(repository, SecurityState.COUNTDOWN_WIPE)
    }

    @Test
    fun deadlineInTheFuture_skipsTheWipe() {
        val repository = armCountdownRepository()
        val futureDeadline = SystemClock.elapsedRealtime() + 60_000L
        receive(repository, deadline = futureDeadline)
        awaitSettled(repository, SecurityState.COUNTDOWN_WIPE)
    }

    @Test
    fun earlyFire_rearmsTheRemainingDelayThroughTheRealReceiverPath() {
        val repository = armCountdownRepository()
        val scheduler = RecordingGraceWipeScheduler()
        val now = SystemClock.elapsedRealtime()
        val deadline = now + 60_000L

        receiveEarly(repository, scheduler, deadline)
        awaitSettled(repository, SecurityState.COUNTDOWN_WIPE)

        assertEquals(1, scheduler.scheduleDelays.size)
        val remaining = scheduler.scheduleDelays[0]
        assertTrue("remaining delay must be positive and at most the grace, was $remaining", remaining in 1..60_000)
        // The re-arm preserves the absolute deadline: now + remaining == the original deadline.
        assertTrue(
            "the re-armed deadline must equal the original, now=$now remaining=$remaining deadline=$deadline",
            now + remaining in deadline - 1..deadline
        )
    }

    @Test
    fun earlyFire_disarmedState_doesNotRearm() {
        val repository = armCountdownRepository()
        repository.setSecurityState(SecurityState.ALARM_ACTIVE)
        val scheduler = RecordingGraceWipeScheduler()

        receiveEarly(repository, scheduler, SystemClock.elapsedRealtime() + 60_000L)
        awaitSettled(repository, SecurityState.ALARM_ACTIVE)

        assertEquals(emptyList<Long>(), scheduler.scheduleDelays)
    }

    @Test
    fun earlyFire_disabledConfig_doesNotRearm() {
        val repository = armCountdownRepository()
        repository.saveConfig(AppConfig(isEnabled = false, dryRunMode = true))
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        val scheduler = RecordingGraceWipeScheduler()

        receiveEarly(repository, scheduler, SystemClock.elapsedRealtime() + 60_000L)
        awaitSettled(repository, SecurityState.COUNTDOWN_WIPE)

        assertEquals(emptyList<Long>(), scheduler.scheduleDelays)
    }

    @Test
    fun earlyFire_wrongAction_doesNotRearm() {
        val repository = armCountdownRepository()
        val scheduler = RecordingGraceWipeScheduler()
        val receiver = object : GraceWipeReceiver(schedulerProvider = { scheduler }) {
            override fun createRepository(context: Context): SecurityStateRepository = repository
        }
        val intent = Intent(context, GraceWipeReceiver::class.java)
            .setAction("com.example.OTHER_ACTION")
            .putExtra(GraceWipeReceiver.EXTRA_DEADLINE, SystemClock.elapsedRealtime() + 60_000L)
        receiver.onReceive(context, intent)
        awaitSettled(repository, SecurityState.COUNTDOWN_WIPE)

        assertEquals(emptyList<Long>(), scheduler.scheduleDelays)
    }

    @Test
    fun elapsedDeadline_executesTheWipe() {
        val repository = armCountdownRepository()
        receive(repository, deadline = SystemClock.elapsedRealtime() - 1_000L)
        awaitWipe(repository)
    }

    @Test
    fun deadlineExactlyAtNow_executesTheWipe() {
        val repository = armCountdownRepository()
        receive(repository, deadline = SystemClock.elapsedRealtime())
        awaitWipe(repository)
    }

    @Test
    fun zeroDeadline_neverBlocksTheWipe() {
        val repository = armCountdownRepository()
        receive(repository, deadline = 0L)
        awaitWipe(repository)
    }

    @Test
    fun disabledConfig_skipsTheWipeEvenWhenTheDeadlineHasElapsed() {
        val repository = armCountdownRepository()
        repository.saveConfig(AppConfig(isEnabled = false, dryRunMode = true))
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)

        receive(repository, deadline = SystemClock.elapsedRealtime() - 1_000L)

        awaitSettled(repository, SecurityState.COUNTDOWN_WIPE)
    }

    @Test
    fun stateNoLongerCountdown_skipsTheWipeEvenWhenTheDeadlineHasElapsed() {
        val repository = armCountdownRepository()
        repository.setSecurityState(SecurityState.ALARM_ACTIVE)

        receive(repository, deadline = SystemClock.elapsedRealtime() - 1_000L)

        awaitSettled(repository, SecurityState.ALARM_ACTIVE)
    }

    /**
     * Pins the receiver's threading contract: onReceive must return before the
     * wipe executes, and the wipe's deadline guard must run on a separate worker
     * thread — never the main thread. The monotonic-clock read is blocked by a
     * latch, so while onReceive has already returned, the wipe is still pending;
     * releasing the latch lets it proceed. If the wipe ever ran inline on the
     * main thread, onReceive would block in the clock read and the pending-state
     * assertion below would fail.
     */
    @Test
    fun onReceive_returnsBeforeTheWipeExecutes_andRunsItOffTheMainThread() {
        val repository = armCountdownRepository()
        val mainThread = Thread.currentThread()
        val workerReached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workerThreadName = AtomicReference<String>()

        val receiver = object : GraceWipeReceiver(
            elapsedRealtimeProvider = {
                workerThreadName.set(Thread.currentThread().name)
                workerReached.countDown()
                release.await(5, TimeUnit.SECONDS)
                SystemClock.elapsedRealtime()
            }
        ) {
            override fun createRepository(context: Context): SecurityStateRepository = repository
        }

        val intent = Intent(context, GraceWipeReceiver::class.java)
            .setAction(GraceWipeReceiver.ACTION)
            .putExtra(GraceWipeReceiver.EXTRA_DEADLINE, 0L)

        receiver.onReceive(context, intent)

        // onReceive has returned, but the wipe is still pending: the worker is
        // blocked inside the monotonic-clock read and has not touched state yet.
        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
        // The wipe guard runs on the receiver's worker thread, never the main thread.
        assertTrue("the wipe worker must start", workerReached.await(3, TimeUnit.SECONDS))
        assertTrue(
            "the wipe must run off the main thread (it ran on ${workerThreadName.get()})",
            workerThreadName.get() != mainThread.name
        )
        // Release the clock read; the wipe now proceeds and lands in WIPING.
        release.countDown()
        awaitWipe(repository)
    }
}
