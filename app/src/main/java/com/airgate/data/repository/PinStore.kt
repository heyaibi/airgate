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
import com.airgate.data.crypto.PinManager
import java.util.Base64

/**
 * PIN credential material returned by [PinStore.getPinData].
 *
 * @property hash the derived key bytes
 * @property salt the per-install salt
 * @property iterations the PBKDF2 iteration count used to produce [hash]
 * @property algorithm the PBKDF2 algorithm name used to produce [hash]
 */
data class PinData(
    val hash: ByteArray,
    val salt: ByteArray,
    val iterations: Int,
    val algorithm: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PinData) return false
        return hash.contentEquals(other.hash) &&
            salt.contentEquals(other.salt) &&
            iterations == other.iterations &&
            algorithm == other.algorithm
    }

    override fun hashCode(): Int {
        var result = hash.contentHashCode()
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + iterations
        result = 31 * result + algorithm.hashCode()
        return result
    }
}

/**
 * Persistence of PIN credential material and its lockout state (failed-attempt
 * counter and exponential-backoff lockout deadline).
 *
 * The failed-attempt counter is a read-modify-write; its increments, resets, and
 * the lockout-deadline writes that accompany them are serialized on
 * [lockoutLock], shared by every store instance in the process. Wrong-PIN
 * attempts are entered from the lock screen and the verify dialog, which can
 * race a concurrent reset, so a lost update here would silently undercount
 * attempts and widen the brute-force window.
 *
 * The lockout deadline is recorded on the [MonotonicClock] timeline, not the
 * wall clock: an attacker who rolls the device clock backward must not be able
 * to clear the lockout, and a reboot must not reset it either (a power cycle is
 * exactly how an attacker would otherwise start a fresh set of attempts).
 */
