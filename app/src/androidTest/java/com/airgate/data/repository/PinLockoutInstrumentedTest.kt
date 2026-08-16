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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification that the PIN lockout deadline runs on the persistent
 * monotonic clock against real storage: it counts down in real time and survives
 * a reboot (simulated by a fresh repository over the same prefs whose elapsed
 * clock starts near zero).
 */
@RunWith(AndroidJUnit4::class)
class PinLockoutInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun throwawayRepository(): SecurityStateRepository {
        val prefs = context.getSharedPreferences(
            "pin_lockout_it_${System.nanoTime()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        return SecurityStateRepository(prefs)
    }

    @Test
    fun lockoutCountsDownInRealTime() {
        val repository = throwawayRepository()
        repository.setPinLockoutUntil(repository.getMonotonicNow() + 2_500L)

        assertTrue(repository.getPinLockoutRemainingMs() > 0L)

        // After the deadline passes in real time the lockout is over.
        val deadline = System.currentTimeMillis() + 6_000L
        while (repository.getPinLockoutRemainingMs() > 0L && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertEquals(0L, repository.getPinLockoutRemainingMs())
    }

    @Test
    fun lockoutSurvivesARebootWithoutShrinking() {
        val prefs = context.getSharedPreferences(
            "pin_lockout_reboot_it_${System.nanoTime()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        val before = SecurityStateRepository(prefs)
        before.setPinLockoutUntil(before.getMonotonicNow() + 30_000L)
        val remainingBefore = before.getPinLockoutRemainingMs()
        assertTrue(remainingBefore > 0L)

        // A reboot resets the elapsed clock to (near) zero; a fresh repository
        // over the same prefs must still see the full lockout, never a cleared
        // or shortened one.
        val afterReboot = SecurityStateRepository(prefs, null, { true }) { 0L }
        val remainingAfter = afterReboot.getPinLockoutRemainingMs()
        assertTrue(
            "post-reboot remaining $remainingAfter must stay close to the original $remainingBefore",
            remainingAfter >= remainingBefore * 9 / 10
        )
    }

    @Test
    fun resetClearsTheLockout() {
        val repository = throwawayRepository()
        repository.setPinLockoutUntil(repository.getMonotonicNow() + 30_000L)
        assertTrue(repository.getPinLockoutRemainingMs() > 0L)

        repository.resetPinFailedAttempts()

        assertEquals(0L, repository.getPinLockoutRemainingMs())
        assertEquals(0L, repository.getPinLockoutUntil())
    }
}
