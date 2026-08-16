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

import android.content.ContextWrapper
import com.airgate.BuildConfig
import com.airgate.data.crypto.JvmPrefsCrypto
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.defense.SelfDefenseManager
import com.airgate.dhizuku.DhizukuBinderWrapper
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.ResponseTier
import com.airgate.domain.model.SecurityState
import com.airgate.domain.model.ViolationType
import com.airgate.engine.ThreatEngine
import com.airgate.policy.DevicePolicyEnforcer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Wiring coverage for the recurring posture audit: the default construction must
 * thread the build-pinned signature hash into its [SelfDefenseManager] (the
 * recurring check was a no-op without it), and [PostureAudit.executeCheck] must
 * actually run the self-defense audit and let a detected tamper escalate.
 */
class PostureAuditSelfDefenseTest {

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
    private lateinit var prefs: MockSharedPreferences
    private lateinit var repository: SecurityStateRepository
    private lateinit var mockBinder: MockDhizukuBinder
    private lateinit var dhizukuManager: DhizukuManager
    private lateinit var threatEngine: ThreatEngine
    private lateinit var policyEnforcer: DevicePolicyEnforcer

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "ag_posture_audit_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        dummyContext = DummyContext(tempDir)
        prefs = MockSharedPreferences()
        repository = SecurityStateRepository(prefs, JvmPrefsCrypto())
        // The watchdog may only be armed after a PIN is configured.
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        mockBinder = MockDhizukuBinder(isGranted = true)
        dhizukuManager = DhizukuManager(dummyContext, mockBinder)
        threatEngine = ThreatEngine(dummyContext, repository, dhizukuManager)
        policyEnforcer = DevicePolicyEnforcer(dummyContext, dhizukuManager)
    }

    private fun managerWith(
        expectedHash: String?,
        currentHash: String?
    ): SelfDefenseManager {
        return object : SelfDefenseManager(
            dummyContext, dhizukuManager, threatEngine,
            expectedSignatureHash = expectedHash, repository = repository
        ) {
            override fun getPackageSignatureHash(): String? = currentHash
        }
    }

    private fun armedConfig(deviceProtectionAlarmEnabled: Boolean = false): AppConfig = AppConfig(
        isEnabled = true,
        dryRunMode = true,
        graceWindowSeconds = 0,
        deviceProtectionAlarmEnabled = deviceProtectionAlarmEnabled
    )

    private fun disabledConfig(deviceProtectionAlarmEnabled: Boolean = true): AppConfig = AppConfig(
        isEnabled = false,
        dryRunMode = true,
        graceWindowSeconds = 0,
        deviceProtectionAlarmEnabled = deviceProtectionAlarmEnabled
    )

    private fun defaultAudit(): PostureAudit = PostureAudit(
        dummyContext, repository, dhizukuManager, threatEngine, policyEnforcer
    )

    /**
     * Corrupts a protected value so the next read fails to decrypt, which latches
     * the store's tamper flag. Returns after the flag is set.
     */
    private fun armTamperFlag(): Unit {
        prefs.edit().putString("streak", "enc:broken").commit()
        repository.getStreak()
    }

    /**
     * Makes the Armed PIN configured-but-unreadable: the keys exist (so the PIN
     * is "set") but the protected blobs cannot be decoded/decrypted — the
     * tamper/corruption case that bricks app entry. The read latches the store's
     * tamper flag; it is consumed here so each test starts clean and asserts on
     * the always-on leg's own detection rather than on the setup read.
     */
    private fun corruptPinMaterial(): Unit {
        prefs.edit().putString("pin_hash", "enc:broken").putString("pin_salt", "enc:broken").commit()
        assertTrue(repository.isPinSet())
        assertFalse(repository.isPinUsable())
        repository.consumeStateTamperFlag()
    }

    // --- Default construction threads the pinned hash ---

    @Test
    fun defaultConstruction_threadsBuildConfigHashIntoManager() {
        val audit = PostureAudit(
            dummyContext, repository, dhizukuManager, threatEngine, policyEnforcer
        )

        // The recurring audit's manager must be pinned to the build's expected
        // signature hash, never silently unpinned.
        assertEquals(BuildConfig.EXPECTED_SIGNATURE_HASH, audit.selfDefenseManager.expectedSignatureHash)
    }

    @Test
    fun defaultConstruction_hashIsPresent_notNull() {
        val audit = PostureAudit(
            dummyContext, repository, dhizukuManager, threatEngine, policyEnforcer
        )

        // Without a pinned hash the recurring check is a no-op; presence is the
        // minimum guarantee for the fail-closed verification to be meaningful.
        assertFalse(audit.selfDefenseManager.expectedSignatureHash.isNullOrBlank())
    }

    @Test
    fun explicitHash_isThreadedIntoManager() {
        val audit = PostureAudit(
            dummyContext, repository, dhizukuManager, threatEngine, policyEnforcer,
            expectedSignatureHash = "custom-pinned-hash"
        )

        assertEquals("custom-pinned-hash", audit.selfDefenseManager.expectedSignatureHash)
    }

    // --- executeCheck runs the self-defense audit ---

    @Test
    fun executeCheck_invokesSelfDefenseAudit_whenEnabled() {
        val healthy = managerWith(expectedHash = "abc123", currentHash = "abc123")
        val audit = PostureAudit(
            dummyContext, repository, dhizukuManager, threatEngine, policyEnforcer,
            selfDefenseManager = healthy
        )
        repository.saveConfig(armedConfig())

        val result = audit.executeCheck()

        assertTrue(result)
        // A healthy pinned audit must not escalate the state.
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
    }

    @Test
    fun executeCheck_skipsSelfDefenseAudit_whenDisabled() {
        var auditInvoked = false
        val recording = object : SelfDefenseManager(
            dummyContext, dhizukuManager, threatEngine,
            expectedSignatureHash = "abc123", repository = repository
        ) {
            override fun getPackageSignatureHash(): String? = "abc123"
            override fun performSelfDefenseAudit(): Boolean {
                auditInvoked = true
                return true
            }
        }
        val audit = PostureAudit(
            dummyContext, repository, dhizukuManager, threatEngine, policyEnforcer,
            selfDefenseManager = recording
        )
        repository.saveConfig(AppConfig(isEnabled = false, dryRunMode = true))

        val result = audit.executeCheck()

        assertFalse(result)
        // Disabled watchdog: no audit, no escalation.
        assertFalse(auditInvoked)
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
    }

    // --- Tamper detected by the recurring audit escalates ---

    @Test
    fun executeCheck_tamperedSignature_escalatesToWiping() {
        val tampered = managerWith(expectedHash = "abc123", currentHash = "0bad0bad")
        val audit = PostureAudit(
            dummyContext, repository, dhizukuManager, threatEngine, policyEnforcer,
            selfDefenseManager = tampered
        )
        // Self-defense breaches route through the DO_RESTRICTION_MISSING suppression
        // gate, so the device-protection alarm must be enabled for them to escalate.
        repository.saveConfig(armedConfig(deviceProtectionAlarmEnabled = true))

        audit.executeCheck()

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        val reason = repository.getVtReason(ViolationType.DO_RESTRICTION_MISSING)
        assertTrue(reason != null && reason.contains("signature tamper detected"))
    }

    @Test
    fun executeCheck_healthyManager_withAlarmEnabled_staysAtAlarmActive_notWiping() {
        // Counterfactual control for the escalation tests above: the dummy context
        // reports every user restriction as missing, which fires a single
        // ALARM_STREAK DO_RESTRICTION_MISSING breach (streak 1 < wipeThreshold 3).
        // That must land in ALARM_ACTIVE, never WIPING — proving WIPING in the
        // escalation tests is driven by the self-tamper tier, not restriction noise.
        val healthy = managerWith(expectedHash = "abc123", currentHash = "abc123")
        val audit = PostureAudit(
            dummyContext, repository, dhizukuManager, threatEngine, policyEnforcer,
            selfDefenseManager = healthy
        )
        repository.saveConfig(armedConfig(deviceProtectionAlarmEnabled = true))

        audit.executeCheck()

        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
        assertEquals(1, repository.getStreak())
    }

    @Test
    fun executeCheck_unpinnedManager_failsClosed_andEscalates() {
        val unpinned = managerWith(expectedHash = null, currentHash = "abc123")
        val audit = PostureAudit(
            dummyContext, repository, dhizukuManager, threatEngine, policyEnforcer,
            selfDefenseManager = unpinned
        )
        repository.saveConfig(armedConfig(deviceProtectionAlarmEnabled = true))

        audit.executeCheck()

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        val reason = repository.getVtReason(ViolationType.DO_RESTRICTION_MISSING)
        assertTrue(reason != null && reason.contains("no pinned signature hash"))
    }

    @Test
    fun executeCheck_tamperIsSuppressed_whenAlarmDisabled() {
        val tampered = managerWith(expectedHash = "abc123", currentHash = "0bad0bad")
        val audit = PostureAudit(
            dummyContext, repository, dhizukuManager, threatEngine, policyEnforcer,
            selfDefenseManager = tampered
        )
        // With the device-protection alarm off the breach is recorded but must not
        // escalate to a wipe.
        repository.saveConfig(armedConfig(deviceProtectionAlarmEnabled = false))

        audit.executeCheck()

        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
    }

    // --- Protected-state tamper flag is processed before the enabled gate ---

    @Test
    fun executeCheck_processesTamper_evenWhenDisabled() {
        // The enabled gate must not silence a protected-state tamper: the tampered
        // value can itself be what flips config.isEnabled to false.
        repository.saveConfig(disabledConfig(deviceProtectionAlarmEnabled = true))
        armTamperFlag()

        val result = defaultAudit().executeCheck()

        assertFalse("the watchdog stays disabled", result)
        assertEquals("the tamper must still escalate in dry-run", SecurityState.WIPING, repository.getSecurityState())
        val reason = repository.getVtReason(ViolationType.DO_RESTRICTION_MISSING)
        assertTrue(reason != null && reason.contains("tamper"))
    }

    @Test
    fun executeCheck_tamperEscalates_whenEnabled() {
        repository.saveConfig(
            AppConfig(
                isEnabled = true,
                dryRunMode = true,
                graceWindowSeconds = 0,
                wipeThreshold = 1,
                deviceProtectionAlarmEnabled = true
            )
        )
        armTamperFlag()

        defaultAudit().executeCheck()

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun executeCheck_tamperIsConsumedExactlyOnce() {
        repository.saveConfig(disabledConfig(deviceProtectionAlarmEnabled = true))
        armTamperFlag()
        val audit = defaultAudit()

        audit.executeCheck()
        assertEquals(1, repository.getVtCount(ViolationType.DO_RESTRICTION_MISSING))

        // A second pass finds no flag: the breach must not re-fire on every audit.
        audit.executeCheck()
        assertEquals(1, repository.getVtCount(ViolationType.DO_RESTRICTION_MISSING))
        assertFalse(repository.consumeStateTamperFlag())
    }

    @Test
    fun executeCheck_tamperIsNotSilencedByCorruptedEnabledFlag() {
        // The exploit the ordering fix closes: config_is_enabled is corrupted so
        // it decrypts to false. The tamper flag latched by that very read must
        // still be consumed and escalated ahead of the (now false) enabled gate.
        repository.saveConfig(armedConfig(deviceProtectionAlarmEnabled = true))
        prefs.edit().putString("config_is_enabled", "enc:broken").commit()
        assertFalse("the corrupted enabled flag must read as disabled", repository.getConfig().isEnabled)

        defaultAudit().executeCheck()

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun executeCheck_tamperIsRecordedButNotEscalated_whenAlarmDisabled() {
        // Consistency with the self-defense suppression contract: with the
        // device-protection alarm off, a state tamper is recorded for the audit
        // trail but must not wipe. The watchdog is disabled so the later audit
        // steps cannot overwrite the recorded reason.
        repository.saveConfig(disabledConfig(deviceProtectionAlarmEnabled = false))
        armTamperFlag()

        defaultAudit().executeCheck()

        val reason = repository.getVtReason(ViolationType.DO_RESTRICTION_MISSING)
        assertTrue("the tamper must still be recorded", reason != null && reason.contains("tamper"))
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
        assertFalse(repository.consumeStateTamperFlag())
    }

    // --- Always-on tamper-only check (runs whether or not the watchdog is enabled) ---

    @Test
    fun checkTamperOnly_escalatesTamper_whenFlagSet() {
        repository.saveConfig(armedConfig(deviceProtectionAlarmEnabled = true))
        armTamperFlag()

        val result = defaultAudit().checkTamperOnly()

        assertTrue("a processed tamper must be reported", result)
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertFalse(repository.consumeStateTamperFlag())
    }

    @Test
    fun checkTamperOnly_escalatesTamper_whenDisabled() {
        // The paused-monitor case: the enabled gate must not silence a tamper in
        // the always-on leg either.
        repository.saveConfig(disabledConfig(deviceProtectionAlarmEnabled = true))
        armTamperFlag()

        val result = defaultAudit().checkTamperOnly()

        assertTrue(result)
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun checkTamperOnly_doesNothing_whenNoFlagSet() {
        repository.saveConfig(disabledConfig(deviceProtectionAlarmEnabled = true))

        val result = defaultAudit().checkTamperOnly()

        assertFalse(result)
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
        assertEquals(0, repository.getStreak())
    }

    @Test
    fun checkTamperOnly_doesNotRunTheFullAudit() {
        // The always-on leg must only consume the tamper flag: neither the
        // self-defense audit nor the restriction audit may run, so a paused
        // watchdog stays passive in every other respect.
        var selfDefenseInvoked = false
        val recording = object : SelfDefenseManager(
            dummyContext, dhizukuManager, threatEngine,
            expectedSignatureHash = "abc123", repository = repository
        ) {
            override fun performSelfDefenseAudit(): Boolean {
                selfDefenseInvoked = true
                return true
            }
        }
        val audit = PostureAudit(
            dummyContext, repository, dhizukuManager, threatEngine, policyEnforcer,
            selfDefenseManager = recording
        )
        repository.saveConfig(armedConfig(deviceProtectionAlarmEnabled = true))

        val result = audit.checkTamperOnly()

        assertFalse(result)
        assertFalse("the full self-defense audit must not run", selfDefenseInvoked)
        // The restriction audit (which would score a DO_RESTRICTION_MISSING
        // ALARM_STREAK point on this dummy context) must not run either.
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
        assertEquals(0, repository.getStreak())
        assertFalse(repository.consumeStateTamperFlag())
    }

    @Test
    fun checkTamperOnly_escalatesTamper_withoutRunningTheFullAudit() {
        var selfDefenseInvoked = false
        val recording = object : SelfDefenseManager(
            dummyContext, dhizukuManager, threatEngine,
            expectedSignatureHash = "abc123", repository = repository
        ) {
            override fun performSelfDefenseAudit(): Boolean {
                selfDefenseInvoked = true
                return true
            }
        }
        val audit = PostureAudit(
            dummyContext, repository, dhizukuManager, threatEngine, policyEnforcer,
            selfDefenseManager = recording
        )
        repository.saveConfig(armedConfig(deviceProtectionAlarmEnabled = true))
        armTamperFlag()

        val result = audit.checkTamperOnly()

        assertTrue(result)
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertFalse("only the tamper leg may run", selfDefenseInvoked)
    }

    @Test
    fun checkTamperOnly_isRecordedButNotEscalated_whenAlarmDisabled() {
        repository.saveConfig(disabledConfig(deviceProtectionAlarmEnabled = false))
        armTamperFlag()

        defaultAudit().checkTamperOnly()

        val reason = repository.getVtReason(ViolationType.DO_RESTRICTION_MISSING)
        assertTrue("the tamper must still be recorded", reason != null && reason.contains("tamper"))
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
        assertFalse(repository.consumeStateTamperFlag())
    }

    @Test
    fun checkTamperOnly_consumesTheFlag_exactlyOnce() {
        repository.saveConfig(disabledConfig(deviceProtectionAlarmEnabled = true))
        armTamperFlag()
        val audit = defaultAudit()

        audit.checkTamperOnly()
        assertEquals(1, repository.getVtCount(ViolationType.DO_RESTRICTION_MISSING))

        audit.checkTamperOnly()
        assertEquals(1, repository.getVtCount(ViolationType.DO_RESTRICTION_MISSING))
        assertFalse(repository.consumeStateTamperFlag())
    }

    // --- Always-on tamper-only check: unreadable Armed PIN credential ---

    @Test
    fun checkTamperOnly_escalatesUnreadablePin_whenAlarmEnabled() {
        repository.saveConfig(armedConfig(deviceProtectionAlarmEnabled = true))
        corruptPinMaterial()

        val result = defaultAudit().checkTamperOnly()

        assertTrue("an unreadable Armed PIN must be detected", result)
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertFalse(
            "the latch from the unreadable read must be consumed, not double-fired",
            repository.consumeStateTamperFlag()
        )
    }

    @Test
    fun checkTamperOnly_escalatesUnreadablePin_whenWatchdogDisabled() {
        // The paused-monitor case: the enabled gate must not silence PIN tamper in
        // the always-on leg either — the credential state stays awake while the
        // intrusion zones sleep.
        repository.saveConfig(disabledConfig(deviceProtectionAlarmEnabled = true))
        corruptPinMaterial()

        val result = defaultAudit().checkTamperOnly()

        assertTrue(result)
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun checkTamperOnly_recordsUnreadablePin_butNotEscalated_whenAlarmDisabled() {
        repository.saveConfig(disabledConfig(deviceProtectionAlarmEnabled = false))
        corruptPinMaterial()

        defaultAudit().checkTamperOnly()

        val reason = repository.getVtReason(ViolationType.DO_RESTRICTION_MISSING)
        assertTrue("the tamper must still be recorded", reason != null && reason.contains("tamper"))
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
        assertFalse(repository.consumeStateTamperFlag())
    }

    @Test
    fun checkTamperOnly_doesNothing_whenPinReadable() {
        repository.saveConfig(disabledConfig(deviceProtectionAlarmEnabled = true))

        val result = defaultAudit().checkTamperOnly()

        assertFalse(result)
        assertEquals(0, repository.getVtCount(ViolationType.DO_RESTRICTION_MISSING))
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
    }

    @Test
    fun checkTamperOnly_doesNothing_whenNoPinConfigured() {
        // A never-configured PIN is the fresh-install path (the owner is setting a
        // PIN for the first time), not tampering.
        repository.saveConfig(disabledConfig(deviceProtectionAlarmEnabled = true))
        prefs.edit().remove("pin_hash").remove("pin_salt").commit()
        assertFalse(repository.isPinSet())

        val result = defaultAudit().checkTamperOnly()

        assertFalse(result)
        assertEquals(0, repository.getVtCount(ViolationType.DO_RESTRICTION_MISSING))
    }

    @Test
    fun checkTamperOnly_unreadablePin_detectsEachCycle_withoutDoubleFiring() {
        // While the credential stays unreadable every audit cycle re-detects it
        // (like any persistent corruption), but a single cycle must fire exactly
        // once: the explicit check consumes the read's latch so the flag path
        // cannot add a second, generic breach for the same read.
        repository.saveConfig(
            AppConfig(
                isEnabled = true, dryRunMode = true, graceWindowSeconds = 0,
                deviceProtectionAlarmEnabled = true,
                selfTamperTier = ResponseTier.ALARM_STREAK
            )
        )
        corruptPinMaterial()
        val audit = defaultAudit()

        audit.checkTamperOnly()
        assertEquals(1, repository.getVtCount(ViolationType.DO_RESTRICTION_MISSING))
        assertFalse(repository.consumeStateTamperFlag())

        audit.checkTamperOnly()
        assertEquals(2, repository.getVtCount(ViolationType.DO_RESTRICTION_MISSING))
    }

    @Test
    fun checkTamperOnly_restoresNormalOncePinReprovisioned() {
        // Re-provisioning (a fresh readable credential) must stop the escalation:
        // the owner resolved the tamper, and the leg must not keep firing.
        repository.saveConfig(disabledConfig(deviceProtectionAlarmEnabled = true))
        corruptPinMaterial()
        val audit = defaultAudit()

        assertTrue(audit.checkTamperOnly())

        repository.savePin(byteArrayOf(9, 8, 7), byteArrayOf(6, 5))
        assertTrue(repository.isPinUsable())
        assertFalse(audit.checkTamperOnly())
    }
}
