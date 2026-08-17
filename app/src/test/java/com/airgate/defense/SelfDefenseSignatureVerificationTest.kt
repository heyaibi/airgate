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

import android.content.ContextWrapper
import com.airgate.data.crypto.JvmPrefsCrypto
import com.airgate.data.crypto.PinManager
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.dhizuku.DhizukuBinderWrapper
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.SecurityState
import com.airgate.domain.model.ViolationType
import com.airgate.engine.ThreatEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Branch coverage for [SelfDefenseManager.verifyAppSignature] and its integration
 * with the threat engine. Every outcome is exercised: a pinned hash that matches
 * (including case-insensitively), a pinned hash that mismatches, a current signature
 * that cannot be read, and the fail-closed response when no hash is pinned at all.
 */
class SelfDefenseSignatureVerificationTest {

    private class DummyContext(private val baseDir: File) : ContextWrapper(null) {
        override fun getPackageName(): String = "com.airgate"
        override fun getFilesDir(): File = baseDir
        override fun getSystemService(name: String): Any? = null
    }

    private class MockDhizukuBinder(var isGranted: Boolean = true) : DhizukuBinderWrapper {
        override fun isPermissionGranted(): Boolean = isGranted
        override fun bindUserService(componentName: android.content.ComponentName, connection: Any): Boolean = true
        override fun setGlobalSetting(admin: android.content.ComponentName, key: String, value: String): Boolean = true
        override fun addUserRestriction(admin: android.content.ComponentName, key: String): Boolean = true
        override fun clearUserRestriction(admin: android.content.ComponentName, key: String): Boolean = true
        override fun wipeDevice(flags: Int): Boolean = true
    }

    private class MockSharedPreferences : android.content.SharedPreferences {
        private val map = mutableMapOf<String, Any>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = Editor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class Editor(private val map: MutableMap<String, Any>) : android.content.SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { if (key != null && value != null) map[key] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { if (key != null) map[key] = value; return this }
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { if (key != null) map[key] = value; return this }
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { if (key != null) map[key] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { if (key != null) map[key] = value; return this }
            override fun remove(key: String?): android.content.SharedPreferences.Editor { map.remove(key); return this }
            override fun clear(): android.content.SharedPreferences.Editor { map.clear(); return this }
            override fun apply() {}
            override fun commit(): Boolean = true
        }
    }

    private lateinit var tempDir: File
    private lateinit var dummyContext: DummyContext
    private lateinit var repository: SecurityStateRepository
    private lateinit var mockBinder: MockDhizukuBinder
    private lateinit var dhizukuManager: DhizukuManager
    private lateinit var threatEngine: ThreatEngine

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "ag_selfdefense_sig_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        dummyContext = DummyContext(tempDir)
        repository = SecurityStateRepository(MockSharedPreferences(), JvmPrefsCrypto())
        // The watchdog may only be armed after a PIN is configured.
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        repository.saveConfig(
            AppConfig(
                isEnabled = true,
                dryRunMode = true,
                graceWindowSeconds = 0,
                deviceProtectionAlarmEnabled = true
            )
        )
        mockBinder = MockDhizukuBinder(isGranted = true)
        dhizukuManager = DhizukuManager(dummyContext, mockBinder)
        threatEngine = ThreatEngine(dummyContext, repository, dhizukuManager)
    }

    private fun managerWith(
        expectedHash: String?,
        currentHash: String? = null
    ): SelfDefenseManager {
        return object : SelfDefenseManager(
            dummyContext, dhizukuManager, threatEngine,
            expectedSignatureHash = expectedHash, repository = repository
        ) {
            override fun getPackageSignatureHash(): String? = currentHash
        }
    }

    // --- Pinned hash, matching current signature ---

    @Test
    fun pinnedMatchingHash_verifiesTrue_andFiresNoBreach() {
        val manager = managerWith(expectedHash = "abc123", currentHash = "abc123")

        assertTrue(manager.verifyAppSignature())
        // A passing signature check must not escalate or change state.
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
        assertEquals(null, repository.getVtReason(ViolationType.DO_RESTRICTION_MISSING))
    }

    @Test
    fun pinnedMatchingHash_isCaseInsensitive() {
        val manager = managerWith(expectedHash = "ABC123", currentHash = "abc123")

        assertTrue(manager.verifyAppSignature())
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
    }

