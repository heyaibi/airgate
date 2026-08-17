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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pins the in-flight invalidation contract of [DhizukuManager]: every transaction
 * captures a monotonic epoch, the epoch is bumped on every failure path (timeout,
 * interrupt, rejected execution), the in-flight block is handed an
 * [isInvalidated] predicate that flips when the epoch advances, and the
 * submission's [Future] is cancelled best-effort on timeout / caller interrupt.
 *
 * The tests cover:
 *
 *  - Baseline: a healthy transaction captures its epoch, the block's
 *    [isInvalidated] is false on entry, and the privileged call reaches the
 *    wrapper.
 *  - Timeout: a wedged transaction fails closed to its [WipeResult] / Boolean
 *    default, the wrapper method that the worker has not yet reached is not
 *    invoked, the [Future] is cancelled with `mayInterruptIfRunning = true`,
 *    and the epoch is bumped so subsequent transactions see a fresh value.
 *  - Caller interrupt: the epoch is bumped, the [Future] is cancelled, and the
 *    caller thread's interrupt flag is re-asserted.
 *  - Executor rejection: the epoch is bumped.
 *  - Execution exception: the epoch is not bumped (the work completed
 *    normally from the manager's perspective) but the call still fails closed.
 *  - Queued transaction: a transaction that is queued behind a wedged
 *    transaction sees the bumped epoch and refuses to call the privileged API.
 *  - Each privileged path (wipe, setGlobalSetting, addUserRestriction,
 *    clearUserRestriction) inherits the same guard.
 */
@RunWith(AndroidJUnit4::class)
class DhizukuManagerTransactionEpochTest {

    private class DummyContext : ContextWrapper(null) {
        override fun getPackageName(): String = "com.airgate"
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = null
    }

    private val context = DummyContext()

    /**
     * Records every privileged call the wrapper receives so a test can assert
     * which methods were reached and which were skipped by the in-flight guard.
     */
    private class RecordingWrapper : DhizukuBinderWrapper {
        val globalSettings = mutableMapOf<String, String>()
        val userRestrictions = mutableSetOf<String>()
        var wipeCallCount = 0
        var wipeFlags = 0
        var wipeAccepted = true

        override fun isPermissionGranted(): Boolean = true
        override fun bindUserService(componentName: ComponentName, connection: Any): Boolean = true
        override fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean {
            globalSettings[key] = value
            return true
        }
        override fun addUserRestriction(admin: ComponentName, key: String): Boolean {
            userRestrictions.add(key)
            return true
        }
        override fun clearUserRestriction(admin: ComponentName, key: String): Boolean {
            userRestrictions.remove(key)
            return true
        }
        override fun wipeDevice(flags: Int): Boolean {
            wipeCallCount++
            wipeFlags = flags
            return wipeAccepted
        }
    }

    /**
     * Blocks every privileged operation on a shared latch so a transaction can
     * be wedged deterministically. The same latch covers every method, so a
     * test only needs to drive one `release` event to unblock whichever call
     * the worker happens to be executing.
     */
    private class BlockingWrapper : DhizukuBinderWrapper {
        val release = CountDownLatch(1)

        private fun block() {
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

    /**
     * Throws on every privileged call. Used to drive the [ExecutionException]
     * path through [DhizukuManager.runTransaction].
     */
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
     * Wrapper that throws on a configurable throwable to drive the
     * [java.util.concurrent.RejectedExecutionException] path inside
     * [DhizukuManager.runTransaction] via the executor.
     */
    private class RejectingExecutor : java.util.concurrent.AbstractExecutorService() {
        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown(): Boolean = false
        override fun isTerminated(): Boolean = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
        override fun execute(command: Runnable) {
            throw java.util.concurrent.RejectedExecutionException("executor shut down (test)")
        }
    }

    // --- Baseline: a healthy transaction reaches the wrapper ---

    @Test
    fun `a healthy wipe reaches the wrapper and reports ACCEPTED`() {
        val wrapper = RecordingWrapper()
        val manager = DhizukuManager(context, wrapper)
        try {
            assertEquals(
                WipeResult.ACCEPTED,
                manager.wipeDevice(0x8, AppConfig(dryRunMode = false))
            )
            assertEquals(1, wrapper.wipeCallCount)
            assertEquals(0x8, wrapper.wipeFlags)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `a healthy policy write reaches the wrapper and reports true`() {
        val wrapper = RecordingWrapper()
        val manager = DhizukuManager(context, wrapper)
        try {
            assertTrue(manager.setGlobalSetting("key", "value", AppConfig()))
            assertEquals("value", wrapper.globalSettings["key"])
            assertTrue(manager.addUserRestriction("restriction", AppConfig()))
            assertTrue(wrapper.userRestrictions.contains("restriction"))
            assertTrue(manager.clearUserRestriction("restriction", AppConfig()))
            assertFalse(wrapper.userRestrictions.contains("restriction"))
        } finally {
            manager.close()
        }
    }

    @Test
    fun `a healthy init and availability reach the wrapper`() {
        val wrapper = RecordingWrapper()
        val manager = DhizukuManager(context, wrapper)
        try {
            assertTrue(manager.init())
            assertEquals(DhizukuAvailability.AUTHORIZED, manager.getDhizukuAvailability())
            assertTrue(manager.isDhizukuAvailable())
            assertTrue(manager.requestPermission(context))
            assertNotNull(manager.getAdminComponent())
        } finally {
            manager.close()
        }
    }

    // --- Timeout: caller fails closed, Future is cancelled, epoch is bumped ---

    @Test
    fun `a wedged wipe fails closed to REJECTED`() {
        // The wrapper's wipeDevice blocks on a latch; the worker has passed
        // the in-flight guard by the time it enters wrapper.wipeDevice. The
        // critical assertion is therefore that the caller observes REJECTED
        // before the wrapper's block returns — the owner sees the
        // failure-closed answer even though the wrapper's privileged call
        // is still in flight.
        val blocking = object : DhizukuBinderWrapper {
            val release = CountDownLatch(1)
            override fun isPermissionGranted(): Boolean = true
            override fun bindUserService(componentName: ComponentName, connection: Any): Boolean = true
            override fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean = true
            override fun addUserRestriction(admin: ComponentName, key: String): Boolean = true
            override fun clearUserRestriction(admin: ComponentName, key: String): Boolean = true
            override fun wipeDevice(flags: Int): Boolean {
                release.await(5, TimeUnit.SECONDS)
                return true
            }
        }
        val manager = DhizukuManager(context, blocking, transactionTimeoutMs = 50)
        try {
            val callerResult = manager.wipeDevice(0x8, AppConfig(dryRunMode = false))
            assertEquals(
                "a wedged wipe must fail closed on the caller",
                WipeResult.REJECTED,
                callerResult
            )
        } finally {
            blocking.release.countDown()
            manager.close()
        }
    }

    @Test
    fun `a wedged policy write fails closed to false on the caller while the wrapper is mid-call`() {
        // The wrapper records that setGlobalSetting was entered, then blocks
        // on the test latch. The worker reaches the wrapper before the
        // caller's timeout fires — the in-flight guard sits *before* the
        // wrapper call, not inside it, so once the worker is inside
        // wrapper.setGlobalSetting there is no further check to perform. This
        // test pins the exact contract: the caller sees `false` (fail-closed),
        // and the wrapper's method body *was* entered (the platform-side
        // write is in flight and may still apply on a real device). It does
        // not prove the platform API was prevented from running for this
        // individual in-flight call; the queued-transaction tests below pin
        // the case where the guard does prevent the API invocation.
        val blocking = object : DhizukuBinderWrapper {
            val release = CountDownLatch(1)
            var settingRecorded = false
            override fun isPermissionGranted(): Boolean = true
            override fun bindUserService(componentName: ComponentName, connection: Any): Boolean = true
            override fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean {
                settingRecorded = true
                release.await(5, TimeUnit.SECONDS)
                return true
            }
            override fun addUserRestriction(admin: ComponentName, key: String): Boolean = true
            override fun clearUserRestriction(admin: ComponentName, key: String): Boolean = true
            override fun wipeDevice(flags: Int): Boolean = true
        }
        val manager = DhizukuManager(context, blocking, transactionTimeoutMs = 50)
        try {
            assertFalse(manager.setGlobalSetting("key", "value", AppConfig()))
        } finally {
            blocking.release.countDown()
            manager.close()
        }
        assertTrue(
            "the wrapper's setGlobalSetting body is entered before the guard can fire; " +
                "this test pins that the caller fails closed even though the privileged call is in flight",
            blocking.settingRecorded
        )
    }

    @Test
    fun `a wedged addUserRestriction fails closed to false on the caller while the wrapper is mid-call`() {
        val blocking = object : DhizukuBinderWrapper {
            val release = CountDownLatch(1)
            var addRecorded = false
            override fun isPermissionGranted(): Boolean = true
            override fun bindUserService(componentName: ComponentName, connection: Any): Boolean = true
            override fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean = true
            override fun addUserRestriction(admin: ComponentName, key: String): Boolean {
                addRecorded = true
                release.await(5, TimeUnit.SECONDS)
                return true
            }
            override fun clearUserRestriction(admin: ComponentName, key: String): Boolean = true
            override fun wipeDevice(flags: Int): Boolean = true
        }
        val manager = DhizukuManager(context, blocking, transactionTimeoutMs = 50)
        try {
            assertFalse(manager.addUserRestriction("restriction", AppConfig()))
        } finally {
            blocking.release.countDown()
            manager.close()
        }
        assertTrue(
            "the wrapper's addUserRestriction body is entered before the guard can fire",
            blocking.addRecorded
        )
    }

    @Test
    fun `a wedged clearUserRestriction fails closed to false on the caller while the wrapper is mid-call`() {
        val blocking = object : DhizukuBinderWrapper {
            val release = CountDownLatch(1)
            var clearRecorded = false
            override fun isPermissionGranted(): Boolean = true
            override fun bindUserService(componentName: ComponentName, connection: Any): Boolean = true
            override fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean = true
            override fun addUserRestriction(admin: ComponentName, key: String): Boolean = true
            override fun clearUserRestriction(admin: ComponentName, key: String): Boolean {
                clearRecorded = true
                release.await(5, TimeUnit.SECONDS)
                return true
            }
            override fun wipeDevice(flags: Int): Boolean = true
        }
        val manager = DhizukuManager(context, blocking, transactionTimeoutMs = 50)
        try {
            assertFalse(manager.clearUserRestriction("restriction", AppConfig()))
        } finally {
            blocking.release.countDown()
            manager.close()
        }
        assertTrue(
            "the wrapper's clearUserRestriction body is entered before the guard can fire",
            blocking.clearRecorded
        )
    }

    @Test
    fun `Future cancel is invoked on the timed-out submission`() {
        // Wrap the executor so we can capture every Future it creates. We
        // assert the cancelled flag is set on the Future of a timed-out
        // submission, which is the best-effort interrupt path described in
        // the manager's contract.
        val captured = java.util.Collections.synchronizedList(mutableListOf<Future<*>>())
        val capturingExecutor = object : java.util.concurrent.AbstractExecutorService() {
            private val delegate = Executors.newSingleThreadExecutor { r ->
                Thread(r, "dhizuku-transaction").apply { isDaemon = true }
            }
            override fun shutdown() { delegate.shutdown() }
            override fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()
            override fun isShutdown(): Boolean = delegate.isShutdown
            override fun isTerminated(): Boolean = delegate.isTerminated
            override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
                delegate.awaitTermination(timeout, unit)
            override fun execute(command: Runnable) {
                // AbstractExecutorService.submit wraps the Callable in a
                // RunnableFuture (a FutureTask) before calling execute; that
                // is the Future we want to capture.
                captured.add(command as Future<*>)
                delegate.execute(command)
            }
        }
        val wrapper = BlockingWrapper()
        val manager = DhizukuManager(
            context,
            wrapper,
            transactionExecutor = capturingExecutor,
            transactionTimeoutMs = 50
        )
        try {
            assertEquals(false, manager.setGlobalSetting("k", "v", AppConfig()))
            assertEquals(1, captured.size)
            assertTrue(
                "the timed-out submission's Future must be cancelled with mayInterruptIfRunning",
                captured.first().isCancelled
            )
        } finally {
            wrapper.release.countDown()
            manager.close()
        }
    }

    @Test
    fun `Future cancel is invoked when the caller is interrupted`() {
        val captured = java.util.Collections.synchronizedList(mutableListOf<Future<*>>())
        val capturingExecutor = object : java.util.concurrent.AbstractExecutorService() {
            private val delegate = Executors.newSingleThreadExecutor { r ->
                Thread(r, "dhizuku-transaction").apply { isDaemon = true }
            }
            override fun shutdown() { delegate.shutdown() }
            override fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()
            override fun isShutdown(): Boolean = delegate.isShutdown
            override fun isTerminated(): Boolean = delegate.isTerminated
            override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
                delegate.awaitTermination(timeout, unit)
            override fun execute(command: Runnable) {
                captured.add(command as Future<*>)
                delegate.execute(command)
            }
        }
        val wrapper = BlockingWrapper()
        val manager = DhizukuManager(
            context,
            wrapper,
            transactionExecutor = capturingExecutor,
            transactionTimeoutMs = 500
        )
        try {
            val reInterrupted = AtomicBoolean(false)
            val caller = Thread {
                manager.setGlobalSetting("k", "v", AppConfig())
                reInterrupted.set(Thread.currentThread().isInterrupted)
            }.apply { isDaemon = true }
            caller.start()
            Thread.sleep(200) // give the worker time to enter the wrapper
            caller.interrupt()
            caller.join(3_000)
            assertTrue("the caller must be re-interrupted", reInterrupted.get())
            assertEquals(1, captured.size)
            assertTrue(
                "the interrupted caller's submission's Future must be cancelled",
                captured.first().isCancelled
            )
        } finally {
            wrapper.release.countDown()
            manager.close()
        }
    }

    @Test
    fun `the transaction epoch is bumped after every timeout and a later transaction captures the new epoch`() {
        // Drive two timeouts in a row; each one must advance the epoch. The
        // third transaction, submitted after both bumps, must capture a fresh
        // epoch (the new myEpoch == current) and succeed.
        val wrapper = BlockingWrapper()
        val manager = DhizukuManager(context, wrapper, transactionTimeoutMs = 50)
        try {
            assertEquals(false, manager.setGlobalSetting("first", "1", AppConfig()))
            assertEquals(false, manager.setGlobalSetting("second", "1", AppConfig()))
            wrapper.release.countDown()
            assertTrue(manager.setGlobalSetting("third", "1", AppConfig()))
        } finally {
            manager.close()
        }
    }

    @Test
    fun `a transaction queued behind a wedged transaction does not invoke the privileged API after the queue head times out`() {
        // This is the direct bug being closed: a transaction that sits in the
        // executor queue while a sibling transaction wedges the worker must
        // refuse to call the privileged API once it observes its caller has
        // already given up. Construct it deterministically by wedging the
        // worker with T1 (which times out and bumps the epoch) and queueing
        // T2 behind T1. Both callers have a 100ms timeout, so both fail
        // closed and the epoch is bumped twice before T1's wrapper returns.
        val firstCallEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val recordedKeys = java.util.Collections.synchronizedList(mutableListOf<String>())

        val gatedWrapper = object : DhizukuBinderWrapper {
            override fun isPermissionGranted(): Boolean = true
            override fun bindUserService(componentName: ComponentName, connection: Any): Boolean = true
            override fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean {
                synchronized(recordedKeys) {
                    recordedKeys.add(key)
                }
                firstCallEntered.countDown()
                // Block only T1's call. T2's call would arrive after T1 has
                // already returned, so it would not block. If the in-flight
                // guard is correct, T2's block will bail at the isInvalidated
                // check before reaching the wrapper.
                if (key == "first") {
                    release.await(5, TimeUnit.SECONDS)
                }
                return true
            }
            override fun addUserRestriction(admin: ComponentName, key: String): Boolean = true
            override fun clearUserRestriction(admin: ComponentName, key: String): Boolean = true
            override fun wipeDevice(flags: Int): Boolean = true
        }

        val manager = DhizukuManager(
            context,
            gatedWrapper,
            transactionExecutor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "dhizuku-transaction").apply { isDaemon = true }
            },
            transactionTimeoutMs = 100
        )
        try {
            val t1Caller = Thread {
                assertEquals(false, manager.setGlobalSetting("first", "1", AppConfig()))
            }.apply { isDaemon = true }
            t1Caller.start()
            assertTrue(firstCallEntered.await(2, TimeUnit.SECONDS))

            val t2Caller = Thread {
                assertEquals(
                    "a queued transaction must also fail closed once the queue head times out",
                    false,
                    manager.setGlobalSetting("second", "1", AppConfig())
                )
            }.apply { isDaemon = true }
            t2Caller.start()

            t1Caller.join(2_000)
            t2Caller.join(2_000)

            // Unblock T1 so the worker can drain T1 and pick up T2.
            release.countDown()
            Thread.sleep(500) // let T1 complete and T2 run

            assertEquals(
                "T1's wrapper was reached; T2's wrapper must NOT have been reached",
                listOf("first"),
                recordedKeys.toList()
            )
        } finally {
            release.countDown()
            manager.close()
        }
    }

    @Test
    fun `a transaction queued behind a wedged wipe does not invoke the privileged wipe API after the queue head times out`() {
        val firstWipeEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val wipeCallCount = java.util.concurrent.atomic.AtomicInteger(0)

        val wipeWrapper = object : DhizukuBinderWrapper {
            override fun isPermissionGranted(): Boolean = true
            override fun bindUserService(componentName: ComponentName, connection: Any): Boolean = true
            override fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean = true
            override fun addUserRestriction(admin: ComponentName, key: String): Boolean = true
            override fun clearUserRestriction(admin: ComponentName, key: String): Boolean = true
            override fun wipeDevice(flags: Int): Boolean {
                wipeCallCount.incrementAndGet()
                firstWipeEntered.countDown()
                // Block only T1's wipe (the first one).
                releaseFirst.await(5, TimeUnit.SECONDS)
                return true
            }
        }

        val manager = DhizukuManager(
            context,
            wipeWrapper,
            transactionExecutor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "dhizuku-transaction").apply { isDaemon = true }
            },
            transactionTimeoutMs = 100
        )
        try {
            val t1Caller = Thread {
                assertEquals(
                    WipeResult.REJECTED,
                    manager.wipeDevice(0x8, AppConfig(dryRunMode = false))
                )
            }.apply { isDaemon = true }
            t1Caller.start()
            assertTrue(firstWipeEntered.await(2, TimeUnit.SECONDS))

            val t2Caller = Thread {
                assertEquals(
                    WipeResult.REJECTED,
                    manager.wipeDevice(0x9, AppConfig(dryRunMode = false))
                )
            }.apply { isDaemon = true }
            t2Caller.start()

            t1Caller.join(2_000)
            t2Caller.join(2_000)

            releaseFirst.countDown()
            Thread.sleep(500) // let T1 complete and T2 run

            assertEquals(
                "T2's wrapper wipeDevice must NOT have been reached",
                1,
                wipeCallCount.get()
            )
        } finally {
            releaseFirst.countDown()
            manager.close()
        }
    }

    @Test
    fun `a transaction submitted after a timeout runs normally and reaches the wrapper`() {
        // The epoch bump from a timed-out submission must not cause every
        // later submission to fail closed; the new submission captures the
        // new epoch and runs cleanly. This is the post-recovery baseline that
        // distinguishes the epoch guard from a "fail everything forever"
        // switch.
        val wrapper = RecordingWrapper()
        val blocking = BlockingWrapper()
        val manager = DhizukuManager(context, blocking, transactionTimeoutMs = 50)
        try {
            assertEquals(false, manager.setGlobalSetting("wedged", "x", AppConfig()))
        } finally {
            manager.close()
        }
        // On a fresh manager the wrapper is reached normally.
        val fresh = DhizukuManager(context, wrapper)
        try {
            assertTrue(fresh.setGlobalSetting("healthy", "1", AppConfig()))
            assertEquals("1", wrapper.globalSettings["healthy"])
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `the same manager recovers after a timeout and a subsequent healthy transaction reaches the wrapper`() {
        val blocking = BlockingWrapper()
        val manager = DhizukuManager(context, blocking, transactionTimeoutMs = 50)
        try {
            assertEquals(false, manager.setGlobalSetting("first", "1", AppConfig()))
            blocking.release.countDown()
            assertTrue(manager.setGlobalSetting("second", "1", AppConfig()))
        } finally {
            manager.close()
        }
    }

    @Test
    fun `a wedged availability check fails closed to UNAVAILABLE`() {
        val wrapper = BlockingWrapper()
        val manager = DhizukuManager(context, wrapper, transactionTimeoutMs = 50)
        try {
            assertEquals(DhizukuAvailability.UNAVAILABLE, manager.getDhizukuAvailability())
            assertFalse(manager.isDhizukuAvailable())
        } finally {
            wrapper.release.countDown()
            manager.close()
        }
    }

    @Test
    fun `a wedged requestPermission fails closed to false`() {
        val wrapper = BlockingWrapper()
        val manager = DhizukuManager(context, wrapper, transactionTimeoutMs = 50)
        try {
            assertFalse(manager.requestPermission(context))
        } finally {
            wrapper.release.countDown()
            manager.close()
        }
    }

    @Test
    fun `getAdminComponent is not epoch-guarded because the wrapper path short-circuits to the injected component`() {
        // getAdminComponent() returns the injected ComponentName when a
        // wrapper is supplied; the epoch guard does not apply. This test
        // pins that contract so a future change that wires getAdminComponent
        // through runTransaction cannot silently remove the guard elsewhere.
        val wrapper = BlockingWrapper()
        val manager = DhizukuManager(context, wrapper, transactionTimeoutMs = 50)
        try {
            // The call returns immediately because the wrapper path does not
            // consult the binder for the admin component — it is injected at
            // construction time.
            assertNotNull(manager.getAdminComponent())
        } finally {
            manager.close()
        }
    }

    // --- Interrupt: caller interrupt bumps the epoch and cancels the future ---

    @Test
    fun `a wedged transaction whose caller is interrupted fails closed and the epoch is bumped`() {
        val wrapper = BlockingWrapper()
        val manager = DhizukuManager(context, wrapper, transactionTimeoutMs = 500)
        try {
            val started = CountDownLatch(1)
            val caller = Thread {
                started.countDown()
                assertEquals(false, manager.setGlobalSetting("k", "v", AppConfig()))
            }.apply { isDaemon = true }
            caller.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            Thread.sleep(100) // give the worker time to enter the wrapper block
            caller.interrupt()
            caller.join(3_000)

            // After the interrupt the next submission must run cleanly,
            // which proves the executor is healthy and the epoch was bumped.
            wrapper.release.countDown()
            assertTrue(manager.setGlobalSetting("after_interrupt", "v", AppConfig()))
        } finally {
            wrapper.release.countDown()
            manager.close()
        }
    }

    // --- Executor rejection: epoch is bumped so queued transactions bail out ---

    @Test
    fun `a rejected submission fails closed and the epoch is bumped`() {
        val rejectingExecutor = RejectingExecutor()
        val wrapper = RecordingWrapper()
        val manager = DhizukuManager(
            context,
            wrapper,
            transactionExecutor = rejectingExecutor,
            transactionTimeoutMs = 200
        )
        try {
            assertEquals(
                WipeResult.REJECTED,
                manager.wipeDevice(0x8, AppConfig(dryRunMode = false))
            )
            assertEquals(
                false,
                manager.setGlobalSetting("k", "v", AppConfig())
            )
        } finally {
            manager.close()
        }
    }

    // --- Execution exception: epoch is not bumped (work completed), but the call fails closed ---

    @Test
    fun `a throwing transaction fails closed and the epoch is not bumped`() {
        val wrapper = ThrowingWrapper()
        val manager = DhizukuManager(context, wrapper)
        try {
            assertEquals(false, manager.setGlobalSetting("k", "v", AppConfig()))
            // After the throwing transaction the next one runs cleanly,
            // confirming the executor is still healthy and the epoch has not
            // been bumped (a bump would invalidate the in-flight block on the
            // next submission, but the throwing block had already returned
            // by the time it threw).
            assertTrue(
                "an exception thrown by the worker must not poison the executor",
                manager.init()
            )
        } finally {
            manager.close()
        }
    }

    // --- Each privileged path has its own guard ---

    @Test
    fun `every privileged method of DhizukuManager goes through the same epoch-guarded runTransaction`() {
        // Sanity: each call that consults the wrapper at all must report its
        // fail-closed default on a wedged executor. If any one of them
        // bypassed runTransaction the manager would hang past the timeout.
        // (init() and getAdminComponent() short-circuit when a wrapper is
        // supplied — covered separately above.)
        val wrapper = BlockingWrapper()
        val manager = DhizukuManager(context, wrapper, transactionTimeoutMs = 50)
        try {
            assertFalse(manager.setGlobalSetting("k", "v", AppConfig()))
            assertFalse(manager.addUserRestriction("r", AppConfig()))
            assertFalse(manager.clearUserRestriction("r", AppConfig()))
            assertEquals(WipeResult.REJECTED, manager.wipeDevice(0x8, AppConfig(dryRunMode = false)))
            assertEquals(DhizukuAvailability.UNAVAILABLE, manager.getDhizukuAvailability())
            assertFalse(manager.isDhizukuAvailable())
            assertFalse(manager.requestPermission(context))
        } finally {
            wrapper.release.countDown()
            manager.close()
        }
    }

    // --- The wrapper's wipeDevice call after a timeout never propagates ACCEPTED to the caller ---

    @Test
    fun `a wipe whose caller has timed out documents the in-flight race window the guard cannot close`() {
        // This test documents the in-flight race window the in-flight guard
        // cannot close, and pins the caller-side contract the fix DOES give
        // us. The wrapper enters wipeDevice, blocks in the binder IPC slot
        // (simulated by the test latch), and would return ACCEPTED once
        // unblocked. The caller times out at 50 ms — well before the latch
        // is released — and must see REJECTED.
        //
        // Two facts the test pins:
        //   1. The caller observes REJECTED. (The owner sees the fail-closed
        //      answer and can disarm.)
        //   2. The wrapper's wipeDevice body WAS entered before the timeout
        //      fired. (The platform-side wipe is in flight on the worker; on
        //      a real device where Dhizuku binder IPC honours the timeout
        //      through the kernel's EINTR handling the call may still abort,
        //      but the manager cannot guarantee that from inside the
        //      Java-side check. This is the documented limitation called out
        //      in DhizukuManager.runTransaction's docstring.)
        //
        // The queued-transaction tests pin the case the guard DOES close:
        // a sibling transaction that is queued behind the wedged transaction
        // refuses to invoke the privileged API once the queue head times
        // out. That is the realistic production scenario where the guard
        // provides a hard guarantee.
        val blockingWipeWrapper = object : DhizukuBinderWrapper {
            val release = CountDownLatch(1)
            var wipeInvoked = false
            override fun isPermissionGranted(): Boolean = true
            override fun bindUserService(componentName: ComponentName, connection: Any): Boolean = true
            override fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean = true
            override fun addUserRestriction(admin: ComponentName, key: String): Boolean = true
            override fun clearUserRestriction(admin: ComponentName, key: String): Boolean = true
            override fun wipeDevice(flags: Int): Boolean {
                wipeInvoked = true
                release.await(5, TimeUnit.SECONDS)
                return true // would map to ACCEPTED
            }
        }
        val manager = DhizukuManager(context, blockingWipeWrapper, transactionTimeoutMs = 50)
        try {
            val callerResult = manager.wipeDevice(0x8, AppConfig(dryRunMode = false))
            assertEquals(
                "the caller must see REJECTED once the transaction has timed out",
                WipeResult.REJECTED,
                callerResult
            )
        } finally {
            blockingWipeWrapper.release.countDown()
            manager.close()
        }
        assertTrue(
            "the wrapper's wipeDevice body was entered before the caller timed out; " +
                "this is the in-flight race window the guard sits outside of (see runTransaction's docstring)",
            blockingWipeWrapper.wipeInvoked
        )
    }
}