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

import com.airgate.data.crypto.JvmPrefsCrypto
import com.airgate.data.crypto.PrefsCrypto
import com.airgate.domain.model.SecurityState
import com.airgate.testutil.InMemorySharedPreferences
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the process-wide [SecurityStateRepository.securityStateFlow]: the
 * observable mirror of the persisted security state that lets the UI react to a
 * background breach immediately.
 *
 * The repository is constructed independently by several components that run
 * concurrently (the watchdog service, the audit loop, schedulers, broadcast
 * receivers, and the UI), so the flow must be shared across instances: a write
 * from one instance must be visible to every other instance's collector, and a
 * fresh instance must never observe a stale in-memory value left by an earlier
 * writer. Every invariant below fails if the flow is per-instance or is not
 * re-synced on reads.
 */
class SecurityStateFlowTest {

    private fun freshRepository(): SecurityStateRepository =
        SecurityStateRepository(InMemorySharedPreferences(), JvmPrefsCrypto())

    // --- Write side: setSecurityState updates the flow ---

    @Test
    fun `fresh repository seeds the flow from the persisted state`() {
        val repo = freshRepository()

        assertEquals(SecurityState.ARMED_COMPLIANT, repo.securityStateFlow.value)
    }

    @Test
    fun `setSecurityState updates the flow`() {
        val repo = freshRepository()

        repo.setSecurityState(SecurityState.WIPING)

        assertEquals(SecurityState.WIPING, repo.securityStateFlow.value)
    }

    @Test
    fun `setSecurityState updates the flow through every state`() {
        val repo = freshRepository()

        repo.setSecurityState(SecurityState.ALARM_ACTIVE)
        assertEquals(SecurityState.ALARM_ACTIVE, repo.securityStateFlow.value)

        repo.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        assertEquals(SecurityState.COUNTDOWN_WIPE, repo.securityStateFlow.value)

        repo.setSecurityState(SecurityState.WIPING)
        assertEquals(SecurityState.WIPING, repo.securityStateFlow.value)

        repo.setSecurityState(SecurityState.ARMED_COMPLIANT)
        assertEquals(SecurityState.ARMED_COMPLIANT, repo.securityStateFlow.value)
    }

    @Test
    fun `resetStreak updates the flow to compliant`() {
        val repo = freshRepository()
        repo.setSecurityState(SecurityState.WIPING)
        assertEquals(SecurityState.WIPING, repo.securityStateFlow.value)

        repo.resetStreak()

        assertEquals(SecurityState.ARMED_COMPLIANT, repo.securityStateFlow.value)
    }

    // --- Cross-instance propagation (the process-wide guarantee) ---

    @Test
    fun `a write from one instance is visible to another instance's flow`() {
        val prefs = InMemorySharedPreferences()
        val first = SecurityStateRepository(prefs, JvmPrefsCrypto())
        val second = SecurityStateRepository(prefs, JvmPrefsCrypto())

        first.setSecurityState(SecurityState.WIPING)

        assertEquals(
            "the watchdog's write must reach the UI's instance",
            SecurityState.WIPING,
            second.securityStateFlow.value
        )
    }

    @Test
    fun `a fresh instance re-seeds the flow from the persisted state`() {
        val prefs = InMemorySharedPreferences()
        val first = SecurityStateRepository(prefs, JvmPrefsCrypto())
        first.setSecurityState(SecurityState.WIPING)

        // A fresh repository over the same prefs (e.g. after a restart) must
        // seed the flow from the persisted value, never a stale in-memory one.
        val reloaded = SecurityStateRepository(prefs, JvmPrefsCrypto())

        assertEquals(SecurityState.WIPING, reloaded.securityStateFlow.value)
    }

    @Test
    fun `a fresh instance over empty prefs re-seeds the flow to compliant`() {
        val prefs = InMemorySharedPreferences()
        val first = SecurityStateRepository(prefs, JvmPrefsCrypto())
        first.setSecurityState(SecurityState.WIPING)

        // A different prefs file (a different device state) must not inherit the
        // previous process-wide value: the constructor re-seeds from its own prefs.
        val other = SecurityStateRepository(InMemorySharedPreferences(), JvmPrefsCrypto())

        assertEquals(SecurityState.ARMED_COMPLIANT, other.securityStateFlow.value)
    }

    // --- Read side: getSecurityState re-syncs the flow ---

