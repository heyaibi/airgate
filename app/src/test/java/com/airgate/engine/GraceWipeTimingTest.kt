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

import com.airgate.receiver.GraceWipeReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure timing math behind the grace-wipe deadline. Both sides — scheduling and
 * the receiver's guard — run on the monotonic clock, so a wall-clock rollback
 * can neither postpone the wipe nor make an elapsed deadline look unreached.
 */
class GraceWipeTimingTest {

    // --- Scheduler: trigger computation ---

    @Test
    fun `trigger is the monotonic now plus the delay`() {
        assertEquals(110_000L, GraceWipeScheduler.computeTriggerAt(100_000L, 10_000L))
        assertEquals(1_000L, GraceWipeScheduler.computeTriggerAt(0L, 1_000L))
    }

    @Test
    fun `trigger is monotonic-now plus delay even at large uptimes`() {
        val uptime = 5_000_000_000L // ~58 days since boot
        assertEquals(uptime + 60_000L, GraceWipeScheduler.computeTriggerAt(uptime, 60_000L))
    }

    @Test
    fun `zero delay triggers immediately`() {
        assertEquals(100_000L, GraceWipeScheduler.computeTriggerAt(100_000L, 0L))
    }

    @Test
    fun `a negative delay cannot move the trigger before now`() {
        // Callers configure a non-negative delay; even a degenerate value must
        // not produce a trigger in the past.
        assertEquals(99_000L, GraceWipeScheduler.computeTriggerAt(100_000L, -1_000L))
    }

    // --- Receiver: deadline guard ---

    @Test
    fun `zero deadline never blocks the wipe`() {
        assertFalse(GraceWipeReceiver.shouldSkipWipe(0L, 100_000L))
        assertFalse(GraceWipeReceiver.shouldSkipWipe(0L, 0L))
    }

    @Test
    fun `a deadline that has not elapsed blocks the wipe`() {
        assertTrue(GraceWipeReceiver.shouldSkipWipe(110_000L, 100_000L))
    }

    @Test
    fun `a deadline exactly at now does not block the wipe`() {
        assertFalse(GraceWipeReceiver.shouldSkipWipe(100_000L, 100_000L))
    }

    @Test
    fun `an elapsed deadline does not block the wipe`() {
        assertFalse(GraceWipeReceiver.shouldSkipWipe(100_000L, 100_001L))
        assertFalse(GraceWipeReceiver.shouldSkipWipe(100_000L, 200_000L))
    }

    @Test
    fun `a wall-clock rollback cannot make an elapsed deadline look unreached`() {
        // The deadline was reached at monotonic time 100_000; the wall clock is
        // irrelevant to the guard, so a clock change changes nothing.
        assertFalse(GraceWipeReceiver.shouldSkipWipe(100_000L, 101_000L))
        assertFalse(GraceWipeReceiver.shouldSkipWipe(100_000L, 99_999L + 1L))
    }
}
