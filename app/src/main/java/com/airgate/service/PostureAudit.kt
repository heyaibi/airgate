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

package com.airgate.service

import android.content.Context
import com.airgate.BuildConfig
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.defense.SelfDefenseManager
import com.airgate.detector.SignalListener
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.ViolationType
import com.airgate.domain.model.BreachEvent
import com.airgate.engine.ThreatEngine
import com.airgate.policy.DevicePolicyEnforcer
import java.util.UUID

class PostureAudit(
    private val context: Context,
    private val repository: SecurityStateRepository = SecurityStateRepository(context),
    private val dhizukuManager: DhizukuManager = DhizukuManager(context),
    private val threatEngine: ThreatEngine = ThreatEngine(context, repository, dhizukuManager),
    private val policyEnforcer: DevicePolicyEnforcer = DevicePolicyEnforcer(context, dhizukuManager),
    expectedSignatureHash: String? = BuildConfig.EXPECTED_SIGNATURE_HASH,
    internal val selfDefenseManager: SelfDefenseManager = SelfDefenseManager(
        context, dhizukuManager, threatEngine, expectedSignatureHash, repository
    )
) : SignalListener {

    /**
     * Consumes and escalates any latched protected-state tamper without running
     * the rest of the audit (policy re-assertion, restriction audit, self-defense
     * signature check). This is the always-on tamper leg: the watchdog's periodic
     * loop calls it whether or not the watchdog is enabled, so a paused monitor
     * still detects tampering with its own persisted state — the tamper circuits
     * stay awake while the intrusion zones sleep. Returns true when a tamper was
     * processed.
     *
     * In addition to the latched store flag (config / security-state / streak
     * blobs), the leg actively verifies the Armed PIN credential: a configured
     * but unreadable PIN is tampering with the monitor's own credential state
     * and must escalate exactly like any other protected-state tamper, never be
     * left as a silent brick.
     */
    fun checkTamperOnly(): Boolean {
        val stateTampered = processPendingStateTamper()
        val pinTampered = checkPinCredentialTamper()
        return stateTampered || pinTampered
    }

    fun executeCheck(): Boolean {
        val config = repository.getConfig()

        // 0. If any protected setting failed to verify/decrypt since the last
        //    check, treat it as tampering with persisted state before trusting
        //    anything else. This runs ahead of the enabled gate on purpose: a
        //    tampered value can itself flip config.isEnabled to its decrypt
        //    default (false), so gating the tamper response behind the enabled
        //    flag would let the tamper silence itself. The response still
        //    respects the device-protection alarm toggle downstream.
        processPendingStateTamper()

        if (!config.isEnabled) {
            return false
        }

        // 1. Re-assert all policies FIRST so a transient gap (e.g. a Dhizuku binder
        //    flake at boot/wake) is repaired before anything is audited. Verifying
        //    before enforcing would raise a false "Device Protection Bypassed" on
        //    every such blip, which is the wake-up false alarm this ordering addresses.
        //    Airplane mode is enforced separately to avoid coupling it to the
        //    debugging toggle.
        policyEnforcer.enforceAirplaneMode(config)
        policyEnforcer.enforceAllPolicies(config)

        // 2. Audit active user restrictions
        val activeRestrictions = policyEnforcer.verifyActiveRestrictions(config)
        val missingRestrictions = activeRestrictions.filterValues { !it }
        if (missingRestrictions.isNotEmpty()) {
            onBreachDetected(
                BreachEvent(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    violationType = ViolationType.DO_RESTRICTION_MISSING,
                    tier = ViolationType.DO_RESTRICTION_MISSING.defaultTier,
                    weight = ViolationType.DO_RESTRICTION_MISSING.defaultWeight,
                    rawMetadata = mapOf("missing" to missingRestrictions.keys.joinToString(","))
                )
            )
        }

        // 3. Perform Self-Defense audit (DO status & signature check)
        selfDefenseManager.performSelfDefenseAudit()

        return true
    }

    override fun onBreachDetected(event: BreachEvent) {
        threatEngine.processBreach(event)
    }

    private fun processPendingStateTamper(): Boolean {
        if (!repository.consumeStateTamperFlag()) return false
        threatEngine.processStateTamperBreach(
            reason = "Protected state failed to decrypt (tamper)"
        )
        return true
    }

    /**
     * Escalates a configured-but-unreadable Armed PIN as tampering with the
     * monitor's credential state.
     *
     * A PIN whose material was set but can no longer be read is indistinguishable
     * from an attacker corrupting the credential blob, and it is the state that
     * bricks app entry: it must ride the always-on tamper leg and escalate through
     * the same [threatEngine.processStateTamperBreach] path as any other
     * protected-state tamper. The failed read latches the store's tamper flag;
     * consuming it here keeps the next cycle's [processPendingStateTamper] from
     * double-firing the same read.
     */
    private fun checkPinCredentialTamper(): Boolean {
        if (!repository.isPinSet()) return false
        if (repository.isPinUsable()) return false
        repository.consumeStateTamperFlag()
        threatEngine.processStateTamperBreach(
            reason = "Armed PIN credential failed to decrypt (tamper)"
        )
        return true
    }
}