internal class PinStore(
    private val prefs: SharedPreferences,
    private val store: ProtectedPrefsStore,
    private val clock: MonotonicClock
) {
    companion object {
        /**
         * Single key holding the complete PIN credential as one encrypted,
         * versioned blob. Keeping the whole credential under one key is what
         * makes a save atomic: a crash or refused write can leave either the
         * previous complete record or the new one, never a torn hash-salt pair.
         */
        private const val KEY_PIN_RECORD = "pin_record"

        /** Format version prefix of the decrypted record blob. */
        private const val RECORD_VERSION = "v1"

        /** Record layout: v1:<salt>:<iterations>:<algorithm>:<hash>. */
        private const val RECORD_FIELDS = 5

        // Legacy keys from installs predating the single-record format. They are
        // still read so an upgrade never bricks an existing credential; once a
        // save writes the record, the record is the only source of truth.
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_ITERATIONS = "pin_iterations"
        private const val KEY_PIN_ALGORITHM = "pin_algorithm"
        private const val KEY_PIN_FAILED_ATTEMPTS = "pin_failed_attempts"
        private const val KEY_PIN_LOCKOUT_UNTIL = "pin_lockout_until"

        /**
         * Process-wide monitor serializing the failed-attempt counter's
         * read-modify-write and the lockout-state transitions (reset, deadline
         * write) across all store instances. The PIN record is a single atomic
         * write, not a counter, so it is not covered by this lock.
         */
        private val lockoutLock = Any()
    }

    fun isPinSet(): Boolean {
        if (prefs.contains(KEY_PIN_RECORD)) return true
        return prefs.contains(KEY_PIN_HASH) && prefs.contains(KEY_PIN_SALT)
    }

    /**
     * Persists the complete PIN credential as one atomic, versioned record.
     *
     * The record is built and validated in memory first, then committed in a
     * single synchronous [ProtectedPrefsStore.protectedPutAll] write whose
     * Boolean is the real durable signal. When the write is refused or fails,
     * nothing is persisted and the previous credential remains fully usable —
     * a crash or keystore failure can never leave a half-written credential.
     *
     * @return true when the record was durably persisted; false when the record
     *   was refused (invalid fields, no usable crypto, or a failed commit).
     */
    fun savePin(pinHash: ByteArray, salt: ByteArray, iterations: Int, algorithm: String): Boolean {
        val record = buildRecord(pinHash, salt, iterations, algorithm) ?: return false
        return store.protectedPutAll(listOf(KEY_PIN_RECORD to record))
    }

    fun getPinData(): PinData? {
        if (prefs.contains(KEY_PIN_RECORD)) {
            // The record is the source of truth when present. A present-but-
            // unreadable record is tamper/corruption and fails closed: it never
            // falls through to a legacy credential, so a downgrade cannot mask
            // a corrupted record.
            val record = store.readProtectedValueOrNull(KEY_PIN_RECORD) ?: return null
            return parseRecord(record)
        }
        val hashB64 = store.unprotectString(KEY_PIN_HASH, prefs.getString(KEY_PIN_HASH, null) ?: return null, "")
        val saltB64 = store.unprotectString(KEY_PIN_SALT, prefs.getString(KEY_PIN_SALT, null) ?: return null, "")
        if (hashB64.isEmpty() || saltB64.isEmpty()) return null
        return try {
            val hash = Base64.getDecoder().decode(hashB64)
            val salt = Base64.getDecoder().decode(saltB64)
            val iterations = prefs.getInt(KEY_PIN_ITERATIONS, PinManager.DEFAULT_ITERATIONS)
            val algorithm = prefs.getString(KEY_PIN_ALGORITHM, PinManager.DEFAULT_ALGORITHM)
                ?: PinManager.DEFAULT_ALGORITHM
            PinData(hash, salt, iterations, algorithm)
        } catch (e: Exception) {
            null
        }
    }

    fun getPinFailedAttempts(): Int = prefs.getInt(KEY_PIN_FAILED_ATTEMPTS, 0)

    fun incrementPinFailedAttempts(): Int {
        synchronized(lockoutLock) {
            val count = getPinFailedAttempts() + 1
            prefs.edit { putInt(KEY_PIN_FAILED_ATTEMPTS, count) }
            return count
        }
    }

    fun resetPinFailedAttempts() {
        synchronized(lockoutLock) {
            prefs.edit {
                remove(KEY_PIN_FAILED_ATTEMPTS)
                remove(KEY_PIN_LOCKOUT_UNTIL)
            }
        }
    }

    fun getPinLockoutUntil(): Long = prefs.getLong(KEY_PIN_LOCKOUT_UNTIL, 0L)

    /**
     * Records the lockout deadline on the monotonic timeline. [deadline] must be
     * an absolute [MonotonicClock.now] value (callers compute it as
     * `clock.now() + lockoutMs`), never a wall-clock timestamp — a mixed clock
     * would let a rolled-back wall clock defeat the lockout.
     *
     * Persisting the clock anchor alongside the deadline is what makes the
     * deadline survive a reboot: after the elapsed clock resets, the anchor
     * carries the timeline forward instead of restarting it at zero.
     */
    fun setPinLockoutUntil(deadline: Long) {
        synchronized(lockoutLock) {
            if (deadline > 0L) clock.persistNow()
            prefs.edit { putLong(KEY_PIN_LOCKOUT_UNTIL, deadline) }
        }
    }

    /**
     * Milliseconds remaining until the lockout expires, on the monotonic
     * timeline. Returns 0 when there is no active lockout (no deadline recorded,
     * or the deadline has already passed). Never negative.
     */
    fun getPinLockoutRemainingMs(): Long {
        val deadline = getPinLockoutUntil()
        if (deadline <= 0L) return 0L
        return (deadline - clock.now()).coerceAtLeast(0L)
    }

    /**
     * Serializes a credential into the versioned record form, refusing a record
     * that could never verify a PIN (empty salt/hash, no algorithm, non-positive
     * iteration count). Returns null for such a credential so nothing invalid is
     * ever committed.
     */
    private fun buildRecord(pinHash: ByteArray, salt: ByteArray, iterations: Int, algorithm: String): String? {
        if (pinHash.isEmpty() || salt.isEmpty() || iterations <= 0 || algorithm.isEmpty()) return null
        return listOf(
            RECORD_VERSION,
            Base64.getEncoder().encodeToString(salt),
            iterations.toString(),
            Base64.getEncoder().encodeToString(algorithm.toByteArray(Charsets.UTF_8)),
            Base64.getEncoder().encodeToString(pinHash)
        ).joinToString(":")
    }

    /**
     * Parses a versioned record into its credential, returning null for a
     * malformed, empty, or unsupported record. Every field is base64 (or a
     * plain integer), so ':' is never a field delimiter.
     */
    private fun parseRecord(record: String): PinData? {
        val parts = record.split(':')
        if (parts.size != RECORD_FIELDS || parts[0] != RECORD_VERSION) return null
        val salt = runCatching { Base64.getDecoder().decode(parts[1]) }.getOrNull() ?: return null
        val iterations = parts[2].toIntOrNull() ?: return null
        val algorithm = runCatching {
            String(Base64.getDecoder().decode(parts[3]), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val hash = runCatching { Base64.getDecoder().decode(parts[4]) }.getOrNull() ?: return null
        if (salt.isEmpty() || hash.isEmpty() || algorithm.isEmpty() || iterations <= 0) return null
        return PinData(hash, salt, iterations, algorithm)
    }
}
