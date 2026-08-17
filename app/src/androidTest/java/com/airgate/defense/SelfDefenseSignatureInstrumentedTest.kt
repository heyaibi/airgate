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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airgate.BuildConfig
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.SecurityState
import com.airgate.engine.ThreatEngine
import com.airgate.service.PostureAudit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device signature self-defense verification against the real PackageManager.
 *
 * These tests prove the verification *mechanism*, not a keystore assumption: a
 * debug-signed test APK legitimately differs from the release-pinned
 * [BuildConfig.EXPECTED_SIGNATURE_HASH], so the pinned baseline is compared to the
 * *actually installed* signature hash read at runtime.
 *
 *  1. the installed app's signature is readable on-device;
 *  2. a manager pinned to the real installed hash verifies it (returns true);
 *  3. a manager pinned to any other hash detects the mismatch (returns false);
 *  4. an unpinned manager fails closed (returns false);
 *  5. the recurring audit's default manager is pinned to the build hash, never
 *     left unpinned (the recurring no-op regression).
 *
 * Breach-firing tests use a throwaway SharedPreferences store so no real app
 * state is touched, and dry-run mode so no destructive call can ever run.
 */
@RunWith(AndroidJUnit4::class)
class SelfDefenseSignatureInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun throwawayRepository(): SecurityStateRepository {
        val prefs = context.getSharedPreferences(
            "self_defense_instrumented_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        return SecurityStateRepository(prefs)
    }

    private fun realInstalledHash(repository: SecurityStateRepository): String {
        val manager = SelfDefenseManager(
            context, DhizukuManager(context), ThreatEngine(context, repository, DhizukuManager(context)),
            expectedSignatureHash = null, repository = repository
        )
        return manager.getPackageSignatureHash() ?: ""
    }

    @Test
    fun installedSignatureHash_isReadableOnDevice() {
        val repository = throwawayRepository()

        val realHash = realInstalledHash(repository)

        assertNotNull(realHash)
        assertTrue("installed signature hash must be a 64-char hex SHA-256", realHash.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun managerPinnedToRealHash_verifiesTheInstalledApp() {
        val repository = throwawayRepository()
        val realHash = realInstalledHash(repository)
        val manager = SelfDefenseManager(
            context, DhizukuManager(context), ThreatEngine(context, repository, DhizukuManager(context)),
            expectedSignatureHash = realHash, repository = repository
        )

        assertTrue(
            "a manager pinned to the real installed hash must verify it",
            manager.verifyAppSignature()
        )
    }

    @Test
    fun mismatchedPinnedHash_detectsTamperOnDevice() {
        val repository = throwawayRepository().also {
            it.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
            it.saveConfig(
                AppConfig(
                    isEnabled = false,
                    dryRunMode = true,
                    graceWindowSeconds = 0,
                    deviceProtectionAlarmEnabled = false
                )
            )
        }
        val realHash = realInstalledHash(repository)
        val wrongHash = if (realHash.startsWith("0")) "1".repeat(64) else "0".repeat(64)
        val manager = SelfDefenseManager(
            context, DhizukuManager(context), ThreatEngine(context, repository, DhizukuManager(context)),
            expectedSignatureHash = wrongHash, repository = repository
        )

        assertFalse("a wrong pinned hash must fail verification", manager.verifyAppSignature())
        assertEquals(
            "the tamper breach must route through the self-tamper tier even with posture alarms disabled",
            SecurityState.WIPING,
            repository.getSecurityState()
        )
    }

    @Test
    fun unpinnedManager_failsClosedOnDevice() {
        val repository = throwawayRepository().also {
            it.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
            it.saveConfig(
                AppConfig(
                    isEnabled = true,
                    dryRunMode = true,
                    graceWindowSeconds = 0,
                    deviceProtectionAlarmEnabled = true
                )
            )
        }
        val manager = SelfDefenseManager(
            context, DhizukuManager(context), ThreatEngine(context, repository, DhizukuManager(context)),
            expectedSignatureHash = null, repository = repository
        )

        assertFalse(
            "an unpinned manager must fail closed, never pass silently",
            manager.verifyAppSignature()
        )
        // Fail-closed counts as a tamper condition: it must route through the
        // self-tamper tier in dry-run, not remain silently compliant.
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun postureAudit_defaultManager_isPinnedToBuildConfigHash() {
        val audit = PostureAudit(context)

        // The recurring audit's default manager must be pinned to the build hash,
        // never left unpinned (which made the periodic check a silent no-op).
        assertEquals(
            BuildConfig.EXPECTED_SIGNATURE_HASH,
            audit.selfDefenseManager.expectedSignatureHash
        )
        assertFalse(audit.selfDefenseManager.expectedSignatureHash.isNullOrBlank())
    }

    @Test
    fun buildConfigPinnedHash_matchesTheInstalledAppsSignature() {
        // The debug APK is signed with the debug keystore; the fingerprint baked
        // into BuildConfig for the debug build type must equal the hash of the
        // keystore that actually signed this installed APK. A mismatch here means
        // every debug build falsely trips its own tamper detection.
        val repository = throwawayRepository()
        val installedHash = realInstalledHash(repository)

        assertEquals(
            "debug build must pin the hash of the key that actually signed it",
            BuildConfig.EXPECTED_SIGNATURE_HASH,
            installedHash
        )
    }

    @Test
    fun defaultManager_pinnedToBuildConfigHash_verifiesTheInstalledApp() {
        val repository = throwawayRepository()
        val manager = SelfDefenseManager(
            context, DhizukuManager(context), ThreatEngine(context, repository, DhizukuManager(context)),
            expectedSignatureHash = BuildConfig.EXPECTED_SIGNATURE_HASH, repository = repository
        )

        assertTrue(
            "the default manager (pinned to the build hash) must verify the installed app",
            manager.verifyAppSignature()
        )
    }

    @Test
    fun buildConfigPinnedHash_isWellFormed() {
        val hash = BuildConfig.EXPECTED_SIGNATURE_HASH
        assertFalse(hash.isBlank())
        assertTrue(
            "EXPECTED_SIGNATURE_HASH must be a 64-hex SHA-256, never an empty sentinel",
            hash.matches(Regex("[0-9a-f]{64}"))
        )
    }
}
