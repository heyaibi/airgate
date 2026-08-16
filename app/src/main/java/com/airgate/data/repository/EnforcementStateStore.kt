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
import com.airgate.domain.model.PendingAlarm
import com.airgate.domain.model.SecurityState

/**
 * Persistence of the enforcement state (armed/alarm/wipe SecurityState), the
 * threat-score streak, and the acknowledged-pending-alarm marker, each with a
 * fingerprint cache where the value is hot.
 *
 * Streak and security state are read on the main thread during every composition /
 * resume; each read would otherwise trigger a keystore decrypt (binder round-trip).
 * Caching the decoded value against the raw stored string keeps those reads a plain
 * prefs lookup. The pending alarm is only read when the dashboard (re)composes, so
 * it is read straight through the protected store without a cache.
 *
 * The streak is a read-modify-write counter and every read, write, and increment
 * of it is serialized on [streakLock]. The repository is constructed independently
 * by several components that run concurrently (the watchdog service, the audit
 * loop, schedulers, broadcast receivers, and the UI), so the lock must be shared
 * by every store instance in the process rather than scoped to a single instance:
 * a per-instance lock would still let writers in different components interleave
 * between a read and its write and lose increments.
 */
internal class EnforcementStateStore(
    private val prefs: SharedPreferences,
    private val store: ProtectedPrefsStore
) {
    companion object {
        private const val KEY_SECURITY_STATE = "security_state"
        private const val KEY_STREAK = "streak"
        private const val KEY_WIPE_DEADLINE = "wipe_deadline"
        private const val KEY_PENDING_ALARM_CATEGORY = "pending_alarm_category"
        private const val KEY_PENDING_ALARM_DESCRIPTION = "pending_alarm_description"
        private const val KEY_PENDING_ALARM_TIMESTAMP = "pending_alarm_timestamp"
        private const val KEY_PENDING_ALARM_IS_COUNTDOWN = "pending_alarm_is_countdown"

        /**
         * Process-wide monitor serializing the streak's read-modify-write across
         * all store instances. Held for the entire read -> compute -> write so
         * no second thread can observe an intermediate state or clobber a write.
         * The other fields (security state, pending alarm) are single writes, not
         * counters, so they are not covered by this lock.
         */
        private val streakLock = Any()
    }

    @Volatile
    private var streakCache: Int? = null

    @Volatile
    private var streakRawCache: String? = null

    @Volatile
    private var securityStateCache: SecurityState? = null

    @Volatile
    private var securityStateRawCache: String? = null

    fun getSecurityState(): SecurityState {
        val raw = store.readRawPref(KEY_SECURITY_STATE)
        val cached = securityStateCache
        if (cached != null && securityStateRawCache == raw) {
            return cached
        }
        val state = readSecurityStateOrFailClosed(raw)
        securityStateCache = state
        securityStateRawCache = raw
        return state
    }

    /**
     * Reads the persisted security state, failing closed. An absent key is a
     * fresh install that has never been armed (compliant), but a present value
     * that cannot be verified, decrypted, or parsed is treated as tampering and
     * surfaced as an alarmed state: a monitor that cannot read its own state
     * must never report "all clear". The failed read also latches the store's
     * tamper flag so the periodic audit escalates it independently of the
     * displayed value.
     */
    private fun readSecurityStateOrFailClosed(raw: String?): SecurityState {
        if (raw == null) return SecurityState.ARMED_COMPLIANT
        val stateName = store.readProtectedValueOrNull(KEY_SECURITY_STATE) ?: return SecurityState.ALARM_ACTIVE
        return try {
            SecurityState.valueOf(stateName)
        } catch (e: Exception) {
            SecurityState.ALARM_ACTIVE
        }
    }

    fun setSecurityState(state: SecurityState) {
        store.protectedPutString(KEY_SECURITY_STATE, state.name)
        securityStateCache = state
        securityStateRawCache = store.readRawPref(KEY_SECURITY_STATE)
    }

    fun getStreak(): Int = synchronized(streakLock) {
        val raw = store.readRawPref(KEY_STREAK)
        val cached = streakCache
        if (cached != null && streakRawCache == raw) {
            return@synchronized cached
        }
        val streak = store.protectedGetInt(KEY_STREAK, 0)
        streakCache = streak
        streakRawCache = raw
        streak
    }

    fun setStreak(streak: Int) {
        synchronized(streakLock) {
            val clamped = streak.coerceAtLeast(0)
            store.protectedPutInt(KEY_STREAK, clamped)
            streakCache = clamped
            streakRawCache = store.readRawPref(KEY_STREAK)
        }
    }

    fun incrementStreak(byWeight: Int): Int = synchronized(streakLock) {
        // The whole read-modify-write stays inside the lock (reentrant, so the
        // synchronized getter/setter below do not release it): the computed value
        // is always derived from the just-read value and the write always lands
        // before another writer can run. The returned value is the value that was
        // actually persisted (negative results are clamped to zero by setStreak),
        // so callers can rely on it matching a subsequent read.
        val newStreak = getStreak() + byWeight
        val clamped = newStreak.coerceAtLeast(0)
        setStreak(clamped)
        clamped
    }

    // --- Pending-wipe deadline ---

    /**
     * The absolute monotonic deadline of a scheduled grace wipe, or 0 when no
     * wipe is pending. Recorded on the [MonotonicClock] timeline so a reboot
     * mid-countdown can be reconciled (re-armed or executed) instead of silently
     * losing the wipe. Plain prefs, like the lockout deadline: the clock anchor
     * is what carries the value across a reboot.
     */
    fun getWipeDeadline(): Long = prefs.getLong(KEY_WIPE_DEADLINE, 0L)

    fun setWipeDeadline(deadline: Long) {
        prefs.edit { putLong(KEY_WIPE_DEADLINE, deadline) }
    }

    // --- Acknowledged-pending-alarm marker ---

    /**
     * The last unacknowledged alarm raised against this device, or null when the
     * owner has acknowledged it (or no alarm has fired since). The marker is
     * independent of the live [getSecurityState]: it records that an alarm
     * transitioned the device to an alarm state and that no owner has yet
     * acknowledged it with the Armed PIN.
     */
    fun getPendingAlarm(): PendingAlarm? {
        val category = store.protectedGetString(KEY_PENDING_ALARM_CATEGORY, "")
        if (category.isBlank()) return null
        return PendingAlarm(
            category = category,
            description = store.protectedGetString(KEY_PENDING_ALARM_DESCRIPTION, ""),
            timestamp = store.protectedGetString(KEY_PENDING_ALARM_TIMESTAMP, "0").toLongOrNull() ?: 0L,
            isCountdown = store.protectedGetBoolean(KEY_PENDING_ALARM_IS_COUNTDOWN, false)
        )
    }

    fun setPendingAlarm(alarm: PendingAlarm) {
        store.protectedPutString(KEY_PENDING_ALARM_CATEGORY, alarm.category)
        store.protectedPutString(KEY_PENDING_ALARM_DESCRIPTION, alarm.description)
        store.protectedPutString(KEY_PENDING_ALARM_TIMESTAMP, alarm.timestamp.toString())
        store.protectedPutBoolean(KEY_PENDING_ALARM_IS_COUNTDOWN, alarm.isCountdown)
    }

    fun clearPendingAlarm() {
        store.removeProtected(KEY_PENDING_ALARM_CATEGORY)
        store.removeProtected(KEY_PENDING_ALARM_DESCRIPTION)
        store.removeProtected(KEY_PENDING_ALARM_TIMESTAMP)
        store.removeProtected(KEY_PENDING_ALARM_IS_COUNTDOWN)
    }
}
