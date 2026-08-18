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

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.airgate.data.crypto.PrefsCrypto
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.PendingAlarm
import com.airgate.domain.model.SecurityState
import com.airgate.domain.model.ViolationType
import kotlinx.coroutines.flow.StateFlow

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
    private val bluetoothConnectAllowedProvider: () -> Boolean = { true },
    private val exactAlarmAllowedProvider: () -> Boolean = { true },
    elapsedRealtimeProvider: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        null,
        { canPostAlarmNotifications(context) },
        { hasBluetoothConnectPermission(context) },
        { canScheduleExactAlarms(context) }
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

        /**
         * True when the app holds [android.Manifest.permission.BLUETOOTH_CONNECT] on
         * the platform versions that require it. On Android 12+ (S+) both receiving
         * the Bluetooth state broadcasts and reading the live adapter state are gated
         * behind this runtime permission, so a device armed without it is blind to
         * Bluetooth activity. On older versions the legacy BLUETOOTH permission
         * (install-time granted) is what applies and no runtime grant exists.
         */
        fun hasBluetoothConnectPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        }

        /**
         * True when the app can schedule exact alarms on this device. On Android
         * 12+ (S) this requires the SCHEDULE_EXACT_ALARM special access (the
         * "Alarms & reminders" toggle in Settings), which is denied by default on
         * fresh Android 13+ installs; below S exact alarms are always available.
         * A missing AlarmManager is treated as unavailable so callers fail closed.
         */
        fun canScheduleExactAlarms(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return false
            return alarmManager.canScheduleExactAlarms()
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

    /**
     * Persists the complete PIN credential as one atomic, versioned record.
     *
     * @return true when the credential was durably persisted; false when the
     *   write was refused or failed, in which case the previous credential (if
     *   any) remains usable.
     */
    fun savePin(pinHash: ByteArray, salt: ByteArray, iterations: Int, algorithm: String): Boolean =
        pinStore.savePin(pinHash, salt, iterations, algorithm)

    fun getPinData(): PinData? = pinStore.getPinData()

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

    /**
     * Process-wide observable of the current [SecurityState]. The watchdog
     * service, audit loop, schedulers, and receivers each build their own
     * repository instance, so the flow is shared across instances: a breach
     * that flips the state to WIPING in the background is pushed to every
     * collector immediately, without waiting for a lifecycle event or a
     * re-read. It is updated on every write and re-synced on every read, so it
     * always reflects the persisted value.
     */
    val securityStateFlow: StateFlow<SecurityState> = EnforcementStateStore.securityStateFlow

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
     * True when the app can currently read the live Bluetooth state on the platform
     * versions that require the grant (BLUETOOTH_CONNECT, Android 12+). Arming
     * without it is refused: a device armed while Bluetooth detection cannot work
     * would be silently blind to a core air-gap signal.
     */
    fun isBluetoothConnectAllowed(): Boolean = bluetoothConnectAllowedProvider()

    /**
     * True when the app currently holds the SCHEDULE_EXACT_ALARM special access
     * (the "Alarms & reminders" toggle) on the platform versions that require it.
     * The precise grace-wipe countdown depends on an exact alarm, so arming
     * without it is refused, and a countdown that loses it mid-flight fails
     * closed to an immediate wipe rather than running unguaranteed.
     */
    fun canScheduleExactAlarms(): Boolean = exactAlarmAllowedProvider()

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
     * The same transition-only rule applies to Bluetooth detection: arming requires
     * the Bluetooth state to be readable (BLUETOOTH_CONNECT on Android 12+), so no
     * arming path can arm a device that is blind to Bluetooth activity.
     *
     * The same transition-only rule applies to exact alarms: arming requires the
     * SCHEDULE_EXACT_ALARM special access ("Alarms & reminders" on Android 12+),
     * so no arming path can arm a precise wipe countdown that the platform could
     * not fire on time. An already-armed device stays armed if the access is
     * later revoked; the countdown reconciliation fails closed when that happens.
     *
     * Disabling is always allowed. Returns the effective config (which may differ
     * from the requested one).
     */
    fun saveConfig(config: AppConfig): AppConfig {
        val enablingNow = config.isEnabled && !configStore.getConfig().isEnabled
        val effective = when {
            config.isEnabled && !isPinUsable() -> config.copy(isEnabled = false)
            enablingNow && !notificationsAllowedProvider() -> config.copy(isEnabled = false)
            enablingNow && !bluetoothConnectAllowedProvider() -> config.copy(isEnabled = false)
            enablingNow && !exactAlarmAllowedProvider() -> config.copy(isEnabled = false)
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
