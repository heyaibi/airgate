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
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the PIN lockout overflow fix. Proves that the
 * capped [PinLockoutPolicy.lockoutMs] combined with the real monotonic clock
 * produces a valid, non-negative deadline even at attempt counts that would
 * previously overflow to Long.MAX_VALUE and wrap negative.
 */
@RunWith(AndroidJUnit4::class)
class PinLockoutOverflowInstrumentedTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var clock: MonotonicClock

    @Before
    fun setUp() {
        val prefs = context.getSharedPreferences(
            "pin_lockout_overflow_instrumented_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        clock = MonotonicClock(prefs)
    }

    @Test
    fun lockoutMs_never_exceeds_24_hours() {
        val max = PinLockoutPolicy.MAX_LOCKOUT_MS
        assertEquals(24 * 60 * 60 * 1000L, max)

        val overflowAttempt = 54
        val result = PinLockoutPolicy.lockoutMs(overflowAttempt)
        assertEquals("attempt $overflowAttempt must return the cap", max, result)
        assertTrue("must not be Long.MAX_VALUE", result < Long.MAX_VALUE)
    }

    @Test
    fun lockoutMs_capped_for_every_attempt_above_threshold() {
        val max = PinLockoutPolicy.MAX_LOCKOUT_MS
        var attempts = PinLockoutPolicy.MAX_ATTEMPTS
        while (attempts <= 200) {
            val result = PinLockoutPolicy.lockoutMs(attempts)
            assertTrue(
                "attempt $attempts: $result must not exceed $max",
                result <= max
            )
            attempts++
        }
    }

    @Test
    fun deadline_never_overflows_when_added_to_monotonic_now() {
        val now = clock.now()
        val overflowAttempt = 54
        val lockoutMs = PinLockoutPolicy.lockoutMs(overflowAttempt)
        val deadline = now + lockoutMs

        assertTrue("deadline must be positive (was $deadline)", deadline > 0)
        assertTrue("deadline must be after now (deadline=$deadline, now=$now)", deadline > now)
    }

    @Test
    fun pinStore_persists_capped_deadline_correctly() {
        val prefs = context.getSharedPreferences(
            "pin_store_overflow_instrumented_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()

        val store = PinStore(
            prefs = prefs,
            store = ProtectedPrefsStore(prefs),
            clock = clock
        )

        val now = clock.now()
        val overflowAttempt = 54
        val lockoutMs = PinLockoutPolicy.lockoutMs(overflowAttempt)
        val deadline = now + lockoutMs

        store.setPinLockoutUntil(deadline)
        val retrieved = store.getPinLockoutUntil()
        assertEquals("stored deadline must match", deadline, retrieved)

        val remaining = store.getPinLockoutRemainingMs()
        assertTrue("remaining must be positive (was $remaining)", remaining > 0)
        assertTrue("remaining must not exceed MAX_LOCKOUT_MS", remaining <= PinLockoutPolicy.MAX_LOCKOUT_MS)
    }

    @Test
    fun pinStore_lockout_remaining_never_negative() {
        val prefs = context.getSharedPreferences(
            "pin_store_remaining_instrumented_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()

        val store = PinStore(
            prefs = prefs,
            store = ProtectedPrefsStore(prefs),
            clock = clock
        )

        val attempts = intArrayOf(5, 10, 17, 54, 100, 200, Int.MAX_VALUE)
        for (attempt in attempts) {
            val now = clock.now()
            val lockoutMs = PinLockoutPolicy.lockoutMs(attempt)
            val deadline = now + lockoutMs

            store.setPinLockoutUntil(deadline)
            val remaining = store.getPinLockoutRemainingMs()
            assertTrue(
                "attempt $attempt: remaining must be >= 0 (was $remaining)",
                remaining >= 0
            )
        }
    }
}
