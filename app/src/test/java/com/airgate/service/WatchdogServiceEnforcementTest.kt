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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airgate.data.crypto.PinManager
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.BreachEvent
import com.airgate.domain.model.ResponseTier
import com.airgate.domain.model.SecurityState
import com.airgate.domain.model.ViolationType
import com.airgate.testutil.crypto.AndroidKeyStoreRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pins the [WatchdogService] enforcement threading contract: breaches and other
 * enforcement work are enqueued onto the service's dedicated worker thread and
 * run there — never inline on the calling (main) thread — and [onBreachDetected]
 * returns before the enforcement it enqueues has applied.
 */
@RunWith(AndroidJUnit4::class)
class WatchdogServiceEnforcementTest {

    @get:Rule
    val androidKeyStoreRule = AndroidKeyStoreRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun enqueueEnforcement_runsOnTheDedicatedWorkerAndNeverInline() {
        val controller = Robolectric.buildService(WatchdogService::class.java).create()
        val service = controller.get()
        try {
            val workerNames = CopyOnWriteArrayList<String>()
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)

            service.enqueueEnforcement(Runnable {
                workerNames.add(Thread.currentThread().name)
                started.countDown()
                // Block so the task is observably still running after enqueue returns.
                release.await(5, TimeUnit.SECONDS)
            })

            assertTrue("the enforcement task must start on the worker", started.await(3, TimeUnit.SECONDS))
            assertEquals(1, workerNames.size)
            assertTrue(
                "enforcement must run on the dedicated worker, never inline (it ran on ${workerNames[0]})",
                workerNames[0] != Thread.currentThread().name
            )
            assertEquals("watchdog-enforcement", workerNames[0])
            release.countDown()
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun onBreachDetected_enqueuesEnforcement_whichIsDeferredBehindBusyWork() {
        // Arm the watchdog over the same prefs the service's repository reads so
        // the enqueued breach is enforced against the armed config. The
        // device-protection alarm is left OFF so the boot self-defense audit's
        // DO-status/signature findings stay suppressed and cannot disturb the
        // expected escalation of the breach under test.
        val prefs = context.getSharedPreferences("airgate_secure_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val repo = SecurityStateRepository(prefs)
        repo.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        val config = repo.saveConfig(
            AppConfig(
                isEnabled = true,
                dryRunMode = true,
                wipeThreshold = 3,
                deviceProtectionAlarmEnabled = false
            )
        )
        assertTrue("test must arm the watchdog", config.isEnabled)
        repo.resetStreak()
        repo.setSecurityState(SecurityState.ARMED_COMPLIANT)

        val controller = Robolectric.buildService(WatchdogService::class.java).create()
        val service = controller.get()
        try {
            // Occupy the enforcement worker so any enforcement enqueued afterwards
            // is necessarily deferred behind it.
            val release = CountDownLatch(1)
            service.enqueueEnforcement(Runnable { release.await(5, TimeUnit.SECONDS) })

            service.onBreachDetected(
                BreachEvent(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    violationType = ViolationType.BLUETOOTH_ACTIVITY,
                    tier = ResponseTier.ALARM_STREAK,
                    weight = 1
                )
            )

            // onBreachDetected returned, but its enforcement cannot have applied:
            // the worker is blocked by the task above and the breach's enforcement
            // is queued behind it. (An inline implementation would already have
            // scored the breach here.)
            assertEquals(0, repo.getStreak())

            // Free the worker; the queued enforcement now applies on it.
            release.countDown()
            waitUntil(5_000) { repo.getStreak() == 1 }
            assertEquals(SecurityState.ALARM_ACTIVE, repo.getSecurityState())
        } finally {
            controller.destroy()
        }
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue("condition not met within ${timeoutMillis}ms", condition())
    }
}
