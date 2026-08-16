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
import org.junit.Test

class PinLockoutPolicyTest {

    @Test
    fun `no lockout below the attempt threshold`() {
        assertEquals(0L, PinLockoutPolicy.lockoutMs(0))
        assertEquals(0L, PinLockoutPolicy.lockoutMs(1))
        assertEquals(0L, PinLockoutPolicy.lockoutMs(4))
    }

    @Test
    fun `the attempt at the threshold starts the base lockout`() {
        assertEquals(30_000L, PinLockoutPolicy.lockoutMs(5))
    }

    @Test
    fun `each further failure doubles the lockout`() {
        assertEquals(60_000L, PinLockoutPolicy.lockoutMs(6))
        assertEquals(120_000L, PinLockoutPolicy.lockoutMs(7))
        assertEquals(240_000L, PinLockoutPolicy.lockoutMs(8))
        assertEquals(480_000L, PinLockoutPolicy.lockoutMs(9))
    }

    @Test
    fun `exponential growth continues without shrinking`() {
        var previous = PinLockoutPolicy.lockoutMs(5)
        for (attempts in 6..25) {
            val current = PinLockoutPolicy.lockoutMs(attempts)
            assertEquals("attempts=$attempts", previous * 2L, current)
            previous = current
        }
        // 2^20 * 30_000 ms ≈ 9 days after 25 consecutive failures.
        assertEquals(30_000L * (1L shl 20), previous)
    }

    @Test
    fun `negative or absurd attempt counts never lock or misbehave`() {
        assertEquals(0L, PinLockoutPolicy.lockoutMs(-1))
        assertEquals(0L, PinLockoutPolicy.lockoutMs(Int.MIN_VALUE))
        // The threshold is the only gate: any count at or above it locks. An
        // absurd count saturates the duration at the Long ceiling (never wraps
        // negative within the policy itself).
        assertEquals(Long.MAX_VALUE, PinLockoutPolicy.lockoutMs(Int.MAX_VALUE))
    }

    @Test
    fun `the shared constants are used consistently across every entry point`() {
        assertEquals(5, PinLockoutPolicy.MAX_ATTEMPTS)
        assertEquals(30_000L, PinLockoutPolicy.BASE_LOCKOUT_MS)
    }
}
