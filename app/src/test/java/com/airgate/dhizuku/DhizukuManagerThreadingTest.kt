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

package com.airgate.dhizuku

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.WipeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pins the threading contract of [DhizukuManager]: every Dhizuku/DPM
 * transaction runs on the manager's own single serialized worker thread — never
 * on the calling (potentially main) thread — is bounded by a transaction timeout,
 * and fails closed on expiry or any failure instead of hanging or fabricating
 * success.
 */
@RunWith(AndroidJUnit4::class)
class DhizukuManagerThreadingTest {

    private class DummyContext : ContextWrapper(null) {
        override fun getPackageName(): String = "com.airgate"
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = null
    }

    private val context = DummyContext()

    private class RecordingWrapper : DhizukuBinderWrapper {
        val executingThreads = mutableListOf<String>()
        val globalSettings = mutableMapOf<String, String>()
        val userRestrictions = mutableSetOf<String>()
        var wipeCalled = false
        var wipeFlags = 0
        var wipeAccepted = true

        override fun isPermissionGranted(): Boolean = true

        override fun bindUserService(componentName: ComponentName, connection: Any): Boolean = true

        override fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean {
            executingThreads.add(Thread.currentThread().name)
            globalSettings[key] = value
            return true
        }

        override fun addUserRestriction(admin: ComponentName, key: String): Boolean {
            executingThreads.add(Thread.currentThread().name)
            userRestrictions.add(key)
            return true
        }

        override fun clearUserRestriction(admin: ComponentName, key: String): Boolean {
            executingThreads.add(Thread.currentThread().name)
            userRestrictions.remove(key)
            return true
        }

        override fun wipeDevice(flags: Int): Boolean {
            executingThreads.add(Thread.currentThread().name)
            wipeCalled = true
            wipeFlags = flags
            return wipeAccepted
        }
    }

    /**
     * Blocks every operation on a latch so any transaction can be held in-flight
     * deterministically to exercise the timeout path.
     */
    private class BlockingWrapper : DhizukuBinderWrapper {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        private fun block() {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }

        override fun isPermissionGranted(): Boolean {
            block()
            return true
        }

        override fun bindUserService(componentName: ComponentName, connection: Any): Boolean {
            block()
            return true
        }

        override fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean {
            block()
            return true
        }

        override fun addUserRestriction(admin: ComponentName, key: String): Boolean {
            block()
            return true
        }

        override fun clearUserRestriction(admin: ComponentName, key: String): Boolean {
            block()
            return true
        }

        override fun wipeDevice(flags: Int): Boolean {
            block()
            return true
        }
    }

    private class ThrowingWrapper : DhizukuBinderWrapper {
        override fun isPermissionGranted(): Boolean = true
        override fun bindUserService(componentName: ComponentName, connection: Any): Boolean = true
        override fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean =
            throw RuntimeException("binder failure")
        override fun addUserRestriction(admin: ComponentName, key: String): Boolean =
            throw RuntimeException("binder failure")
        override fun clearUserRestriction(admin: ComponentName, key: String): Boolean =
            throw RuntimeException("binder failure")
        override fun wipeDevice(flags: Int): Boolean = throw RuntimeException("binder failure")
    }

    /**
     * Tracks how many transactions are ever in flight at once. With a single
     * serialized worker the peak must stay at exactly 1 no matter how many
     * callers submit concurrently.
     */
    private class ConcurrencyProbeWrapper : DhizukuBinderWrapper {
        @Volatile
        var maxInFlight = 0
        private var inFlight = 0

        override fun isPermissionGranted(): Boolean = true
        override fun bindUserService(componentName: ComponentName, connection: Any): Boolean = true

        override fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            Thread.sleep(20)
            inFlight--
            return true
        }

        override fun addUserRestriction(admin: ComponentName, key: String): Boolean {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            Thread.sleep(20)
            inFlight--
            return true
        }