    @Test
    fun pinnedMatchingHash_doesNotConsumeScoringPoint() {
        val manager = managerWith(expectedHash = "abc123", currentHash = "abc123")

        manager.verifyAppSignature()
        // A healthy signature check is not a breach event: no VT count is recorded.
        assertEquals(0, repository.getVtCount(ViolationType.DO_RESTRICTION_MISSING))
    }

    // --- Pinned hash, mismatching current signature (in-place re-signing) ---

    @Test
    fun pinnedMismatchingHash_fails_andEscalatesToSelfTamperTier() {
        val manager = managerWith(expectedHash = "abc123", currentHash = "0bad0bad")

        val result = manager.verifyAppSignature()
        assertFalse(result)
        // selfTamperTier defaults to INSTANT_WIPE, so a tampered signature drives
        // the device into WIPING (dry-run: no real wipe is performed).
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun pinnedMismatchingHash_RecordsTamperReason() {
        val manager = managerWith(expectedHash = "abc123", currentHash = "0bad0bad")

        manager.verifyAppSignature()
        val reason = repository.getVtReason(ViolationType.DO_RESTRICTION_MISSING)
        assertTrue(reason != null && reason.contains("signature tamper detected"))
    }

    // --- Pinned hash, current signature unreadable ---

    @Test
    fun pinnedHashWithUnreadableSignature_fails() {
        val manager = managerWith(expectedHash = "abc123", currentHash = null)

        assertFalse(manager.verifyAppSignature())
    }

    // --- Unpinned (null / empty expected hash): the recurring audit no-op bug ---

    @Test
    fun nullPinnedHash_failsClosed_firesBreach() {
        val manager = managerWith(expectedHash = null, currentHash = "abc123")

        val result = manager.verifyAppSignature()
        // Fail closed: an unpinned check must not pass silently.
        assertFalse(result)
        // And it must count as a self-defense breach, not just a false return.
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun nullPinnedHash_recordsUnpinnedReason() {
        val manager = managerWith(expectedHash = null, currentHash = "abc123")

        manager.verifyAppSignature()
        val reason = repository.getVtReason(ViolationType.DO_RESTRICTION_MISSING)
        assertTrue(reason != null && reason.contains("no pinned signature hash"))
    }

    @Test
    fun emptyPinnedHash_failsClosed_firesBreach() {
        val manager = managerWith(expectedHash = "", currentHash = "abc123")

        val result = manager.verifyAppSignature()
        assertFalse(result)
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun blankPinnedHash_failsClosed_firesBreach() {
        val manager = managerWith(expectedHash = "   ", currentHash = "abc123")

        val result = manager.verifyAppSignature()
        assertFalse(result)
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun nullPinnedHash_failsClosed_evenWhenSignatureUnreadable() {
        val manager = managerWith(expectedHash = null, currentHash = null)

        assertFalse(manager.verifyAppSignature())
    }

    // --- performSelfDefenseAudit composition ---

    @Test
    fun fullAudit_passesOnlyWhenDoStatusAndSignatureBothOk() {
        val ok = managerWith(expectedHash = "abc123", currentHash = "abc123")
        assertTrue(ok.performSelfDefenseAudit())
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
    }

    @Test
    fun fullAudit_failsWhenSignatureTampered_butDoStillGranted() {
        val tampered = managerWith(expectedHash = "abc123", currentHash = "0bad0bad")
        assertFalse(tampered.performSelfDefenseAudit())
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun fullAudit_failsWhenDoLost_butSignatureOk() {
        val flaky = MockDhizukuBinder(isGranted = false)
        val flakyDhizuku = DhizukuManager(dummyContext, flaky)
        val manager = object : SelfDefenseManager(
            dummyContext, flakyDhizuku, threatEngine,
            expectedSignatureHash = "abc123", repository = repository
        ) {
            override fun getPackageSignatureHash(): String? = "abc123"
        }

        // Below the consecutive-failure threshold: no wipe, but the audit reports failure.
        assertFalse(manager.performSelfDefenseAudit())
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
    }

    @Test
    fun fullAudit_failsWhenUnpinned_andEscalates() {
        val unpinned = managerWith(expectedHash = null, currentHash = "abc123")
        assertFalse(unpinned.performSelfDefenseAudit())
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }
}
