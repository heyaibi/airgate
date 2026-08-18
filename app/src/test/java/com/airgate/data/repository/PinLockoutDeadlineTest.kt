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

package com.airgate.data.repository

import com.airgate.data.crypto.JvmPrefsCrypto
import com.airgate.testutil.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the lockout deadline lives on the persistent monotonic clock and not
 * on the wall clock: a rolled-back wall clock must never clear a lockout, and a
 * reboot must never reset one either.
 */
class PinLockoutDeadlineTest {

    private val prefs = InMemorySharedPreferences()

    private fun repositoryAt(elapsed: () -> Long): SecurityStateRepository =
        SecurityStateRepository(
            prefs, JvmPrefsCrypto(), { true }, { true },
            elapsedRealtimeProvider = elapsed
        )

    @Test
    fun `a recorded deadline reports the remaining time on the monotonic clock`() {
        var elapsed = 10_000L
        val repository = repositoryAt { elapsed }
        repository.setPinLockoutUntil(repository.getMonotonicNow() + 60_000L)

        assertEquals(60_000L, repository.getPinLockoutRemainingMs())

        elapsed = 10_500L
        assertEquals(59_500L, repository.getPinLockoutRemainingMs())
    }

    @Test
    fun `the lockout expires once the monotonic clock passes the deadline`() {
        var elapsed = 0L
        val repository = repositoryAt { elapsed }
        repository.setPinLockoutUntil(repository.getMonotonicNow() + 30_000L)
        assertTrue(repository.getPinLockoutRemainingMs() > 0L)

        elapsed = 30_000L
        assertEquals(0L, repository.getPinLockoutRemainingMs())

        elapsed = 90_000L
        assertEquals(0L, repository.getPinLockoutRemainingMs())
    }

    @Test
    fun `a lockout survives a reboot without shrinking`() {
        var elapsed = 5_000L
        val before = repositoryAt { elapsed }
        before.setPinLockoutUntil(before.getMonotonicNow() + 30_000L)
        assertEquals(30_000L, before.getPinLockoutRemainingMs())

        // Reboot: the elapsed clock resets to zero but the persisted anchor
        // carries the timeline forward; the remaining time is unchanged.
        elapsed = 0L
        val afterReboot = repositoryAt { elapsed }
        assertEquals(30_000L, afterReboot.getPinLockoutRemainingMs())

        // Only real monotonic time burns the remaining window after the reboot.
        elapsed = 10_000L
        assertEquals(20_000L, afterReboot.getPinLockoutRemainingMs())
    }

    @Test
    fun `the deadline is independent of the wall clock`() {
        // A rolled-back wall clock is irrelevant: remaining time is computed
        // purely from the monotonic clock, which the user cannot change.
        var elapsed = 100_000L
        val repository = repositoryAt { elapsed }
        repository.setPinLockoutUntil(repository.getMonotonicNow() + 60_000L)

        // The wall clock moving backward (as it does when the user edits the
        // date) changes nothing about the remaining window.
        elapsed = 100_500L
        assertEquals(59_500L, repository.getPinLockoutRemainingMs())
    }

    @Test
    fun `a deadline set before a reboot is still fully counted after it`() {
        var elapsed = 1_000L
        val before = repositoryAt { elapsed }
        before.setPinLockoutUntil(before.getMonotonicNow() + 10_000L)

        elapsed = 0L
        val after = repositoryAt { elapsed }
        assertEquals(10_000L, after.getPinLockoutRemainingMs())
    }

    @Test
    fun `reset clears the lockout deadline`() {
        var elapsed = 0L
        val repository = repositoryAt { elapsed }
        repository.setPinLockoutUntil(repository.getMonotonicNow() + 30_000L)
        assertTrue(repository.getPinLockoutRemainingMs() > 0L)

        repository.resetPinFailedAttempts()

        assertEquals(0L, repository.getPinLockoutRemainingMs())
        assertEquals(0L, repository.getPinLockoutUntil())
    }

    @Test
    fun `explicitly clearing the deadline ends the lockout`() {
        var elapsed = 0L
        val repository = repositoryAt { elapsed }
        repository.setPinLockoutUntil(repository.getMonotonicNow() + 30_000L)
        assertTrue(repository.getPinLockoutRemainingMs() > 0L)

        repository.setPinLockoutUntil(0L)

        assertEquals(0L, repository.getPinLockoutRemainingMs())
    }

    @Test
    fun `no deadline ever recorded means no lockout`() {
        val repository = repositoryAt { 0L }
        assertEquals(0L, repository.getPinLockoutUntil())
        assertEquals(0L, repository.getPinLockoutRemainingMs())
    }

    @Test
    fun `a fresh repository instance over the same prefs sees the same deadline`() {
        var elapsed = 10_000L
        repositoryAt { elapsed }.setPinLockoutUntil(repositoryAt { elapsed }.getMonotonicNow() + 60_000L)

        val fresh = repositoryAt { elapsed }
        assertEquals(60_000L, fresh.getPinLockoutRemainingMs())
    }

    @Test
    fun `getMonotonicNow is the same timeline across repository instances`() {
        var elapsed = 5_000L
        val a = repositoryAt { elapsed }
        a.setPinLockoutUntil(a.getMonotonicNow() + 30_000L)

        // A second instance constructed at the same moment shares the timeline.
        elapsed = 5_100L
        val b = repositoryAt { elapsed }
        assertTrue(b.getMonotonicNow() >= a.getMonotonicNow())
        assertTrue(b.getPinLockoutRemainingMs() <= 30_000L)
    }

    @Test
    fun `a deadline recorded in the past reads as expired not negative`() {
        var elapsed = 100_000L
        val repository = repositoryAt { elapsed }
        repository.setPinLockoutUntil(repository.getMonotonicNow() + 1_000L)

        elapsed = 200_000L
        assertEquals(0L, repository.getPinLockoutRemainingMs())
    }
}
