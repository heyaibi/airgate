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

package com.airgate.defense

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.dhizuku.DhizukuManager
import com.airgate.engine.ThreatEngine
import java.security.MessageDigest

open class SelfDefenseManager(
    private val context: Context,
    private val dhizukuManager: DhizukuManager,
    private val threatEngine: ThreatEngine,
    internal val expectedSignatureHash: String? = null,
    private val repository: SecurityStateRepository = SecurityStateRepository(context)
) {

    companion object {
        /**
         * Consecutive Dhizuku availability failures that must be observed before the
         * device-protection alarm can fire. Dhizuku's binder is known to be flaky at
         * boot/wake (process not yet ready, binder reconnect in progress), so a single
         * transient failure must never escalate straight to the wipe path.
         */
        const val DHIzuku_FAILURE_THRESHOLD = 3
    }

    open fun checkDeviceOwnerStatus(): Boolean {
        // Dhizuku grants Device Owner privileges via Binder
        // Check if Dhizuku is available / permission granted
        val isAvailable = dhizukuManager.isDhizukuAvailable()
        if (!isAvailable) {
            val consecutiveFailures = repository.incrementDhizukuFailures()
            if (consecutiveFailures >= DHIzuku_FAILURE_THRESHOLD) {
                threatEngine.processSelfDefenseBreach(
                    "Dhizuku DO status lost or revoked"
                )
            }
            return false
        }
        repository.resetDhizukuFailures()
        return true
    }

    open fun verifyAppSignature(): Boolean {
        // No pinned hash means the check cannot establish the app's authenticity.
        // Fail closed: an unpinned build must never pass verification silently.
        if (expectedSignatureHash.isNullOrBlank()) {
            threatEngine.processSelfDefenseBreach(
                "App signature cannot be verified: no pinned signature hash configured"
            )
            return false
        }

        val currentHash = getPackageSignatureHash() ?: return false
        if (!currentHash.equals(expectedSignatureHash, ignoreCase = true)) {
            threatEngine.processSelfDefenseBreach(
                "App signature tamper detected",
                rawMetadata = mapOf("expected" to expectedSignatureHash, "found" to currentHash)
            )
            return false
        }
        return true
    }

    open fun getPackageSignatureHash(): String? {
        return try {
            val pm = context.packageManager
            val packageName = context.packageName
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (signatures.isNullOrEmpty()) return null
            // Pin ALL signers (covers multi-signer / signing-certificate-rotation configs),
            // not just the first one. Deterministic order: sort certs then hash once.
            val canonicalBytes = signatures
                .map { it.toByteArray() }
                .sortedBy { bytes -> bytes.joinToString("") { "%02x".format(it) } }
                .flatMap { it.asIterable() }
                .toByteArray()
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(canonicalBytes)
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    open fun performSelfDefenseAudit(): Boolean {
        val doOk = checkDeviceOwnerStatus()
        val sigOk = verifyAppSignature()
        return doOk && sigOk
    }
}
