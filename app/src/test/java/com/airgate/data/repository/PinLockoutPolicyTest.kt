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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinLockoutPolicyTest {

    @Test
    fun `no lockout below the attempt threshold`() {
        assertEquals(0L, PinLockoutPolicy.lockoutMs(0))
        assertEquals(0L, PinLockoutPolicy.lockoutMs(1))
        assertEquals(0L, PinLockoutPolicy.lockoutMs(2))
        assertEquals(0L, PinLockoutPolicy.lockoutMs(3))
        assertEquals(0L, PinLockoutPolicy.lockoutMs(4))
    }

    @Test
    fun `the attempt at the threshold starts the base lockout`() {
        assertEquals(30_000L, PinLockoutPolicy.lockoutMs(5))
    }

    @Test
    fun `each further failure doubles the lockout up to the cap`() {
        assertEquals(60_000L, PinLockoutPolicy.lockoutMs(6))
        assertEquals(120_000L, PinLockoutPolicy.lockoutMs(7))
        assertEquals(240_000L, PinLockoutPolicy.lockoutMs(8))
        assertEquals(480_000L, PinLockoutPolicy.lockoutMs(9))
    }

    @Test
    fun `exponential growth continues without shrinking`() {
        var previous = PinLockoutPolicy.lockoutMs(5)
        for (attempts in 6..16) {
            val current = PinLockoutPolicy.lockoutMs(attempts)
            assertEquals("attempts=$attempts", previous * 2L, current)
            previous = current
        }
    }

    @Test
    fun `lockout caps at 24 hours`() {
        val max = PinLockoutPolicy.MAX_LOCKOUT_MS
        assertEquals(24 * 60 * 60 * 1000L, max)

        // Attempt 16 is the last uncapped value (30000 * 2^11 = 61_440_000).
        assertEquals(61_440_000L, PinLockoutPolicy.lockoutMs(16))

        // Attempt 17 would exceed 24h (30000 * 2^12 = 122_880_000) → capped.
        assertEquals(max, PinLockoutPolicy.lockoutMs(17))
    }

    @Test
    fun `all attempts at or above the cap return the same value`() {
        val max = PinLockoutPolicy.MAX_LOCKOUT_MS
        var attempts = 17
        while (attempts <= 100) {
            assertEquals("attempts=$attempts", max, PinLockoutPolicy.lockoutMs(attempts))
            attempts++
        }
    }

    @Test
    fun `the overflow point at attempt 54 returns the cap, not Long MAX_VALUE`() {
        // Without the cap, 30000 * 2.0.pow(49) overflows to Long.MAX_VALUE
        // which, when added to a positive monotonic timestamp, wraps negative
        // and silently disables the lockout.
        val result = PinLockoutPolicy.lockoutMs(54)
        assertEquals(PinLockoutPolicy.MAX_LOCKOUT_MS, result)
        assertTrue("must not be Long.MAX_VALUE", result < Long.MAX_VALUE)
    }

    @Test
    fun `absurd attempt count returns the cap, never Long MAX_VALUE`() {
        val result = PinLockoutPolicy.lockoutMs(Int.MAX_VALUE)
        assertEquals(PinLockoutPolicy.MAX_LOCKOUT_MS, result)
        assertTrue("must not be Long.MAX_VALUE", result < Long.MAX_VALUE)
    }

    @Test
    fun `adding the cap to a monotonic timestamp never overflows`() {
        // Simulate the caller pattern: now + lockoutMs(attempts).
        // With MAX_LOCKOUT_MS as a Long, the addition must never wrap negative.
        val now = System.nanoTime() / 1_000_000 // monotonic-like timestamp
        val deadline = now + PinLockoutPolicy.lockoutMs(54)
        assertTrue("deadline must be positive", deadline > 0)
        assertTrue("deadline must be after now", deadline > now)
    }

    @Test
    fun `negative or absurd attempt counts never lock or misbehave`() {
        assertEquals(0L, PinLockoutPolicy.lockoutMs(-1))
        assertEquals(0L, PinLockoutPolicy.lockoutMs(Int.MIN_VALUE))
    }

    @Test
    fun `the shared constants are used consistently across every entry point`() {
        assertEquals(5, PinLockoutPolicy.MAX_ATTEMPTS)
        assertEquals(30_000L, PinLockoutPolicy.BASE_LOCKOUT_MS)
        assertEquals(24 * 60 * 60 * 1000L, PinLockoutPolicy.MAX_LOCKOUT_MS)
    }
}
