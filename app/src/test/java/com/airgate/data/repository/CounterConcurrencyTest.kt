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
import com.airgate.domain.model.ViolationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

/**
 * Verifies that every persisted counter read-modify-write is atomic under
 * concurrent writers, including writers in different repository instances.
 *
 * Breach processing runs on several threads at once (broadcast receivers on the
 * main thread, the audit handler thread, the SafetyNet thread) and each of those
 * components builds its own [SecurityStateRepository], so the store locks must be
 * shared across instances in the process. A test with multiple repository
 * instances over one prefs file proves that: with per-instance locks the
 * increments would lose updates and the deterministic invariants below (exact
 * totals, contiguous returned values, single daily-point spend) would fail.
 */
class CounterConcurrencyTest {

    /**
     * A SharedPreferences fake that mirrors the real implementation's guarantees:
     * individual get/put are thread-safe and an `apply()` updates the in-memory
     * state synchronously and atomically. This keeps concurrency tests meaningful
     * without an Android runtime (a plain HashMap would fail for the wrong reason).
     */
    private class ThreadSafePrefs : android.content.SharedPreferences {
        private val map = ConcurrentHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? {
            val v = map[key] ?: return defValue
            return v as? String ?: throw ClassCastException("$v cannot be cast to String")
        }
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private inner class Editor : android.content.SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { temp[key!!] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor { temp[key!!] = values; return this }
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { temp[key!!] = value; return this }
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { temp[key!!] = value; return this }
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { temp[key!!] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { temp[key!!] = value; return this }
            override fun remove(key: String?): android.content.SharedPreferences.Editor { temp[key!!] = null; return this }
            override fun clear(): android.content.SharedPreferences.Editor { clearFlag = true; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                synchronized(map) {
                    if (clearFlag) map.clear()
                    temp.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
                }
            }
        }
    }

    private val prefs = ThreadSafePrefs()
    private val repository = SecurityStateRepository(prefs, JvmPrefsCrypto()) { 0L }

    private val dayMs = 86_400_000L

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

    // --- Streak (EnforcementStateStore) ---

    @Test
    fun `concurrent streak increments lose no updates`() {
        val results = collectConcurrently(8, 50) { repository.incrementStreak(1) }

        assertEquals(400, repository.getStreak())
        // Every increment must derive from the value the previous one persisted:
        // with no lost update the returned values are exactly 1..400.
        assertEquals(400, results.size)
        assertEquals((1..400).toSet(), results.toSet())
    }

    @Test
    fun `concurrent increments across repository instances serialize`() {
        val second = SecurityStateRepository(prefs, JvmPrefsCrypto())
        val first = repository
        val results = ConcurrentLinkedQueue<Int>()
        val gate = CountDownLatch(1)
        val workers = listOf(
            Thread { gate.await(); repeat(50) { results.add(first.incrementStreak(1)) } },
            Thread { gate.await(); repeat(50) { results.add(second.incrementStreak(1)) } },
            Thread { gate.await(); repeat(50) { results.add(first.incrementStreak(1)) } },
            Thread { gate.await(); repeat(50) { results.add(second.incrementStreak(1)) } }
        )
        workers.forEach { it.start() }
        gate.countDown()
        workers.forEach { it.join() }

        assertEquals(200, repository.getStreak())
        assertEquals(200, second.getStreak())
        assertEquals(200, results.size)
        assertEquals((1..200).toSet(), results.toSet())
    }

    @Test
    fun `a set between increment bursts is the base for the next burst`() {
        runConcurrently(4, 25) { repository.incrementStreak(1) }
        assertEquals(100, repository.getStreak())

        // A concurrent setStreak cannot be silently clobbered by an in-flight
        // increment: the second burst must build on the value 7, not a stale one.
        repository.setStreak(7)

        val results = collectConcurrently(4, 25) { repository.incrementStreak(1) }
        assertEquals(107, repository.getStreak())
        assertEquals((8..107).toSet(), results.toSet())
    }

    @Test
    fun `negative increments clamp at zero`() {
        repository.setStreak(3)
        assertEquals(0, repository.incrementStreak(-5))
        assertEquals(0, repository.getStreak())
        assertEquals(0, repository.incrementStreak(-1))
        assertEquals(0, repository.getStreak())
    }

    @Test
    fun `streak reads during concurrent writes stay within bounds`() {
        val readValues = ConcurrentLinkedQueue<Int>()
        val gate = CountDownLatch(1)
        val writer = Thread { gate.await(); repeat(200) { repository.incrementStreak(1) } }
        val reader = Thread { gate.await(); repeat(200) { readValues.add(repository.getStreak()) } }
        writer.start(); reader.start(); gate.countDown(); writer.join(); reader.join()

        assertEquals(200, repository.getStreak())
        assertEquals(200, readValues.size)
        readValues.forEach { assertTrue("read $it outside [0, 200]", it in 0..200) }
    }

