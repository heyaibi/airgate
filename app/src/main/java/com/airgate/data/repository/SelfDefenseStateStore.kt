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
import androidx.core.content.edit

/**
 * Persistence of self-defense transient-failure state: consecutive Dhizuku
 * availability failures.
 *
 * The failure counter is a read-modify-write; increments and resets are
 * serialized on [counterLock], shared by every store instance in the process so
 * concurrent components (the watchdog audit loop, broadcast receivers) cannot
 * interleave between an increment's read and its write and lose failures.
 */
internal class SelfDefenseStateStore(private val prefs: SharedPreferences) {
    companion object {
        private const val KEY_DHIZUKU_FAILURES = "dhizuku_consecutive_failures"

        /**
         * Process-wide monitor serializing the consecutive-failure counter's
         * read-modify-write across all store instances.
         */
        private val counterLock = Any()
    }

    /**
     * Consecutive Dhizuku availability failures, used to require a sustained loss
     * before the device-protection alarm can fire (transient binder flakes at boot
     * or wake must not cause an instant wipe).
     */
    fun getDhizukuConsecutiveFailures(): Int = prefs.getInt(KEY_DHIZUKU_FAILURES, 0)

    fun incrementDhizukuFailures(): Int {
        synchronized(counterLock) {
            val count = getDhizukuConsecutiveFailures() + 1
            prefs.edit { putInt(KEY_DHIZUKU_FAILURES, count) }
            return count
        }
    }

    fun resetDhizukuFailures() {
        synchronized(counterLock) {
            prefs.edit { remove(KEY_DHIZUKU_FAILURES) }
        }
    }

    /** Clears the self-defense transient-failure key. */
    fun reset() {
        synchronized(counterLock) {
            prefs.edit {
                remove(KEY_DHIZUKU_FAILURES)
            }
        }
    }
}
