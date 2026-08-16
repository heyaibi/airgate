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
import com.airgate.domain.model.ScoringGroup
import com.airgate.domain.model.ViolationType

/**
 * Tracks per-violation-type breach scoring: occurrence counts within a scoring
 * window, scoring-group daily point claims, alert notification rate limiting and
 * human-readable breach reasons.
 *
 * Every method that mutates a counter (a read-modify-write) is serialized on
 * [counterLock], which is shared by all instances in the process. Breach
 * processing runs concurrently — broadcast receivers on the main thread, the
 * audit handler thread, and the SafetyNet thread — and each component builds its
 * own repository, so a per-instance lock would not prevent those writers from
 * interleaving between a read and its write. The daily scoring-group point and
 * the alert cap are scarce, enforcement-critical resources: a lost update there
 * means a double-claimed point or a doubled alert.
 */
internal class ViolationTracker(
    private val prefs: SharedPreferences,
    private val streakProvider: () -> Int
) {
    companion object {
        private const val PREFIX_VT_TIMESTAMP = "vt_last_timestamp_"
        private const val PREFIX_VT_COUNT = "vt_count_"
        private const val PREFIX_SCORING_GROUP_DATE = "sg_date_"
        private const val PREFIX_VT_REASON = "vt_reason_"

        private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L

        private const val ALERT_COUNT_PREFIX = "alert_count_"
        private const val ALERT_TIMESTAMP_PREFIX = "alert_timestamp_"

        /**
         * Process-wide monitor serializing every counter read-modify-write in
         * this tracker. Pure reads do not take the lock: they observe a
         * committed state because each write happens entirely inside the lock.
         */
        private val counterLock = Any()
    }

    /**
     * Records the occurrence of a breach for a specific Violation Type (VT).
     * The VT count increments on every call and resets once the scoring window
     * elapses. This is a pure audit record — it never touches the scoring-group
     * daily point, which is claimed separately via [claimScoringGroupPoint] only
     * for escalation-tier events.
     */
    fun recordVtBreach(violationType: ViolationType, windowMs: Long = ONE_DAY_MS) {
        synchronized(counterLock) {
            val now = System.currentTimeMillis()
            val vtKey = violationType.name

            // Track occurrence count within the current scoring window.
            // The counter is reset when the window elapses and saturates at Int.MAX_VALUE,
            // so it can never grow without bound across many 10s polls.
            val currentCount = prefs.getInt("$PREFIX_VT_COUNT$vtKey", 0)
            val lastTimestamp = prefs.getLong("$PREFIX_VT_TIMESTAMP$vtKey", 0L)

            val editor = prefs.edit()
            val isVtDebounced = (now - lastTimestamp) < windowMs
            val newCount = if (isVtDebounced) {
                if (currentCount == Int.MAX_VALUE) Int.MAX_VALUE else currentCount + 1
            } else 1
            editor.putInt("$PREFIX_VT_COUNT$vtKey", newCount)
            if (!isVtDebounced) {
                editor.putLong("$PREFIX_VT_TIMESTAMP$vtKey", now)
            }

            editor.apply()
        }
    }

    /**
     * Claims the scoring group's single daily point for the given Violation Type.
     * Returns true only when this is the first claim from the group within the
     * scoring window (the daily point is consumed); later claims in the same
     * window return false. Only escalation-tier events call this, so a benign
     * record-only event can never spend the point that drives the wipe streak.
     */
    fun claimScoringGroupPoint(violationType: ViolationType, windowMs: Long = ONE_DAY_MS): Boolean {
        synchronized(counterLock) {
            val now = System.currentTimeMillis()
            val sgKey = violationType.scoringGroup.name
            val lastSgDate = prefs.getLong("$PREFIX_SCORING_GROUP_DATE$sgKey", 0L)
            val isSgDebounced = (now - lastSgDate) < windowMs
            if (!isSgDebounced) {
                prefs.edit().putLong("$PREFIX_SCORING_GROUP_DATE$sgKey", now).apply()
                return true
            }
            return false
        }
    }

    /**
     * Checks if the active alert count for this VT has reached notificationsPerBreach limit.
     * Returns true if alarm display is allowed, false if rate-limited.
     */
    fun shouldTriggerAlarmAlert(violationType: ViolationType, maxAlerts: Int, tailMinutes: Int): Boolean {
        synchronized(counterLock) {
            val vtKey = violationType.name
            val alertCountKey = "$ALERT_COUNT_PREFIX$vtKey"
            val alertTimestampKey = "$ALERT_TIMESTAMP_PREFIX$vtKey"
            val now = System.currentTimeMillis()

            val lastAlertTime = prefs.getLong(alertTimestampKey, 0L)
            val currentAlerts = prefs.getInt(alertCountKey, 0)
            val tailMs = tailMinutes * 60 * 1000L

            // If this is the first alert or tail window has elapsed, reset counter to 1 and fire
            if (lastAlertTime == 0L || (now - lastAlertTime) > tailMs) {
                prefs.edit {
                    putInt(alertCountKey, 1)
                    putLong(alertTimestampKey, now)
                }
                return true
            }

            // If under maxAlerts limit, increment counter and fire
            if (currentAlerts < maxAlerts) {
                prefs.edit {
                    putInt(alertCountKey, currentAlerts + 1)
                    putLong(alertTimestampKey, now)
                }
                return true
            }

            // Exceeded notificationsPerBreach cap for this breach episode
            return false
        }
    }

    fun isScoringGroupClaimedToday(scoringGroup: ScoringGroup, windowMs: Long = ONE_DAY_MS): Boolean {
        if (streakProvider() == 0) return false
        val now = System.currentTimeMillis()
        val lastSgDate = prefs.getLong("$PREFIX_SCORING_GROUP_DATE${scoringGroup.name}", 0L)
        return (now - lastSgDate) < windowMs
    }

    fun getVtCount(violationType: ViolationType): Int {
        return prefs.getInt("$PREFIX_VT_COUNT${violationType.name}", 0)
    }

    fun setVtCount(violationType: ViolationType, count: Int) {
        synchronized(counterLock) {
            prefs.edit { putInt("$PREFIX_VT_COUNT${violationType.name}", count.coerceIn(0, Int.MAX_VALUE)) }
        }
    }

    /**
     * Persists the human-readable reason for the most recent occurrence of a
     * violation type so alarm sub-causes (e.g. "Dhizuku DO status lost or
     * revoked") are diagnosable after the fact.
     * Also recorded for suppressed alarms so owners can review what was blocked.
     */
    fun recordVtReason(violationType: ViolationType, reason: String) {
        prefs.edit { putString("$PREFIX_VT_REASON${violationType.name}", reason) }
    }

    fun getVtReason(violationType: ViolationType): String? {
        return prefs.getString("$PREFIX_VT_REASON${violationType.name}", null)
    }

    fun getAllVtCounts(): Map<ViolationType, Int> {
        val map = mutableMapOf<ViolationType, Int>()
        ViolationType.values().forEach { vt ->
            val count = getVtCount(vt)
            if (count > 0) {
                map[vt] = count
            }
        }
        return map
    }

    /** Clears all scoring group timestamps, VT count records, and alert rate limiters. */
    fun reset() {
        synchronized(counterLock) {
            prefs.edit {
                ScoringGroup.values().forEach { sg ->
                    remove("$PREFIX_SCORING_GROUP_DATE${sg.name}")
                }
                ViolationType.values().forEach { vt ->
                    remove("$PREFIX_VT_COUNT${vt.name}")
                    remove("$PREFIX_VT_TIMESTAMP${vt.name}")
                    remove("$PREFIX_VT_REASON${vt.name}")
                    remove("$ALERT_COUNT_PREFIX${vt.name}")
                    remove("$ALERT_TIMESTAMP_PREFIX${vt.name}")
                }
            }
        }
    }
}