    @Test
    fun `concurrent increments and resets never corrupt the streak`() {
        val gate = CountDownLatch(1)
        val incrementer = Thread { gate.await(); repeat(150) { repository.incrementStreak(1) } }
        val reseter = Thread { gate.await(); repeat(15) { repository.setStreak(0) } }
        incrementer.start(); reseter.start(); gate.countDown(); incrementer.join(); reseter.join()

        val finalValue = repository.getStreak()
        assertTrue("final streak $finalValue outside [0, 150]", finalValue in 0..150)
    }

    // --- Violation-type counters (ViolationTracker) ---

    @Test
    fun `concurrent same-window breach records lose no updates`() {
        val vt = ViolationType.WIFI_TRANSCEIVER_ENABLED
        runConcurrently(8, 40) { repository.recordVtBreach(vt, dayMs) }

        assertEquals(320, repository.getVtCount(vt))
    }

    @Test
    fun `cross-instance breach records lose no updates`() {
        val second = SecurityStateRepository(prefs, JvmPrefsCrypto())
        val vt = ViolationType.ADB_ENABLED_FLIP
        val gate = CountDownLatch(1)
        val workers = listOf(
            Thread { gate.await(); repeat(50) { repository.recordVtBreach(vt, dayMs) } },
            Thread { gate.await(); repeat(50) { second.recordVtBreach(vt, dayMs) } },
            Thread { gate.await(); repeat(50) { repository.recordVtBreach(vt, dayMs) } },
            Thread { gate.await(); repeat(50) { second.recordVtBreach(vt, dayMs) } }
        )
        workers.forEach { it.start() }
        gate.countDown()
        workers.forEach { it.join() }

        assertEquals(200, repository.getVtCount(vt))
        assertEquals(200, second.getVtCount(vt))
    }

    @Test
    fun `concurrent window-elapse resets land at one`() {
        // A window of 0 makes every record start a fresh window (reset to 1), so
        // the concurrent reset branch never crashes and settles on the last write.
        val vt = ViolationType.BLUETOOTH_ACTIVITY
        runConcurrently(8, 40) { repository.recordVtBreach(vt, 0L) }

        assertEquals(1, repository.getVtCount(vt))
    }

    @Test
    fun `concurrent increments saturate at the maximum value`() {
        val vt = ViolationType.AIRPLANE_MODE_OFF
        // Establish the scoring window first: the first record would otherwise be
        // read as a new window (lastTimestamp == 0) and reset the count to 1.
        repository.recordVtBreach(vt, dayMs)
        repository.setVtCount(vt, Int.MAX_VALUE - 3)
        runConcurrently(8, 25) { repository.recordVtBreach(vt, dayMs) }

        assertEquals(Int.MAX_VALUE, repository.getVtCount(vt))
    }

    @Test
    fun `only one concurrent claim spends the daily point`() {
        val vt = ViolationType.VALIDATED_NETWORK
        repository.setStreak(1)

        val outcomes = collectConcurrently(10, 1) {
            if (repository.claimScoringGroupPoint(vt, dayMs)) 1 else 0
        }

        assertEquals(1, outcomes.sum())
    }

    @Test
    fun `each scoring group keeps its own daily point under concurrency`() {
        val wireless = ViolationType.VALIDATED_NETWORK
        val usb = ViolationType.USB_HOST_LINK
        repository.setStreak(1)

        val outcomes = ConcurrentLinkedQueue<Pair<String, Boolean>>()
        runConcurrently(10, 1) {
            outcomes.add("wireless" to repository.claimScoringGroupPoint(wireless, dayMs))
            outcomes.add("usb" to repository.claimScoringGroupPoint(usb, dayMs))
        }

        assertEquals(1, outcomes.count { it.first == "wireless" && it.second })
        assertEquals(1, outcomes.count { it.first == "usb" && it.second })
    }

    @Test
    fun `cross-instance concurrent claims spend the daily point exactly once`() {
        val second = SecurityStateRepository(prefs, JvmPrefsCrypto())
        val vt = ViolationType.DEVELOPER_OPTIONS_TOGGLE
        repository.setStreak(1)

        val outcomes = ConcurrentLinkedQueue<Boolean>()
        val gate = CountDownLatch(1)
        val workers = listOf(
            Thread { gate.await(); outcomes.add(repository.claimScoringGroupPoint(vt, dayMs)) },
            Thread { gate.await(); outcomes.add(second.claimScoringGroupPoint(vt, dayMs)) },
            Thread { gate.await(); outcomes.add(repository.claimScoringGroupPoint(vt, dayMs)) },
            Thread { gate.await(); outcomes.add(second.claimScoringGroupPoint(vt, dayMs)) }
        )
        workers.forEach { it.start() }
        gate.countDown()
        workers.forEach { it.join() }

        assertEquals(1, outcomes.count { it })
    }

