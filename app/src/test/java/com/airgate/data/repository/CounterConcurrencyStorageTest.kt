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
import com.airgate.domain.model.ViolationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import com.airgate.testutil.crypto.AndroidKeyStoreRule
import org.junit.Rule

/**
 * JVM verification (Robolectric) that every persisted counter read-modify-write is
 * atomic under concurrent writers, against the real SharedPreferences backing
 * store and the simulated Android Keystore. The deterministic invariants (exact
 * totals, contiguous returned streak values, a single daily-point spend, an
 * unbroken alert cap) hold only when the whole read -> compute -> write runs
 * under one process-wide lock; a lost update anywhere breaks them.
 *
 * All tests use a throwaway SharedPreferences file so no real app state is
 * touched.
 */
@RunWith(AndroidJUnit4::class)
class CounterConcurrencyStorageTest {

    @get:Rule
    val androidKeyStoreRule = AndroidKeyStoreRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private fun freshRepository(): SecurityStateRepository {
        val prefs = context.getSharedPreferences(
            "counter_concurrency_instrumented_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        return SecurityStateRepository(prefs)
    }

    private fun twoRepositoriesOverSamePrefs(): Pair<SecurityStateRepository, SecurityStateRepository> {
        val prefs = context.getSharedPreferences(
            "counter_concurrency_pair_instrumented_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        return SecurityStateRepository(prefs) to SecurityStateRepository(prefs)
    }

    private fun runConcurrently(threads: Int, iterations: Int, action: () -> Unit) {
        val gate = CountDownLatch(1)
        val workers = (0 until threads).map {
            Thread {
                gate.await()
                repeat(iterations) { action() }
            }
        }
        workers.forEach { it.start() }
        gate.countDown()
        workers.forEach { it.join() }
    }

    private fun collectConcurrently(threads: Int, iterations: Int, action: () -> Int): List<Int> {
        val results = ConcurrentLinkedQueue<Int>()
        runConcurrently(threads, iterations) { results.add(action()) }
        return results.toList()
    }

    @Test
    fun concurrentStreakIncrements_onDevice_loseNoUpdates() {
        val repo = freshRepository()

        val results = collectConcurrently(4, 15) { repo.incrementStreak(1) }

        assertEquals(60, repo.getStreak())
        assertEquals(60, results.size)
        assertEquals((1..60).toSet(), results.toSet())
    }

    @Test
    fun crossInstanceStreakIncrements_onDevice_serialize() {
        val (first, second) = twoRepositoriesOverSamePrefs()
        val results = ConcurrentLinkedQueue<Int>()
        val gate = CountDownLatch(1)
        val workers = listOf(
            Thread { gate.await(); repeat(15) { results.add(first.incrementStreak(1)) } },
            Thread { gate.await(); repeat(15) { results.add(second.incrementStreak(1)) } },
            Thread { gate.await(); repeat(15) { results.add(first.incrementStreak(1)) } },
            Thread { gate.await(); repeat(15) { results.add(second.incrementStreak(1)) } }
        )
        workers.forEach { it.start() }
        gate.countDown()
        workers.forEach { it.join() }

        assertEquals(60, first.getStreak())
        assertEquals(60, second.getStreak())
        assertEquals(60, results.size)
        assertEquals((1..60).toSet(), results.toSet())
    }

    @Test
    fun concurrentVtRecords_onDevice_loseNoUpdates() {
        val repo = freshRepository()
        val vt = ViolationType.WIFI_TRANSCEIVER_ENABLED

        runConcurrently(6, 30) { repo.recordVtBreach(vt, 86_400_000L) }

        assertEquals(180, repo.getVtCount(vt))
    }

    @Test
    fun concurrentScoringClaims_onDevice_spendThePointOnce() {
        val repo = freshRepository()
        val vt = ViolationType.VALIDATED_NETWORK
        repo.setStreak(1)

        val outcomes = collectConcurrently(10, 1) {
            if (repo.claimScoringGroupPoint(vt, 86_400_000L)) 1 else 0
        }

        assertEquals(1, outcomes.sum())
    }

    @Test
    fun alertCap_onDevice_isNotExceededUnderConcurrency() {
        val repo = freshRepository()
        val vt = ViolationType.USB_HOST_LINK

        val outcomes = collectConcurrently(12, 1) {
            if (repo.shouldTriggerAlarmAlert(vt, maxAlerts = 4, tailMinutes = 60)) 1 else 0
        }

        assertEquals(4, outcomes.sum())
    }

    @Test
    fun concurrentDhizukuFailures_onDevice_loseNoUpdates() {
        val repo = freshRepository()

        runConcurrently(6, 25) { repo.incrementDhizukuFailures() }

        assertEquals(150, repo.getDhizukuConsecutiveFailures())
    }

    @Test
    fun readsDuringConcurrentWrites_onDevice_neverThrow() {
        val repo = freshRepository()
        val readValues = ConcurrentLinkedQueue<Int>()
        val gate = CountDownLatch(1)
        val writer = Thread { gate.await(); repeat(40) { repo.incrementStreak(1) } }
        val reader = Thread { gate.await(); repeat(40) { readValues.add(repo.getStreak()) } }
        writer.start(); reader.start(); gate.countDown(); writer.join(); reader.join()

        assertEquals(40, repo.getStreak())
        assertEquals(40, readValues.size)
        readValues.forEach { assertTrue("read $it outside [0, 40]", it in 0..40) }
    }
}
