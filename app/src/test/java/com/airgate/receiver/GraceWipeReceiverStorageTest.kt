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
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.SecurityState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import com.airgate.testutil.crypto.AndroidKeyStoreRule
import org.junit.Rule

/**
 * JVM verification (Robolectric) of the grace-wipe deadline guard against the real
 * monotonic clock. A scheduled wipe only executes once its monotonic deadline
 * has genuinely elapsed; a wall-clock rollback cannot make an elapsed deadline
 * look unreached because the guard never consults the wall clock.
 */
@RunWith(AndroidJUnit4::class)
class GraceWipeReceiverStorageTest {

    @get:Rule
    val androidKeyStoreRule = AndroidKeyStoreRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private fun armCountdownRepository(): SecurityStateRepository {
        val prefs = context.getSharedPreferences(
            "grace_wipe_it_${System.nanoTime()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        val repository = SecurityStateRepository(prefs)
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        repository.saveConfig(
            AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 0)
        )
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        return repository
    }

    private fun receive(repository: SecurityStateRepository, deadline: Long, action: String = GraceWipeReceiver.ACTION) {
        val receiver = object : GraceWipeReceiver() {
            override fun createRepository(context: Context): SecurityStateRepository = repository
        }
        val intent = Intent(context, GraceWipeReceiver::class.java)
            .setAction(action)
            .putExtra(GraceWipeReceiver.EXTRA_DEADLINE, deadline)
        receiver.onReceive(context, intent)
    }

    @Test
    fun wrongAction_isIgnored() {
        val repository = armCountdownRepository()
        receive(repository, deadline = 0L, action = "com.example.OTHER_ACTION")
        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
    }

    @Test
    fun deadlineInTheFuture_skipsTheWipe() {
        val repository = armCountdownRepository()
        val futureDeadline = SystemClock.elapsedRealtime() + 60_000L
        receive(repository, deadline = futureDeadline)
        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
    }

    @Test
    fun elapsedDeadline_executesTheWipe() {
        val repository = armCountdownRepository()
        receive(repository, deadline = SystemClock.elapsedRealtime() - 1_000L)
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun deadlineExactlyAtNow_executesTheWipe() {
        val repository = armCountdownRepository()
        receive(repository, deadline = SystemClock.elapsedRealtime())
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun zeroDeadline_neverBlocksTheWipe() {
        val repository = armCountdownRepository()
        receive(repository, deadline = 0L)
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun disabledConfig_skipsTheWipeEvenWhenTheDeadlineHasElapsed() {
        val repository = armCountdownRepository()
        repository.saveConfig(AppConfig(isEnabled = false, dryRunMode = true))
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)

        receive(repository, deadline = SystemClock.elapsedRealtime() - 1_000L)

        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
    }

    @Test
    fun stateNoLongerCountdown_skipsTheWipeEvenWhenTheDeadlineHasElapsed() {
        val repository = armCountdownRepository()
        repository.setSecurityState(SecurityState.ALARM_ACTIVE)

        receive(repository, deadline = SystemClock.elapsedRealtime() - 1_000L)

        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
    }
}
