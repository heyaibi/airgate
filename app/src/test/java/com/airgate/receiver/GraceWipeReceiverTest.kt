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

package com.airgate.receiver

import android.content.Context
import com.airgate.data.crypto.JvmPrefsCrypto
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.SecurityState
import com.airgate.testutil.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM guard tests for [GraceWipeReceiver]. The deadline decision runs on
 * an explicit monotonic [now] parameter — never the wall clock — so a rolled-back
 * wall clock cannot cancel an elapsed wipe. (The full onReceive path, including
 * the Intent deadline extra and the injected clock wiring, is covered on-device.)
 */
class GraceWipeReceiverTest {

    private class DummyContext : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.airgate"
        override fun getSystemService(name: String): Any? = null
    }

    private val context = DummyContext()
    private val prefs = InMemorySharedPreferences()

    private fun countdownRepository(): SecurityStateRepository {
        val repository = SecurityStateRepository(prefs, JvmPrefsCrypto(), { true }) { 0L }
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        repository.saveConfig(
            AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 0)
        )
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        return repository
    }

    private fun receiver() = GraceWipeReceiver()

    @Test
    fun disabledConfig_skipsTheWipeEvenWhenTheDeadlineHasElapsed() {
        val repository = countdownRepository()
        repository.saveConfig(AppConfig(isEnabled = false, dryRunMode = true))
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)

        receiver().executeIfDeadlineReached(context, repository, deadline = 0L, now = 100_000L)

        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
    }

    @Test
    fun stateNoLongerCountdown_skipsTheWipeEvenWhenTheDeadlineHasElapsed() {
        val repository = countdownRepository()
        repository.setSecurityState(SecurityState.ALARM_ACTIVE)

        receiver().executeIfDeadlineReached(context, repository, deadline = 0L, now = 100_000L)

        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
    }

    @Test
    fun deadlineInTheFuture_skipsTheWipe() {
        val repository = countdownRepository()

        receiver().executeIfDeadlineReached(context, repository, deadline = 150_000L, now = 100_000L)

        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
    }

    @Test
    fun deadlineExactlyAtNow_executesTheWipe() {
        val repository = countdownRepository()

        receiver().executeIfDeadlineReached(context, repository, deadline = 100_000L, now = 100_000L)

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun elapsedDeadline_executesTheWipe() {
        val repository = countdownRepository()

        receiver().executeIfDeadlineReached(context, repository, deadline = 50_000L, now = 100_000L)

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun zeroDeadline_neverBlocksTheWipe() {
        val repository = countdownRepository()

        receiver().executeIfDeadlineReached(context, repository, deadline = 0L, now = 100_000L)

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun theGuardConsultsTheSuppliedMonotonicNowNotAWallClock() {
        // The deadline is 150_000 on the monotonic timeline. At monotonic now
        // 100_000 it has not elapsed, so the wipe must be skipped. A guard that
        // consulted the wall clock (whose reading is ~1.7e12 and thus "past"
        // 150_000) would wrongly execute the wipe here.
        val repository = countdownRepository()

        receiver().executeIfDeadlineReached(context, repository, deadline = 150_000L, now = 100_000L)

        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
    }
}
