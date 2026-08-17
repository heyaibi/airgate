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

import kotlin.math.pow

/**
 * Exponential-backoff lockout policy shared by every PIN entry point (the
 * unlock screen and the verification dialog) so the two can never drift apart.
 *
 * The lockout activates after [MAX_ATTEMPTS] consecutive failures and doubles
 * in length with each further failure: 30s, 1m, 2m, ...
 */
object PinLockoutPolicy {

    const val MAX_ATTEMPTS = 5
    const val BASE_LOCKOUT_MS = 30_000L

    /**
     * Upper bound on the lockout duration. Matches the Android CDD §9.11
     * requirement that exponential backoff on lock-screen authentication must
     * reach at least 24 hours per attempt beyond 150 failures. Capping here
     * also prevents a Long overflow: without a cap, the unchecked double
     * multiplication produces `Long.MAX_VALUE` which, when added to a
     * positive monotonic timestamp, wraps negative and silently disables
     * the lockout.
     */
    const val MAX_LOCKOUT_MS = 24 * 60 * 60 * 1000L

    /**
     * The lockout duration for a given total number of consecutive failed
     * attempts, or 0 when the threshold has not been reached yet.
     */
    fun lockoutMs(attempts: Int): Long {
        if (attempts < MAX_ATTEMPTS) return 0L
        val exponent = (attempts - MAX_ATTEMPTS).coerceAtLeast(0)
        return (BASE_LOCKOUT_MS * 2.0.pow(exponent)).toLong().coerceAtMost(MAX_LOCKOUT_MS)
    }
}
