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
import android.content.SharedPreferences
import androidx.core.app.NotificationManagerCompat
import com.airgate.data.crypto.PrefsCrypto
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.PendingAlarm
import com.airgate.domain.model.SecurityState
import com.airgate.domain.model.ViolationType

/**
 * Facade over the shared prefs-backed stores. Each responsibility lives in its own
 * store:
 *  - [ProtectedPrefsStore]: AndroidKeyStore-backed typed prefs access + tamper flag
 *  - [PinStore]: PIN credential material and lockout state
 *  - [EnforcementStateStore]: SecurityState, threat-score streak, pending-alarm marker
 *  - [AppConfigStore]: AppConfig persistence
 *  - [ViolationTracker]: per-violation-type breach scoring and alert rate limiting
 *  - [SelfDefenseStateStore]: Dhizuku transient-failure state
 */
class SecurityStateRepository(
    private val prefs: SharedPreferences,
    crypto: PrefsCrypto? = null,
    private val notificationsAllowedProvider: () -> Boolean = { true },
    elapsedRealtimeProvider: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        null,
        { canPostAlarmNotifications(context) }
    )

    companion object {
        private const val PREFS_NAME = "airgate_secure_prefs"
        private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L

        /**
         * The alarm's full-screen notification path is usable only when the app can
         * post notifications AND can send full-screen intents. On Android 14+ the
         * full-screen-intent permission is independently revocable (it is not
         * reflected by [android.app.NotificationManager.areNotificationsEnabled]),
         * so arming must require both.
         */
        fun canPostAlarmNotifications(context: Context): Boolean {
            val compat = NotificationManagerCompat.from(context)
            return compat.areNotificationsEnabled() && compat.canUseFullScreenIntent()
        }
    }

    private val store = ProtectedPrefsStore(prefs, crypto)
    private val clock = MonotonicClock(prefs, elapsedRealtimeProvider)
    private val pinStore = PinStore(prefs, store, clock)
    private val enforcementStateStore = EnforcementStateStore(prefs, store)
    private val configStore = AppConfigStore(prefs, store)
    private val violationTracker = ViolationTracker(prefs) { enforcementStateStore.getStreak() }
    private val selfDefenseStateStore = SelfDefenseStateStore(prefs)

    fun consumeStateTamperFlag(): Boolean = store.consumeTamperFlag()

    // --- PIN credentials & lockout ---

    fun isPinSet(): Boolean = pinStore.isPinSet()

    /**
     * True when an Armed PIN is configured AND its stored material can actually
     * be read (decoded/decrypted). Arming the watchdog requires a usable PIN,
     * not merely a present one: a present-but-unreadable PIN would leave the
     * owner able to arm the device but never able to pass the disarm gates.
     */
    fun isPinUsable(): Boolean {
        if (!isPinSet()) return false
        return getPinData() != null
    }

    fun savePin(pinHash: ByteArray, salt: ByteArray) = pinStore.savePin(pinHash, salt)

    fun getPinData(): Pair<ByteArray, ByteArray>? = pinStore.getPinData()

    fun getPinFailedAttempts(): Int = pinStore.getPinFailedAttempts()

    fun incrementPinFailedAttempts(): Int = pinStore.incrementPinFailedAttempts()

    fun resetPinFailedAttempts() = pinStore.resetPinFailedAttempts()

    /**
     * The current reading of the persistent monotonic clock (reboot-surviving,
     * immune to wall-clock changes). All lockout deadline comparisons must use
     * this clock, never `System.currentTimeMillis()`.
     */
    fun getMonotonicNow(): Long = clock.now()

    /**
     * An absolute lockout deadline on the [getMonotonicNow] timeline, or 0 when
     * no lockout is recorded. Callers combine these: set a deadline with
     * `getMonotonicNow() + PinLockoutPolicy.lockoutMs(attempts)`.
     */
    fun getPinLockoutUntil(): Long = pinStore.getPinLockoutUntil()

    fun setPinLockoutUntil(deadline: Long) = pinStore.setPinLockoutUntil(deadline)

    /** Milliseconds remaining until the lockout expires, or 0 when not locked. */
    fun getPinLockoutRemainingMs(): Long = pinStore.getPinLockoutRemainingMs()

    // --- Enforcement state ---

    fun getSecurityState(): SecurityState = enforcementStateStore.getSecurityState()

    fun setSecurityState(state: SecurityState) = enforcementStateStore.setSecurityState(state)

    /**
     * The absolute monotonic deadline of a scheduled grace wipe, or 0 when none
     * is pending. Persisted alongside the clock anchor so a countdown interrupted
     * by a reboot can be reconciled: the remaining time survives the reboot and
     * the wipe is either re-armed or executed once the deadline has passed.
     */
    fun getWipeDeadline(): Long = enforcementStateStore.getWipeDeadline()

    fun setWipeDeadline(deadline: Long) {
        if (deadline > 0L) clock.persistNow()
        enforcementStateStore.setWipeDeadline(deadline)
    }

    /** Milliseconds remaining until a scheduled wipe fires, or 0 when none is pending. */
    fun getWipeRemainingMs(): Long {
        val deadline = getWipeDeadline()
        if (deadline <= 0L) return 0L
        return (deadline - clock.now()).coerceAtLeast(0L)
    }

    fun getStreak(): Int = enforcementStateStore.getStreak()

    fun setStreak(streak: Int) = enforcementStateStore.setStreak(streak)

    fun incrementStreak(byWeight: Int): Int = enforcementStateStore.incrementStreak(byWeight)

    fun resetStreak() {
        enforcementStateStore.setStreak(0)
        enforcementStateStore.setSecurityState(SecurityState.ARMED_COMPLIANT)
        clearPendingAlarm()
        violationTracker.reset()
        selfDefenseStateStore.reset()
    }

    // --- Acknowledged-pending-alarm marker ---

    /**
     * The last unacknowledged alarm, or null once the owner has acknowledged it
     * with the Armed PIN. This is the in-app guarantee that an alarm is never
     * entirely invisible: it is persisted independently of the notification /
     * activity surfaces and is surfaced by the dashboard until cleared.
     */
    fun getPendingAlarm(): PendingAlarm? = enforcementStateStore.getPendingAlarm()

    fun setPendingAlarm(alarm: PendingAlarm) = enforcementStateStore.setPendingAlarm(alarm)

    fun clearPendingAlarm() = enforcementStateStore.clearPendingAlarm()

    // --- App config ---

    fun getConfig(): AppConfig = configStore.getConfig()

    /**
     * True when the app is currently allowed to post the alarm's full-screen
     * notifications (the owner granted POST_NOTIFICATIONS on Android 13+ and has
     * not blocked notifications or full-screen intents in system settings). The
     * full-screen notification is the primary way a wipe countdown reaches the
     * owner, so arming without it is refused.
     */
    fun areNotificationsAllowed(): Boolean = notificationsAllowedProvider()

    /**
     * Persists config.
     *
     * The watchdog can never be enabled while the Armed PIN is missing or its
     * material cannot be read: any request with `isEnabled = true` under an
     * unusable PIN is coerced back to disabled. This is the always-on guard from
     * the PIN gate — it also disarms a device whose PIN material later becomes
     * unreadable, so an owner is never left armed-but-unable-to-disarm.
     *
     * The watchdog can only be *newly* enabled (transit from disabled to enabled)
     * while the app can post notifications; a request to enable while the alarm's
     * notification path is unavailable is coerced back to disabled, so no arming
     * path — dashboard, settings, preset, or future caller — can arm a device whose
     * alarm could be entirely silent. This check is transition-only: an already-armed
     * device stays armed if notifications are later revoked.
     *
     * Disabling is always allowed. Returns the effective config (which may differ
     * from the requested one).
     */
    fun saveConfig(config: AppConfig): AppConfig {
        val enablingNow = config.isEnabled && !configStore.getConfig().isEnabled
        val effective = when {
            config.isEnabled && !isPinUsable() -> config.copy(isEnabled = false)
            enablingNow && !notificationsAllowedProvider() -> config.copy(isEnabled = false)
            else -> config
        }
        configStore.saveConfig(effective)
        return effective
    }

    // --- Violation-type breach scoring ---

    /**
     * Records a violation-type occurrence for the audit trail. Never claims the
     * scoring-group daily point; use [claimScoringGroupPoint] for that.
     */
    fun recordVtBreach(violationType: ViolationType, windowMs: Long = ONE_DAY_MS) =
        violationTracker.recordVtBreach(violationType, windowMs)

    /**
     * Claims the violation's scoring-group daily point, returning whether the
     * point was newly claimed. Only escalation-tier (ALARM_STREAK) events call
     * this, so benign record-only events never consume the group's point.
     */
    fun claimScoringGroupPoint(violationType: ViolationType, windowMs: Long = ONE_DAY_MS): Boolean =
        violationTracker.claimScoringGroupPoint(violationType, windowMs)

    fun shouldTriggerAlarmAlert(violationType: ViolationType, maxAlerts: Int, tailMinutes: Int): Boolean =
        violationTracker.shouldTriggerAlarmAlert(violationType, maxAlerts, tailMinutes)

    fun isScoringGroupClaimedToday(scoringGroup: com.airgate.domain.model.ScoringGroup, windowMs: Long = ONE_DAY_MS): Boolean =
        violationTracker.isScoringGroupClaimedToday(scoringGroup, windowMs)

    fun getVtCount(violationType: ViolationType): Int = violationTracker.getVtCount(violationType)

    fun setVtCount(violationType: ViolationType, count: Int) = violationTracker.setVtCount(violationType, count)

    fun getAllVtCounts(): Map<ViolationType, Int> = violationTracker.getAllVtCounts()

    fun recordVtReason(violationType: ViolationType, reason: String) = violationTracker.recordVtReason(violationType, reason)

    fun getVtReason(violationType: ViolationType): String? = violationTracker.getVtReason(violationType)

    // --- Self-defense transient state ---

    fun getDhizukuConsecutiveFailures(): Int = selfDefenseStateStore.getDhizukuConsecutiveFailures()

    fun incrementDhizukuFailures(): Int = selfDefenseStateStore.incrementDhizukuFailures()

    fun resetDhizukuFailures() = selfDefenseStateStore.resetDhizukuFailures()
}
