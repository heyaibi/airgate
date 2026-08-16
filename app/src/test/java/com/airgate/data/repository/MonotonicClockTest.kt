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

import com.airgate.testutil.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonotonicClockTest {

    private val prefs = InMemorySharedPreferences()

    @Test
    fun `fresh clock with no anchor tracks the elapsed source from zero`() {
        var elapsed = 5_000L
        val clock = MonotonicClock(prefs) { elapsed }

        assertEquals(5_000L, clock.now())
        elapsed = 6_000L
        assertEquals(6_000L, clock.now())
    }

    @Test
    fun `clock tracks the elapsed source while time advances`() {
        var elapsed = 1_000L
        val clock = MonotonicClock(prefs) { elapsed }

        assertEquals(1_000L, clock.now())
        elapsed = 2_000L
        assertEquals(2_000L, clock.now())
        elapsed = 2_000_000L
        assertEquals(2_000_000L, clock.now())
    }

    @Test
    fun `persistNow survives a reboot and the timeline keeps going`() {
        var elapsed = 5_000L
        val before = MonotonicClock(prefs) { elapsed }
        elapsed = 10_000L
        assertEquals(10_000L, before.now())
        before.persistNow()

        // Reboot: the elapsed clock resets to a small value (near zero).
        elapsed = 0L
        val afterReboot = MonotonicClock(prefs) { elapsed }

        // The monotonic reading continues from where the pre-reboot session
        // persisted it, never restarting at zero.
        assertEquals(10_000L, afterReboot.now())

        elapsed = 100L
        assertEquals(10_100L, afterReboot.now())
    }

    @Test
    fun `a deadline persists with the anchor across multiple reboots`() {
        // Boot 1: advance the timeline, persist it, then record a deadline on it.
        var elapsed = 1_000L
        val boot1 = MonotonicClock(prefs) { elapsed }
        elapsed = 2_000L
        boot1.persistNow() // anchor = 2_000
        val deadline = boot1.now() + 30_000L // e.g. a lockout set at boot 1

        // Boot 2: the clock resumes at the anchor; the deadline is unchanged and
        // still exactly 30_000 ms away.
        elapsed = 0L
        val boot2 = MonotonicClock(prefs) { elapsed }
        assertEquals(2_000L, boot2.now())
        assertEquals(30_000L, deadline - boot2.now())

        // Boot 3: a later boot re-anchors and keeps advancing.
        boot2.persistNow() // anchor stays 2_000, elapsed base re-anchors to 0
        elapsed = 0L
        val boot3 = MonotonicClock(prefs) { elapsed }
        assertEquals(2_000L, boot3.now())
        elapsed = 500L
        assertEquals(2_500L, boot3.now())
    }

    @Test
    fun `clock never goes backward while the source advances`() {
        var elapsed = 0L
        val clock = MonotonicClock(prefs) { elapsed }

        var previous = clock.now()
        for (step in 1..1_000) {
            elapsed = step * 10L
            val current = clock.now()
            assertTrue("monotonic clock went backward: $current < $previous", current >= previous)
            previous = current
        }
    }

    @Test
    fun `a reboot cannot move a previously recorded reading backward`() {
        var elapsed = 50_000L
        val clock = MonotonicClock(prefs) { elapsed }
        elapsed = 60_000L
        clock.persistNow() // anchor = 60_000
        assertEquals(60_000L, clock.now())

        // Attacker power-cycles the device; the elapsed clock comes back near
        // zero, but the persistent clock must not report an earlier time.
        elapsed = 0L
        val rebooted = MonotonicClock(prefs) { elapsed }
        assertEquals(60_000L, rebooted.now())
    }

    @Test
    fun `persistNow is monotonic even if the elapsed source is reset within a session`() {
        // A fresh instance constructed after the source reset must still pick up
        // the persisted anchor rather than restarting.
        var elapsed = 9_000L
        val first = MonotonicClock(prefs) { elapsed }
        elapsed = 18_000L
        first.persistNow() // anchor = 18_000

        elapsed = 100L // the source was reset (a second "boot" in the same prefs)
        val second = MonotonicClock(prefs) { elapsed }
        assertTrue(second.now() >= 18_000L)
    }

    @Test
    fun `an instance self-heals to a later anchor persisted by another instance`() {
        var elapsedA = 0L
        val early = MonotonicClock(prefs) { elapsedA }
        assertEquals(0L, early.now())

        var elapsedB = 1_000L
        val later = MonotonicClock(prefs) { elapsedB }
        elapsedB = 2_000L
        later.persistNow() // anchor = 2_000

        // The early instance must adopt the later anchor instead of reporting a
        // stale (earlier) reading that could make a shared deadline look elapsed.
        assertEquals(2_000L, early.now())
    }

    @Test
    fun `two instances agree at the same moment before any persist`() {
        var elapsed = 3_000L
        val a = MonotonicClock(prefs) { elapsed }
        val b = MonotonicClock(prefs) { elapsed }
        assertEquals(a.now(), b.now())

        elapsed = 5_000L
        assertEquals(a.now(), b.now())
    }

    @Test
    fun `two instances agree after one of them persists`() {
        var elapsed = 1_000L
        val a = MonotonicClock(prefs) { elapsed }
        val b = MonotonicClock(prefs) { elapsed }

        elapsed = 4_000L
        a.persistNow()
        assertEquals(a.now(), b.now())

        elapsed = 6_000L
        assertEquals(a.now(), b.now())
    }

    @Test
    fun `missing anchor key is treated as the very start of the timeline`() {
        assertEquals(0L, prefs.getLong("monotonic_clock_anchor", 0L))

        val clock = MonotonicClock(prefs) { 123L }
        assertEquals(123L, clock.now())
    }

    @Test
    fun `a backwards tick of the source clamps instead of going backward`() {
        var elapsed = 10_000L
        val clock = MonotonicClock(prefs) { elapsed }
        clock.persistNow() // anchor = 10_000

        // The provider reports an earlier value (as if the source reset).
        elapsed = 0L
        assertEquals(10_000L, clock.now())
    }
}