    @Test
    fun `alert cap is not exceeded under concurrency`() {
        val vt = ViolationType.USB_HOST_LINK
        val outcomes = collectConcurrently(12, 1) {
            if (repository.shouldTriggerAlarmAlert(vt, maxAlerts = 4, tailMinutes = 60)) 1 else 0
        }

        assertEquals(4, outcomes.sum())
        assertEquals(4, prefs.getInt("alert_count_${vt.name}", 0))
    }

    @Test
    fun `every concurrent alert under the cap is allowed and counted`() {
        val vt = ViolationType.TETHERING_RNDIS
        val outcomes = collectConcurrently(8, 1) {
            if (repository.shouldTriggerAlarmAlert(vt, maxAlerts = 8, tailMinutes = 60)) 1 else 0
        }

        assertEquals(8, outcomes.sum())
        assertEquals(8, prefs.getInt("alert_count_${vt.name}", 0))
    }

    // --- Dhizuku consecutive failures (SelfDefenseStateStore) ---

    @Test
    fun `concurrent dhizuku failure increments lose no updates`() {
        runConcurrently(6, 20) { repository.incrementDhizukuFailures() }

        assertEquals(120, repository.getDhizukuConsecutiveFailures())
    }

    @Test
    fun `cross-instance dhizuku increments lose no updates`() {
        val second = SecurityStateRepository(prefs, JvmPrefsCrypto())
        val gate = CountDownLatch(1)
        val workers = listOf(
            Thread { gate.await(); repeat(25) { repository.incrementDhizukuFailures() } },
            Thread { gate.await(); repeat(25) { second.incrementDhizukuFailures() } },
            Thread { gate.await(); repeat(25) { repository.incrementDhizukuFailures() } },
            Thread { gate.await(); repeat(25) { second.incrementDhizukuFailures() } }
        )
        workers.forEach { it.start() }
        gate.countDown()
        workers.forEach { it.join() }

        assertEquals(100, repository.getDhizukuConsecutiveFailures())
        assertEquals(100, second.getDhizukuConsecutiveFailures())
    }

    @Test
    fun `dhizuku failures reset then count again from one`() {
        runConcurrently(4, 10) { repository.incrementDhizukuFailures() }
        assertEquals(40, repository.getDhizukuConsecutiveFailures())

        repository.resetDhizukuFailures()
        assertEquals(0, repository.getDhizukuConsecutiveFailures())

        assertEquals(1, repository.incrementDhizukuFailures())
        assertEquals(1, repository.getDhizukuConsecutiveFailures())
    }

    @Test
    fun `concurrent resets leave the failure counter at zero`() {
        runConcurrently(8, 20) { repository.incrementDhizukuFailures() }
        assertEquals(160, repository.getDhizukuConsecutiveFailures())

        runConcurrently(4, 5) { repository.resetDhizukuFailures() }
        assertEquals(0, repository.getDhizukuConsecutiveFailures())
    }

    @Test
    fun `concurrent increments and resets never corrupt the failure counter`() {
        val gate = CountDownLatch(1)
        val incrementer = Thread { gate.await(); repeat(200) { repository.incrementDhizukuFailures() } }
        val reseter = Thread { gate.await(); repeat(20) { repository.resetDhizukuFailures() } }
        incrementer.start(); reseter.start(); gate.countDown(); incrementer.join(); reseter.join()

        val finalValue = repository.getDhizukuConsecutiveFailures()
        assertTrue("final failure count $finalValue outside [0, 200]", finalValue in 0..200)
    }

    // --- PIN failed-attempt counter (PinStore) ---

    @Test
    fun `concurrent pin failed-attempt increments lose no updates`() {
        runConcurrently(6, 20) { repository.incrementPinFailedAttempts() }

        assertEquals(120, repository.getPinFailedAttempts())
    }

    @Test
    fun `cross-instance pin failed-attempt increments lose no updates`() {
        val second = SecurityStateRepository(prefs, JvmPrefsCrypto())
        val gate = CountDownLatch(1)
        val workers = listOf(
            Thread { gate.await(); repeat(25) { repository.incrementPinFailedAttempts() } },
            Thread { gate.await(); repeat(25) { second.incrementPinFailedAttempts() } },
            Thread { gate.await(); repeat(25) { repository.incrementPinFailedAttempts() } },
            Thread { gate.await(); repeat(25) { second.incrementPinFailedAttempts() } }
        )
        workers.forEach { it.start() }
        gate.countDown()
        workers.forEach { it.join() }

        assertEquals(100, repository.getPinFailedAttempts())
        assertEquals(100, second.getPinFailedAttempts())
    }

