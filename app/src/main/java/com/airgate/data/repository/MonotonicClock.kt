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

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.edit

/**
 * A wall clock that the user cannot change and that keeps running across
 * device reboots.
 *
 * Security deadlines such as the PIN brute-force lockout must not be tied to
 * the displayed clock: the wall clock "may jump backwards or forwards
 * unpredictably" ([android.os.SystemClock] docs), so an attacker holding the
 * device can roll it back to clear a lockout or cancel a wipe countdown.
 *
 * [SystemClock.elapsedRealtime] is guaranteed monotonic and cannot be adjusted
 * by the user, but it resets at every boot. This clock persists an anchor so a
 * post-reboot instance continues the previous timeline instead of restarting
 * at zero. The invariant is that monotonic time never goes backward, even
 * across a power cycle or a backwards tick of the source.
 *
 * Two values are persisted together:
 *  - the [KEY_ANCHOR] monotonic reading at the last [persistNow], and
 *  - the [KEY_ANCHOR_ELAPSED] elapsed-realtime reading at that moment.
 *
 * `now()` is then `anchor + (elapsed - anchorElapsed)`; after a reboot the
 * elapsed clock comes back near zero and the formula resumes from the anchor.
 * A backwards tick (which can only be a reboot of the source) re-anchors at
 * the current elapsed value instead of going backward.
 *
 * The time source is injectable so pure-JVM tests can simulate time passing
 * and a reboot by swapping in a controllable provider.
 */
internal class MonotonicClock(
    private val prefs: SharedPreferences,
    private val elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() }
) {

    private companion object {
        const val KEY_ANCHOR = "monotonic_clock_anchor"
        const val KEY_ANCHOR_ELAPSED = "monotonic_clock_anchor_elapsed"
    }

    // In-memory view of the last-persisted state, refreshed (and self-healed to
    // the latest values any instance has recorded) on every read so concurrent
    // instances all stay on one timeline.
    private var anchor = 0L
    private var anchorElapsed = 0L

    /** The current monotonic reading, in milliseconds. Never goes backward. */
    fun now(): Long {
        val elapsed = elapsedRealtimeProvider()
        val (a, e) = effectiveBase(elapsed)
        anchor = a
        anchorElapsed = e
        return a + (elapsed - e)
    }

    /**
     * Persists the current reading as the anchor for a future boot. Called when
     * a deadline is recorded, so a reboot that resets the elapsed clock cannot
     * move that deadline backward.
     */
    fun persistNow() {
        val elapsed = elapsedRealtimeProvider()
        val (a, e) = effectiveBase(elapsed)
        val next = a + (elapsed - e)
        anchor = next
        anchorElapsed = elapsed
        prefs.edit {
            putLong(KEY_ANCHOR, next)
            putLong(KEY_ANCHOR_ELAPSED, elapsed)
        }
    }

    /**
     * Resolves the (anchor, anchorElapsed) pair to use for a reading at the
     * given elapsed time:
     *  - a later anchor persisted by a concurrent instance is adopted, and
     *  - if the elapsed clock has reset since the anchor was recorded (a
     *    reboot, or any backwards tick), the elapsed base re-anchors at the
     *    current value so the timeline resumes from the anchor.
     */
    private fun effectiveBase(elapsed: Long): Pair<Long, Long> {
        var effAnchor = anchor
        var effElapsed = anchorElapsed
        val persistedAnchor = prefs.getLong(KEY_ANCHOR, 0L)
        if (persistedAnchor > effAnchor) {
            effAnchor = persistedAnchor
            effElapsed = prefs.getLong(KEY_ANCHOR_ELAPSED, 0L)
        }
        if (elapsed < effElapsed) effElapsed = elapsed
        return effAnchor to effElapsed
    }
}