        override fun clearUserRestriction(admin: ComponentName, key: String): Boolean = true
        override fun wipeDevice(flags: Int): Boolean = true
    }

    // --- Transaction threading ---

    @Test
    fun `a policy write never executes on the calling thread`() {
        val wrapper = RecordingWrapper()
        val manager = DhizukuManager(context, wrapper)
        try {
            val callerThread = Thread.currentThread()
            val result = manager.setGlobalSetting("airplane_mode_on", "1", AppConfig())

            assertTrue(result)
            assertEquals("1", wrapper.globalSettings["airplane_mode_on"])
            assertEquals(1, wrapper.executingThreads.size)
            assertFalse("the transaction must run on the worker, not the caller", wrapper.executingThreads.first() == callerThread.name)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `the transaction runs on the dedicated dhizuku worker thread`() {
        val wrapper = RecordingWrapper()
        val manager = DhizukuManager(context, wrapper)
        try {
            manager.setGlobalSetting("airplane_mode_on", "1", AppConfig())

            assertEquals(listOf("dhizuku-transaction"), wrapper.executingThreads)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `consecutive transactions all serialize onto the one worker thread`() {
        val wrapper = RecordingWrapper()
        val manager = DhizukuManager(context, wrapper)
        try {
            repeat(12) { manager.setGlobalSetting("key$it", "1", AppConfig()) }

            assertEquals(12, wrapper.executingThreads.size)
            assertEquals(
                "every transaction must run on the same single worker thread",
                1,
                wrapper.executingThreads.distinct().size
            )
            assertEquals("dhizuku-transaction", wrapper.executingThreads.first())
        } finally {
            manager.close()
        }
    }

    @Test
    fun `concurrent submissions never overlap on the serialized worker`() {
        val wrapper = ConcurrencyProbeWrapper()
        val manager = DhizukuManager(context, wrapper)
        try {
            val callers = (1..8).map {
                Thread { manager.setGlobalSetting("key", "1", AppConfig()) }
            }
            callers.forEach { it.start() }
            callers.forEach { it.join(5_000) }

            assertEquals(
                "a single-thread executor must serialize every transaction",
                1,
                wrapper.maxInFlight
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun `wipe and policy operations return their real results through the executor`() {
        val wrapper = RecordingWrapper()
        val manager = DhizukuManager(context, wrapper)
        try {
            assertEquals(WipeResult.ACCEPTED, manager.wipeDevice(0x8, AppConfig(dryRunMode = false)))
            assertTrue(wrapper.wipeCalled)
            assertEquals(0x8, wrapper.wipeFlags)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `a refused wipe is reported as REJECTED through the executor`() {
        val wrapper = RecordingWrapper().apply { wipeAccepted = false }
        val manager = DhizukuManager(context, wrapper)
        try {
            assertEquals(WipeResult.REJECTED, manager.wipeDevice(0x8, AppConfig(dryRunMode = false)))
            assertTrue(wrapper.wipeCalled)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `isDhizukuAvailable reports the grant through the executor`() {
        val wrapper = RecordingWrapper()
        val manager = DhizukuManager(context, wrapper)
        try {
            assertTrue(manager.isDhizukuAvailable())
        } finally {
            manager.close()
        }
    }

    @Test
    fun `requestPermission reports the grant through the executor`() {
        val wrapper = RecordingWrapper()
        val manager = DhizukuManager(context, wrapper)
        try {
            assertTrue(manager.requestPermission(context))
        } finally {
            manager.close()
        }
    }

    @Test
    fun `the transaction worker thread is a daemon so it cannot pin the process`() {
        val wrapper = RecordingWrapper()
        val manager = DhizukuManager(context, wrapper)
        try {
            manager.setGlobalSetting("airplane_mode_on", "1", AppConfig())
            val worker = Thread.getAllStackTraces().keys.firstOrNull { it.name == "dhizuku-transaction" }
            assertTrue("the worker thread must exist", worker != null)
            assertTrue("the worker must be a daemon thread", worker!!.isDaemon)
        } finally {
            manager.close()
        }
    }

    // --- Bounded timeout, fail closed ---

    @Test
    fun `a wedged transaction fails closed to false after the timeout`() {
        val wrapper = BlockingWrapper()
        val manager = DhizukuManager(context, wrapper, transactionTimeoutMs = 50)
        try {
            val result = manager.setGlobalSetting("airplane_mode_on", "1", AppConfig())

            assertEquals("a wedged policy write must fail closed", false, result)
        } finally {
            wrapper.release.countDown()
            manager.close()
        }
    }

    @Test
    fun `a wedged wipe fails closed to REJECTED after the timeout`() {
        val wrapper = BlockingWrapper()
        val manager = DhizukuManager(context, wrapper, transactionTimeoutMs = 50)
        try {
            val result = manager.wipeDevice(0x8, AppConfig(dryRunMode = false))

            assertEquals(
                "a wedged wipe must never report acceptance",
                WipeResult.REJECTED,
                result
            )
        } finally {
            wrapper.release.countDown()
            manager.close()
        }
    }

    @Test
    fun `a wedged availability check fails closed to false after the timeout`() {
        val wrapper = BlockingWrapper()
        val manager = DhizukuManager(context, wrapper, transactionTimeoutMs = 50)
        try {
            assertFalse(manager.isDhizukuAvailable())
        } finally {
            wrapper.release.countDown()
            manager.close()
        }
    }

    @Test
    fun `a write queued behind a wedged transaction fails closed to false`() {
        val wrapper = BlockingWrapper()
        val manager = DhizukuManager(context, wrapper, transactionTimeoutMs = 50)
        try {
            // Occupy the single worker with a wedged transaction, then submit a
            // policy write that can only run once the worker drains. It must time
            // out while queued and fail closed (false), never hang and never wait
            // for the blocked task to complete.
            manager.setGlobalSetting("airplane_mode_on", "1", AppConfig())
            assertEquals(false, manager.addUserRestriction("test_restriction", AppConfig()))
        } finally {
            wrapper.release.countDown()
            manager.close()
        }
    }

    @Test
    fun `the manager recovers once a wedged transaction completes`() {
        val wrapper = BlockingWrapper()
        val manager = DhizukuManager(context, wrapper, transactionTimeoutMs = 50)
        try {
            // A wedged transaction fails closed after the timeout...
            assertEquals(false, manager.setGlobalSetting("airplane_mode_on", "1", AppConfig()))
            // ...but once the Dhizuku server responds (the worker unblocks), the
            // executor drains the wedged task and later transactions succeed again.
            wrapper.release.countDown()
            assertEquals(true, manager.setGlobalSetting("airplane_mode_on", "1", AppConfig()))
        } finally {
            manager.close()
        }
    }

    @Test
    fun `an interrupted caller fails closed and is re-interrupted`() {
        val wrapper = BlockingWrapper()
        val manager = DhizukuManager(context, wrapper, transactionTimeoutMs = 500)
        try {
            val result = java.util.concurrent.atomic.AtomicReference<Boolean>()
            val reInterrupted = java.util.concurrent.atomic.AtomicBoolean()
            val caller = Thread {
                result.set(manager.setGlobalSetting("airplane_mode_on", "1", AppConfig()))
                reInterrupted.set(Thread.currentThread().isInterrupted)
            }.apply { isDaemon = true }
            caller.start()

            // Wait until the transaction is in-flight (the worker is blocked in the
            // wrapper), then interrupt the waiting caller.
            assertTrue("the wedged transaction must start", wrapper.started.await(2, TimeUnit.SECONDS))
            caller.interrupt()
            caller.join(3_000)

            assertEquals(false, result.get())
            assertTrue("the interrupt must be re-asserted on the caller thread", reInterrupted.get())
        } finally {
            wrapper.release.countDown()
            manager.close()
        }
    }

    @Test
    fun `the default transaction timeout keeps a main-thread caller under the ANR bound`() {
        // A bounded wait on the UI thread must stay below the 5s ANR input-dispatch
        // threshold; the default is the bound that Settings/RequiredPermissions use.
        assertTrue(
            "a bounded UI-thread wait must stay under the 5s ANR threshold",
            DhizukuManager.DEFAULT_TRANSACTION_TIMEOUT_MS < 5_000L
        )
        assertTrue(DhizukuManager.DEFAULT_TRANSACTION_TIMEOUT_MS > 0L)
    }

    @Test
    fun `a timed-out transaction returns within the bound and does not hang`() {
        val wrapper = BlockingWrapper()
        val manager = DhizukuManager(context, wrapper, transactionTimeoutMs = 100)
        try {
            val started = System.nanoTime()
            manager.setGlobalSetting("airplane_mode_on", "1", AppConfig())
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertTrue(
                "a timed-out transaction must return promptly (took ${elapsedMs}ms)",
                elapsedMs < 3_000
            )
        } finally {
            wrapper.release.countDown()
            manager.close()
        }
    }

    @Test
    fun `a throwing transaction fails closed instead of propagating`() {
        val manager = DhizukuManager(context, ThrowingWrapper())
        try {
            assertEquals(false, manager.setGlobalSetting("adb_enabled", "0", AppConfig()))
            assertEquals(false, manager.addUserRestriction("restriction", AppConfig()))
            assertEquals(false, manager.clearUserRestriction("restriction", AppConfig()))
            assertEquals(WipeResult.REJECTED, manager.wipeDevice(0x8, AppConfig(dryRunMode = false)))
        } finally {
            manager.close()
        }
    }

    // --- Lifecycle ---

    @Test
    fun `calls after close fail closed instead of throwing`() {
        val wrapper = RecordingWrapper()
        val manager = DhizukuManager(context, wrapper)
        manager.close()

        assertEquals(false, manager.setGlobalSetting("airplane_mode_on", "1", AppConfig()))
        assertEquals(WipeResult.REJECTED, manager.wipeDevice(0x8, AppConfig(dryRunMode = false)))
        assertEquals(false, manager.isDhizukuAvailable())
    }
}