    @Test
    fun `getSecurityState re-syncs the flow from the persisted state`() {
        val prefs = InMemorySharedPreferences()
        val repo = SecurityStateRepository(prefs, JvmPrefsCrypto())
        repo.setSecurityState(SecurityState.ARMED_COMPLIANT)

        // A second instance writes a different state; the flow is process-wide so
        // it already reflects the write, and a read through this instance must
        // agree with it.
        val writer = SecurityStateRepository(prefs, JvmPrefsCrypto())
        writer.setSecurityState(SecurityState.COUNTDOWN_WIPE)

        assertEquals(SecurityState.COUNTDOWN_WIPE, repo.getSecurityState())
        assertEquals(SecurityState.COUNTDOWN_WIPE, repo.securityStateFlow.value)
    }

    @Test
    fun `fail-closed corrupt state syncs the flow to alarm`() {
        val prefs = InMemorySharedPreferences()
        val repo = SecurityStateRepository(prefs, JvmPrefsCrypto())
        assertEquals(SecurityState.ARMED_COMPLIANT, repo.securityStateFlow.value)

        prefs.edit().putString("security_state", "enc:broken").apply()

        assertEquals(SecurityState.ALARM_ACTIVE, repo.getSecurityState())
        assertEquals(
            "a fail-closed read must converge the flow to the alarmed value",
            SecurityState.ALARM_ACTIVE,
            repo.securityStateFlow.value
        )
    }

    @Test
    fun `unparseable persisted state syncs the flow to alarm`() {
        val prefs = InMemorySharedPreferences()
        val repo = SecurityStateRepository(prefs, JvmPrefsCrypto())

        prefs.edit().putString("security_state", "NOT_A_STATE").apply()

        assertEquals(SecurityState.ALARM_ACTIVE, repo.getSecurityState())
        assertEquals(SecurityState.ALARM_ACTIVE, repo.securityStateFlow.value)
    }

    // --- Emission semantics ---

    @Test
    fun `flow does not emit when the value is unchanged`() = runBlocking {
        val repo = freshRepository()
        val emissions = mutableListOf<SecurityState>()
        val collector = launch {
            repo.securityStateFlow.collect { emissions.add(it) }
        }
        yield()
        assertEquals(listOf(SecurityState.ARMED_COMPLIANT), emissions)

        repo.setSecurityState(SecurityState.ARMED_COMPLIANT)
        yield()
        assertEquals("an unchanged value must not re-emit", 1, emissions.size)

        repo.setSecurityState(SecurityState.WIPING)
        yield()
        assertEquals(2, emissions.size)
        assertEquals(SecurityState.WIPING, emissions.last())

        collector.cancel()
    }

    @Test
    fun `flow replays the latest value to a new collector`() = runBlocking {
        val repo = freshRepository()
        repo.setSecurityState(SecurityState.WIPING)

        val emissions = mutableListOf<SecurityState>()
        val collector = launch {
            repo.securityStateFlow.collect { emissions.add(it) }
        }
        yield()

        assertEquals(
            "a new collector must immediately receive the current state",
            listOf(SecurityState.WIPING),
            emissions
        )
        collector.cancel()
    }

    // --- Refused writes (keystore failure) ---

    @Test
    fun `a refused write still advances the in-memory flow`() {
        val prefs = InMemorySharedPreferences()
        val broken = SecurityStateRepository(prefs, FailingPrefsCrypto())

        broken.setSecurityState(SecurityState.WIPING)

        // The in-memory view advances for the current process even though the
        // write was refused; the flow mirrors that in-memory view.
        assertEquals(SecurityState.WIPING, broken.securityStateFlow.value)
        assertTrue(broken.consumeStateTamperFlag())

        // A fresh repository over the same prefs sees the persisted value, never
        // the refused one, and re-seeds the flow accordingly.
        val reloaded = SecurityStateRepository(prefs, JvmPrefsCrypto())
        assertEquals(SecurityState.ARMED_COMPLIANT, reloaded.getSecurityState())
        assertEquals(SecurityState.ARMED_COMPLIANT, reloaded.securityStateFlow.value)
    }

    /**
     * A [PrefsCrypto] whose every operation throws, standing in for a keystore
     * that is present but broken mid-operation.
     */
    private class FailingPrefsCrypto : PrefsCrypto {
        override fun encrypt(data: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> =
            throw IllegalStateException("encrypt failed")

        override fun decrypt(ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray =
            throw IllegalStateException("decrypt failed")

        override fun hmac(data: ByteArray): ByteArray =
            throw IllegalStateException("hmac failed")
    }
}