    @Test
    fun `pin failed attempts reset then count again from one`() {
        runConcurrently(4, 10) { repository.incrementPinFailedAttempts() }
        assertEquals(40, repository.getPinFailedAttempts())

        repository.resetPinFailedAttempts()
        assertEquals(0, repository.getPinFailedAttempts())

        assertEquals(1, repository.incrementPinFailedAttempts())
        assertEquals(1, repository.getPinFailedAttempts())
    }

    @Test
    fun `pin reset also clears the lockout deadline`() {
        repository.setPinLockoutUntil(999_999L)
        assertEquals(999_999L, repository.getPinLockoutUntil())

        repository.resetPinFailedAttempts()

        assertEquals(0L, repository.getPinLockoutUntil())
    }

    @Test
    fun `concurrent pin increments and resets never corrupt the failed-attempt counter`() {
        val gate = CountDownLatch(1)
        val incrementer = Thread { gate.await(); repeat(150) { repository.incrementPinFailedAttempts() } }
        val reseter = Thread { gate.await(); repeat(20) { repository.resetPinFailedAttempts() } }
        incrementer.start(); reseter.start(); gate.countDown(); incrementer.join(); reseter.join()

        val finalValue = repository.getPinFailedAttempts()
        assertTrue("final attempt count $finalValue outside [0, 150]", finalValue in 0..150)
    }

    // --- Additional branch and scenario coverage ---

    @Test
    fun `streak increments return the value that a fresh read confirms`() {
        // The clamp contract: an increment returns exactly what a subsequent read
        // observes — negative results clamp to zero rather than being reported as
        // a negative streak.
        repository.setStreak(2)
        assertEquals(3, repository.incrementStreak(1))
        assertEquals(3, repository.getStreak())
        assertEquals(5, repository.incrementStreak(2))
        assertEquals(5, repository.getStreak())
        assertEquals(0, repository.incrementStreak(-10))
        assertEquals(0, repository.getStreak())
    }

    @Test
    fun `vt count reads during concurrent writes stay within bounds`() {
        val vt = ViolationType.AIRPLANE_MODE_OFF
        val readValues = ConcurrentLinkedQueue<Int>()
        val gate = CountDownLatch(1)
        val writer = Thread { gate.await(); repeat(150) { repository.recordVtBreach(vt, dayMs) } }
        val reader = Thread { gate.await(); repeat(150) { readValues.add(repository.getVtCount(vt)) } }
        writer.start(); reader.start(); gate.countDown(); writer.join(); reader.join()

        assertEquals(150, repository.getVtCount(vt))
        assertEquals(150, readValues.size)
        readValues.forEach { assertTrue("read $it outside [0, 150]", it in 0..150) }
    }

    @Test
    fun `concurrent alerts after the tail window reset the stale episode`() {
        val vt = ViolationType.AIRPLANE_MODE_OFF
        val tailMinutes = 1
        val tailMs = tailMinutes * 60 * 1000L
        // Seed a stale episode that has exceeded the tail window: a count past
        // the cap and an old timestamp. The tail-elapse reset branch must discard
        // the stale count (otherwise the cap would rate-limit every call to
        // false), so a fresh episode is counted from 1 under concurrency.
        prefs.edit()
            .putInt("alert_count_${vt.name}", 99)
            .putLong("alert_timestamp_${vt.name}", System.currentTimeMillis() - tailMs - 1000L)
            .apply()

        val outcomes = collectConcurrently(10, 1) {
            if (repository.shouldTriggerAlarmAlert(vt, maxAlerts = 10, tailMinutes = tailMinutes)) 1 else 0
        }

        assertEquals(10, outcomes.sum())
        assertEquals(10, prefs.getInt("alert_count_${vt.name}", 0))
    }

    @Test
    fun `cross-instance alert cap is not exceeded under concurrency`() {
        val second = SecurityStateRepository(prefs, JvmPrefsCrypto())
        val vt = ViolationType.BLUETOOTH_ACTIVITY

        val outcomes = ConcurrentLinkedQueue<Boolean>()
        val gate = CountDownLatch(1)
        val workers = (0 until 12).map {
            val repo = if (it % 2 == 0) repository else second
            Thread {
                gate.await()
                outcomes.add(repo.shouldTriggerAlarmAlert(vt, maxAlerts = 4, tailMinutes = 60))
            }
        }
        workers.forEach { it.start() }
        gate.countDown()
        workers.forEach { it.join() }

        assertEquals(4, outcomes.count { it })
        assertEquals(4, prefs.getInt("alert_count_${vt.name}", 0))
    }
}
