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

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the persistent monotonic clock against the real
 * Android elapsed-realtime clock and real SharedPreferences storage.
 */
@RunWith(AndroidJUnit4::class)
class MonotonicClockInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun throwawayPrefs(): android.content.SharedPreferences {
        val prefs = context.getSharedPreferences(
            "monotonic_clock_it_${System.nanoTime()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        return prefs
    }

    @Test
    fun nowAdvancesWithRealElapsedTime() {
        val clock = MonotonicClock(throwawayPrefs())

        val first = clock.now()
        assertTrue("real uptime should be a positive reading", first > 0L)
        Thread.sleep(50)
        val second = clock.now()
        assertTrue("the clock must advance with real elapsed time", second >= first)
    }

    @Test
    fun twoInstancesOverTheSamePrefsAgree() {
        val prefs = throwawayPrefs()
        val a = MonotonicClock(prefs)
        val b = MonotonicClock(prefs)

        assertEquals(a.now(), b.now())
        Thread.sleep(10)
        assertEquals(a.now(), b.now())
    }

    @Test
    fun persistedReadingSurvivesARebootSimulatedByAResetElapsedClock() {
        val prefs = throwawayPrefs()
        val before = MonotonicClock(prefs)
        before.persistNow()
        val persisted = before.now()
        assertTrue(persisted > 0L)

        // A reboot resets the elapsed clock to (near) zero. The fresh instance
        // must resume the timeline from the persisted reading, never earlier.
        val afterReboot = MonotonicClock(prefs) { 0L }
        assertTrue(
            "post-reboot reading ${afterReboot.now()} must be at least the persisted $persisted",
            afterReboot.now() >= persisted
        )

        // Real time then accumulates on top of the resumed timeline.
        Thread.sleep(30)
        assertTrue(afterReboot.now() >= persisted)
    }

    @Test
    fun aRolledBackElapsedSourceCannotShortenTheTimeline() {
        val prefs = throwawayPrefs()
        var elapsed = 100_000_000L
        val clock = MonotonicClock(prefs) { elapsed }
        clock.persistNow()
        val persisted = clock.now()

        // The source reports a much smaller value (as if it reset / was rolled
        // back); the clock must clamp to the persisted reading.
        elapsed = 0L
        assertTrue(clock.now() >= persisted)
    }

    @Test
    fun repeatedPersistsKeepTheTimelineMonotonic() {
        val prefs = throwawayPrefs()
        val clock = MonotonicClock(prefs)

        var previous = clock.now()
        repeat(20) {
            Thread.sleep(5)
            clock.persistNow()
            val current = clock.now()
            assertTrue("clock went backward: $current < $previous", current >= previous)
            previous = current
        }
    }
}
