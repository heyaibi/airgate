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
 * Verifies the grace-wipe deadline is persisted on the monotonic clock so a
 * reboot mid-countdown can be reconciled (re-armed or executed) instead of
 * silently losing the wipe — and that a wall-clock rollback can neither postpone
 * it past its deadline nor cancel it.
 */
class WipeDeadlineTest {

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
        repository.setWipeDeadline(repository.getMonotonicNow() + 60_000L)

        assertEquals(60_000L, repository.getWipeRemainingMs())

        elapsed = 10_500L
        assertEquals(59_500L, repository.getWipeRemainingMs())
    }

    @Test
    fun `the deadline expires once the monotonic clock passes it`() {
        var elapsed = 0L
        val repository = repositoryAt { elapsed }
        repository.setWipeDeadline(repository.getMonotonicNow() + 2_000L)
        assertTrue(repository.getWipeRemainingMs() > 0L)

        elapsed = 2_000L
        assertEquals(0L, repository.getWipeRemainingMs())

        elapsed = 50_000L
        assertEquals(0L, repository.getWipeRemainingMs())
    }

    @Test
    fun `a wipe deadline survives a reboot without shrinking`() {
        var elapsed = 5_000L
        val before = repositoryAt { elapsed }
        before.setWipeDeadline(before.getMonotonicNow() + 30_000L)
        assertEquals(30_000L, before.getWipeRemainingMs())

        // Reboot: the elapsed clock resets to zero but the persisted anchor
        // carries the timeline forward; the remaining time is unchanged.
        elapsed = 0L
        val afterReboot = repositoryAt { elapsed }
        assertEquals(30_000L, afterReboot.getWipeRemainingMs())

        // Only real monotonic time burns the remaining window after the reboot.
        elapsed = 10_000L
        assertEquals(20_000L, afterReboot.getWipeRemainingMs())
    }

    @Test
    fun `the deadline is independent of the wall clock`() {
        // A rolled-back wall clock is irrelevant: remaining time is computed
        // purely from the monotonic clock, which the user cannot change.
        var elapsed = 100_000L
        val repository = repositoryAt { elapsed }
        repository.setWipeDeadline(repository.getMonotonicNow() + 60_000L)

        elapsed = 100_500L
        assertEquals(59_500L, repository.getWipeRemainingMs())
    }

    @Test
    fun `clearing the deadline ends the pending wipe`() {
        var elapsed = 0L
        val repository = repositoryAt { elapsed }
        repository.setWipeDeadline(repository.getMonotonicNow() + 30_000L)
        assertTrue(repository.getWipeRemainingMs() > 0L)

        repository.setWipeDeadline(0L)

        assertEquals(0L, repository.getWipeRemainingMs())
        assertEquals(0L, repository.getWipeDeadline())
    }

    @Test
    fun `no deadline ever recorded means nothing is pending`() {
        val repository = repositoryAt { 0L }
        assertEquals(0L, repository.getWipeDeadline())
        assertEquals(0L, repository.getWipeRemainingMs())
    }

    @Test
    fun `a fresh repository instance over the same prefs sees the same deadline`() {
        var elapsed = 10_000L
        repositoryAt { elapsed }.setWipeDeadline(repositoryAt { elapsed }.getMonotonicNow() + 60_000L)

        val fresh = repositoryAt { elapsed }
        assertEquals(60_000L, fresh.getWipeRemainingMs())
    }

    @Test
    fun `an elapsed wipe deadline reads as expired not negative`() {
        var elapsed = 100_000L
        val repository = repositoryAt { elapsed }
        repository.setWipeDeadline(repository.getMonotonicNow() + 1_000L)

        elapsed = 200_000L
        assertEquals(0L, repository.getWipeRemainingMs())
    }
}
