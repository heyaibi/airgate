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

package com.airgate.engine

import android.content.Context
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.BreachEvent
import com.airgate.domain.model.PendingAlarm
import com.airgate.domain.model.ResponseTier
import com.airgate.domain.model.SecurityState
import com.airgate.domain.model.ViolationType
import com.airgate.domain.model.WipeResult
import com.airgate.policy.WipeController
import java.util.UUID

/**
 * Orchestrates breach responses. Each concern is delegated to a single-responsibility
 * collaborator:
 *  - [AlarmNotifier]: critical alarm notification + full-screen/countdown activity launches
 *  - [GraceWipeScheduler]: AlarmManager scheduling/cancellation of the delayed wipe
 *  - [ReactiveHardener]: reactive hardening (airplane mode + DO restriction re-assertion)
 *  - [WipeController]: the actual (possibly dry-run) wipe execution
 */
class ThreatEngine(
    private val context: Context,
    private val repository: SecurityStateRepository,
    private val dhizukuManager: DhizukuManager,
    private val customWindowMs: Long? = null,
    private val alarmNotifier: AlarmNotifier = AlarmNotifier(context),
    internal val graceWipeScheduler: GraceWipeScheduler = GraceWipeScheduler(context)
) {
    private enum class BreachOrigin(
        val bypassEnabledGate: Boolean,
        val bypassDeviceProtectionAlarm: Boolean
    ) {
        ORDINARY(false, false),
        SELF_DEFENSE(true, true),
        STATE_TAMPER(true, true)
    }

    private val wipeController = WipeController(context, dhizukuManager)
    private val reactiveHardener = ReactiveHardener(context, repository, dhizukuManager)

    fun processBreach(event: BreachEvent) {
        processBreach(event, BreachOrigin.ORDINARY)
    }

    private fun processBreach(event: BreachEvent, origin: BreachOrigin) {
        val config = repository.getConfig()
        if (!config.isEnabled && !origin.bypassEnabledGate) {
            // App monitoring is disabled by the user
            return
        }

        // Always persist the sub-cause so past alarms (and suppressed detections)
        // remain diagnosable. This runs before the suppression gate below so a
        // blocked detection is still recorded.
        repository.recordVtReason(
            event.violationType,
            event.rawMetadata["reason"]
                ?: event.rawMetadata["missing"]?.let { "Missing: $it" }
                ?: event.violationType.description
        )

        // Suppression gate: the device-protection posture alarm is OFF by default.
        // When disabled, the condition is still detected and its reason recorded, but
        // no alarm, hardening, streak, or wipe path runs — enforcement/self-healing
        // is handled independently by the posture audit. Self-defense routes pass
        // an explicit bypass because this setting controls ordinary posture alarms,
        // not the emergency response to losing the protection layer itself.
        val suppressed = when (event.violationType) {
            ViolationType.DO_RESTRICTION_MISSING ->
                !config.deviceProtectionAlarmEnabled && !origin.bypassDeviceProtectionAlarm
            // Debugging-domain events stay authorized while the owner deliberately
            // turns OFF "Block Debugging Features" for recovery/install. Firing on
            // them would re-run reactive hardening and accumulate points, so they
            // remain silent while the block is disabled.
            ViolationType.ADB_ENABLED_FLIP,
            ViolationType.DEVELOPER_OPTIONS_TOGGLE -> !config.blockDebuggingFeatures
            // USB DATA TRANSFER is a separate feature and is never authorized by the
            // debugging block. Power-only sessions (charger / power bank) never fire
            // these detectors; only a real data session (MTP/PTP/ADB/accessory/MIDI
            // function, host attach, tethering, ethernet) does, and that is always an
            // exfiltration vector regardless of the block setting.
            else -> false
        }
        if (suppressed) return

        // Record the VT occurrence for the audit trail on every processed event.
        // The scoring-group daily point is claimed separately and only by the
        // ALARM_STREAK branch below, so benign log-only events can never spend the
        // point that drives the wipe streak.
        if (customWindowMs != null) {
            repository.recordVtBreach(event.violationType, customWindowMs)
        } else {
            repository.recordVtBreach(event.violationType)
        }

        val canLaunchAlert = repository.shouldTriggerAlarmAlert(
            event.violationType,
            config.notificationsPerBreach,
            config.notificationTailMinutes
        )

        when (event.tier) {
            ResponseTier.LOG_ONLY -> {
                // Audit log only
            }
            ResponseTier.ALARM -> {
                reactiveHardener.harden()
                raisePendingAlarm(event.violationType.scoringGroup.displayName, event.violationType.description)
                if (canLaunchAlert) {
                    alarmNotifier.launch(event)
                }
            }
            ResponseTier.ALARM_STREAK -> {
                reactiveHardener.harden()
                // Only an ALARM_STREAK event may consume the group's daily point:
                // the first such claim in a window advances the streak, while
                // repeated claims (and claims from other tiers) leave it untouched.
                val pointClaimed = if (customWindowMs != null) {
                    repository.claimScoringGroupPoint(event.violationType, customWindowMs)
                } else {
                    repository.claimScoringGroupPoint(event.violationType)
                }
                val newStreak = if (pointClaimed) {
                    repository.incrementStreak(1)
                } else {
                    repository.getStreak()
                }

                if (newStreak >= config.wipeThreshold) {
                    executeWipeState()
                } else {
                    repository.setSecurityState(SecurityState.ALARM_ACTIVE)
                    raisePendingAlarm(event.violationType.scoringGroup.displayName, event.violationType.description)
                    if (canLaunchAlert) {
                        alarmNotifier.launch(event)
                    }
                }
            }
            ResponseTier.INSTANT_WIPE -> {
                // Self-tamper / instant-wipe tier: bypass the streak accumulation entirely.
                reactiveHardener.harden()
                executeWipeState()
            }
        }
    }

    /**
     * Routes self-defense failures (DO loss, signature tamper) through the user-selected
     * `selfTamperTier` instead of the violation type's default tier.
     */
    fun processSelfDefenseBreach(reason: String, rawMetadata: Map<String, String> = emptyMap()) {
        val config = repository.getConfig()
        val violationType = ViolationType.DO_RESTRICTION_MISSING
        processBreach(
            BreachEvent(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                violationType = violationType,
                tier = config.selfTamperTier,
                weight = violationType.defaultWeight,
                rawMetadata = rawMetadata + mapOf("reason" to reason)
            ),
            BreachOrigin.SELF_DEFENSE
        )
    }

    /**
     * Routes a protected-state tamper through the user-selected `selfTamperTier`.
     *
     * This is the monitor defending its own persisted state, so it is processed
     * even when the watchdog is disabled: a tampered protected value can itself
     * flip `config.isEnabled` to its decrypt default (false), and gating the
     * tamper response behind the enabled flag would let the tamper silence
     * itself. The emergency response bypasses the ordinary device-protection alarm
     * toggle, and all enforcement side effects (hardening, wipe) remain the same as
     * any self-tamper at the configured tier.
     */
    fun processStateTamperBreach(reason: String) {
        val config = repository.getConfig()
        val violationType = ViolationType.DO_RESTRICTION_MISSING
        processBreach(
            BreachEvent(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                violationType = violationType,
                tier = config.selfTamperTier,
                weight = violationType.defaultWeight,
                rawMetadata = mapOf("reason" to reason)
            ),
            BreachOrigin.STATE_TAMPER
        )
    }

    fun cancelPendingWipe() {
        synchronized(wipeTransitionLock) {
            repository.setSecurityState(SecurityState.ARMED_COMPLIANT)
            repository.setWipeDeadline(0L)
            graceWipeScheduler.cancel()
        }
    }

    /**
     * Reconciles a countdown that survived a process restart or reboot. The
     * wipe deadline is persisted on the monotonic clock when the countdown is
     * scheduled, so a reboot (which clears AlarmManager alarms) does not lose
     * the wipe:
     *  - if the deadline has not yet elapsed, the alarm is re-armed for the
     *    remaining time only (the absolute deadline never moves), and
     *  - if the deadline elapsed while the app was down, the wipe executes.
     *
     * A countdown with no recorded deadline (a legacy schedule) is left alone —
     * its pending alarm governs, and alarms survive process death, not reboot.
     */
    fun reconcilePendingWipe() {
        synchronized(wipeTransitionLock) {
            val config = repository.getConfig()
            if (!config.isEnabled) return
            if (repository.getSecurityState() != SecurityState.COUNTDOWN_WIPE) return
            val deadline = repository.getWipeDeadline()
            if (deadline <= 0L) return
            val remaining = repository.getWipeRemainingMs()
            if (remaining > 0L) {
                graceWipeScheduler.scheduleDelay(remaining)
            } else {
                executeWipeState(graceElapsed = true)
            }
        }
    }

    fun executeWipeState(graceElapsed: Boolean = false) {
        synchronized(wipeTransitionLock) {
            val config = repository.getConfig()
            if (!graceElapsed && config.graceWindowSeconds > 0) {
                // The wipe countdown is latched: once it is running, a further breach
                // must not re-arm it. The absolute wipe deadline stands, so repeated
                // breaches cannot postpone the wipe indefinitely. Only the deadline
                // reaching or the owner disarming moves the state.
                if (repository.getSecurityState() == SecurityState.COUNTDOWN_WIPE) return
                repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
                repository.setWipeDeadline(
                    repository.getMonotonicNow() + config.graceWindowSeconds * 1000L
                )
                graceWipeScheduler.schedule(config)
                raisePendingAlarm(
                    category = COUNTDOWN_ALARM_CATEGORY,
                    description = "A wipe is scheduled. Disarm with your Armed PIN to cancel it.",
                    isCountdown = true
                )
                alarmNotifier.launchCountdown()
            } else {
                if (graceElapsed && repository.getSecurityState() != SecurityState.COUNTDOWN_WIPE) return
                when (wipeController.executeWipe(config)) {
                    WipeResult.ACCEPTED, WipeResult.SIMULATED -> {
                        repository.setSecurityState(SecurityState.WIPING)
                        raisePendingAlarm(
                            category = WIPE_ALARM_CATEGORY,
                            description = "The device wipe executed. Production protocol would erase all data."
                        )
                    }
                    WipeResult.REJECTED -> {
                        // The platform refused the wipe: the device's data is still present,
                        // so it must never be shown as wiped. Return to the alarm state and
                        // surface the failure loudly instead of silently claiming success.
                        repository.setSecurityState(SecurityState.ALARM_ACTIVE)
                        raisePendingAlarm(
                            category = WIPE_FAILED_ALARM_CATEGORY,
                            description = "The wipe was rejected by the system; device data has not been erased."
                        )
                        alarmNotifier.launchWipeFailure()
                    }
                }
                // The scheduled wipe is no longer pending once it fired (or was refused).
                repository.setWipeDeadline(0L)
            }
        }
    }

    /**
     * Records the in-app alarm marker so the alarm is never entirely invisible:
     * persisted independently of the notification / activity surfaces, it is what
     * the dashboard surfaces to an owner who must acknowledge it with the Armed
     * PIN. It is informational — it never gates the wipe, which fires on schedule.
     */
    private fun raisePendingAlarm(category: String, description: String, isCountdown: Boolean = false) {
        repository.setPendingAlarm(
            PendingAlarm(
                category = category,
                description = description,
                timestamp = System.currentTimeMillis(),
                isCountdown = isCountdown
            )
        )
    }

    companion object {
        private val wipeTransitionLock = Any()
        const val COUNTDOWN_ALARM_CATEGORY = "WIPE COUNTDOWN"
        const val WIPE_ALARM_CATEGORY = "WIPE EXECUTED"
        const val WIPE_FAILED_ALARM_CATEGORY = "WIPE FAILED"
    }
}
