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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airgate.domain.model.SecurityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the process-wide [SecurityStateRepository.securityStateFlow]
 * against the real SharedPreferences backing store and the Android Keystore.
 *
 * The repository is constructed independently by several components that run
 * concurrently (the watchdog service, the audit loop, schedulers, broadcast
 * receivers, and the UI), so the flow must be shared across instances: a write
 * from one instance must be visible to every other instance's collector, and a
 * fresh instance must never observe a stale in-memory value left by an earlier
 * writer. All tests use a throwaway SharedPreferences store so no real app state
 * is touched.
 */
@RunWith(AndroidJUnit4::class)
class SecurityStateFlowInstrumentedTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private fun freshRepository(): SecurityStateRepository {
        val prefs = context.getSharedPreferences(
            "security_state_flow_instrumented_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        return SecurityStateRepository(prefs)
    }

    private fun twoRepositoriesOverSamePrefs(): Pair<SecurityStateRepository, SecurityStateRepository> {
        val prefs = context.getSharedPreferences(
            "security_state_flow_pair_instrumented_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        return SecurityStateRepository(prefs) to SecurityStateRepository(prefs)
    }

    @Test
    fun setSecurityState_updatesTheFlow_onDevice() {
        val repo = freshRepository()
        assertEquals(SecurityState.ARMED_COMPLIANT, repo.securityStateFlow.value)

        repo.setSecurityState(SecurityState.WIPING)

        assertEquals(SecurityState.WIPING, repo.securityStateFlow.value)
    }

    @Test
    fun crossInstanceWrite_propagatesToTheFlow_onDevice() {
        val (first, second) = twoRepositoriesOverSamePrefs()

        first.setSecurityState(SecurityState.WIPING)

        assertEquals(
            "the watchdog's write must reach the UI's instance",
            SecurityState.WIPING,
            second.securityStateFlow.value
        )
    }

    @Test
    fun freshInstance_reSeedsTheFlowFromThePersistedState_onDevice() {
        val prefs = context.getSharedPreferences(
            "security_state_flow_reload_instrumented_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        val first = SecurityStateRepository(prefs)
        first.setSecurityState(SecurityState.COUNTDOWN_WIPE)

        // A fresh repository over the same prefs (e.g. after a restart) must
        // seed the flow from the persisted value, never a stale in-memory one.
        val reloaded = SecurityStateRepository(prefs)

        assertEquals(SecurityState.COUNTDOWN_WIPE, reloaded.securityStateFlow.value)
    }

    @Test
    fun failClosedCorruptState_syncsTheFlowToAlarm_onDevice() {
        val prefs = context.getSharedPreferences(
            "security_state_flow_corrupt_instrumented_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        val repo = SecurityStateRepository(prefs)
        assertEquals(SecurityState.ARMED_COMPLIANT, repo.securityStateFlow.value)

        prefs.edit().putString("security_state", "enc:broken").commit()

        assertEquals(SecurityState.ALARM_ACTIVE, repo.getSecurityState())
        assertEquals(
            "a fail-closed read must converge the flow to the alarmed value",
            SecurityState.ALARM_ACTIVE,
            repo.securityStateFlow.value
        )
        assertTrue(repo.consumeStateTamperFlag())
    }

    @Test
    fun crossInstanceTamperDetection_sharedAcrossRepositories_onDevice() {
        val prefs1 = context.getSharedPreferences(
            "security_state_cross_1_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        val prefs2 = context.getSharedPreferences(
            "security_state_cross_2_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs1.edit().clear().commit()
        prefs2.edit().clear().commit()

        val repo1 = SecurityStateRepository(prefs1)
        val repo2 = SecurityStateRepository(prefs2)

        // Clear any leftover latch
        repo1.consumeStateTamperFlag()

        // Inject corrupt data into repo1's store
        prefs1.edit().putString("security_state", "enc:corrupted_blob").commit()
        repo1.getSecurityState()

        // Verify repo2 (watchdog/audit stand-in) immediately observes the tamper
        assertTrue("repo2 must observe tamper triggered via repo1", repo2.consumeStateTamperFlag())
        assertFalse("flag should now be cleared for repo1", repo1.consumeStateTamperFlag())
        assertFalse("flag should now be cleared for repo2", repo2.consumeStateTamperFlag())
    }
}
