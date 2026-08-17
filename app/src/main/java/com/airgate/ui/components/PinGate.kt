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

package com.airgate.ui.components

import com.airgate.data.repository.SecurityStateRepository

/**
 * Result of resolving the armed-PIN gate for a security-sensitive action,
 * evaluated before any typed PIN is verified.
 *
 * The gate fails closed: a PIN that was never configured, or whose stored
 * material cannot be read, must never authorize an action.
 */
internal sealed class PinGateDecision {
    /** No PIN has ever been configured. The action is refused. */
    data object NoPinConfigured : PinGateDecision()

    /**
     * A PIN is configured but its stored material cannot be read or decoded
     * (tamper, corruption, keystore failure). The action is refused.
     */
    data object PinUnreadable : PinGateDecision()

    /**
     * PIN material is available; the typed PIN must be verified against it.
     *
     * @property expectedHash the stored derived key
     * @property salt the per-install salt
     * @property iterations the PBKDF2 iteration count used to produce [expectedHash]
     * @property algorithm the PBKDF2 algorithm name used to produce [expectedHash]
     */
    data class Verify(
        val expectedHash: ByteArray,
        val salt: ByteArray,
        val iterations: Int,
        val algorithm: String
    ) : PinGateDecision()
}

/**
 * Resolves the armed-PIN gate from the stored PIN material.
 *
 * Returns [PinGateDecision.NoPinConfigured] when no PIN is on file,
 * [PinGateDecision.PinUnreadable] when a PIN exists but its material cannot be
 * read (never treated as authorization), and [PinGateDecision.Verify] with the
 * stored hash/salt/iterations/algorithm when verification should proceed.
 */
internal fun resolvePinGate(repository: SecurityStateRepository): PinGateDecision {
    if (!repository.isPinSet()) return PinGateDecision.NoPinConfigured
    val pinData = repository.getPinData() ?: return PinGateDecision.PinUnreadable
    return PinGateDecision.Verify(
        expectedHash = pinData.hash,
        salt = pinData.salt,
        iterations = pinData.iterations,
        algorithm = pinData.algorithm
    )
}

/**
 * Result of an unlock-screen PIN submit, resolved before any typed PIN is
 * verified.
 *
 * The unlock screen must agree with the verify dialog: a missing or unreadable
 * PIN is never a wrong guess (it must not feed the brute-force lockout) and
 * never an authorization. Only genuinely readable PIN material is verified
 * against.
 */
internal sealed class AuthPinSubmitDecision {
    /** The typed PIN verified against the stored material; the owner is authenticated. */
    data object Unlock : AuthPinSubmitDecision()

    /** The typed PIN did not match; the owner is refused and a failure may be counted. */
    data object IncorrectPin : AuthPinSubmitDecision()

    /** No PIN is configured; the owner must set one before the app can be entered. */
    data object NoPinConfigured : AuthPinSubmitDecision()

    /** A PIN is configured but its material cannot be read; entry is blocked and nothing is counted. */
    data object PinUnreadable : AuthPinSubmitDecision()
}

/**
 * Decides what an unlock-screen submit does. Shares [resolvePinGate] with the
 * verify dialog so the two PIN entry points can never disagree. [verifyPin] is
 * injectable so the branch logic is exercised in pure-JVM tests without running
 * PBKDF2.
 */
internal fun decideAuthPinSubmit(
    repository: SecurityStateRepository,
    verifyPin: (String, ByteArray, ByteArray, Int, String) -> Boolean,
    typedPin: String
): AuthPinSubmitDecision {
    return when (val decision = resolvePinGate(repository)) {
        is PinGateDecision.NoPinConfigured -> AuthPinSubmitDecision.NoPinConfigured
        is PinGateDecision.PinUnreadable -> AuthPinSubmitDecision.PinUnreadable
        is PinGateDecision.Verify ->
            if (verifyPin(typedPin, decision.salt, decision.expectedHash, decision.iterations, decision.algorithm)) {
                AuthPinSubmitDecision.Unlock
            } else {
                AuthPinSubmitDecision.IncorrectPin
            }
    }
}
