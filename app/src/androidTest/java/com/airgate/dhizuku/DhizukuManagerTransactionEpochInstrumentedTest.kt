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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.WipeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device verification of the in-flight invalidation contract of
 * [DhizukuManager]: a transaction whose caller has timed out, been
 * interrupted, or seen the executor reject its submission refuses to call the
 * privileged platform API. The full Android JVM plus real binder thread pools
 * exercise the production code paths that the JVM-only suite cannot, in
 * particular the [java.util.concurrent.Future.cancel] interaction with a real
 * executor running on a real Looper-anchored process.
 */
@RunWith(AndroidJUnit4::class)
class DhizukuManagerTransactionEpochInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class RecordingWrapper : DhizukuBinderWrapper {
        val recordedKeys = java.util.Collections.synchronizedList(mutableListOf<String>())
        var wipeCallCount = 0
        override fun isPermissionGranted(): Boolean = true
        override fun bindUserService(componentName: ComponentName, connection: Any): Boolean = true
        override fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean {
            recordedKeys.add(key)
            return true
        }
        override fun addUserRestriction(admin: ComponentName, key: String): Boolean {
            recordedKeys.add("add:$key")
            return true
        }
        override fun clearUserRestriction(admin: ComponentName, key: String): Boolean {
            recordedKeys.add("clear:$key")
            return true
        }
        override fun wipeDevice(flags: Int): Boolean {
            wipeCallCount++
            return true
        }
    }

    private class BlockingWrapper : DhizukuBinderWrapper {
        val release = CountDownLatch(1)
        private fun block() { release.await(10, TimeUnit.SECONDS) }
        override fun isPermissionGranted(): Boolean { block(); return true }
        override fun bindUserService(componentName: ComponentName, connection: Any): Boolean { block(); return true }
        override fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean { block(); return true }
        override fun addUserRestriction(admin: ComponentName, key: String): Boolean { block(); return true }
        override fun clearUserRestriction(admin: ComponentName, key: String): Boolean { block(); return true }
        override fun wipeDevice(flags: Int): Boolean { block(); return true }
    }

    @Test
    fun timedOutWipe_failsClosedToREJECTEDOnDevice() {
        val wrapper = BlockingWrapper()
        val manager = DhizukuManager(context, wrapper, transactionTimeoutMs = 200)
        try {
            assertEquals(
                "a wedged wipe must fail closed to REJECTED on the device",
                WipeResult.REJECTED,
                manager.wipeDevice(0x8, AppConfig(dryRunMode = false))
            )
        } finally {
            wrapper.release.countDown()
            manager.close()
        }
    }

    @Test
    fun timedOutPolicyWrite_failsClosedOnDevice() {
        val wrapper = BlockingWrapper()
        val manager = DhizukuManager(context, wrapper, transactionTimeoutMs = 200)
        try {
            assertFalse(manager.setGlobalSetting("key", "value", AppConfig()))
            assertFalse(manager.addUserRestriction("restriction", AppConfig()))
            assertFalse(manager.clearUserRestriction("restriction", AppConfig()))
        } finally {
            wrapper.release.countDown()
            manager.close()
        }
    }

    @Test
    fun healthyTransaction_reachesTheWrapperOnDevice() {
        val wrapper = RecordingWrapper()
        val manager = DhizukuManager(context, wrapper)
        try {
            assertTrue(manager.setGlobalSetting("first", "1", AppConfig()))
            assertTrue(manager.addUserRestriction("r", AppConfig()))
            assertTrue(manager.clearUserRestriction("r", AppConfig()))
            assertEquals(WipeResult.ACCEPTED, manager.wipeDevice(0x8, AppConfig(dryRunMode = false)))
            assertEquals(1, wrapper.wipeCallCount)
            assertTrue(wrapper.recordedKeys.contains("first"))
            assertTrue(wrapper.recordedKeys.contains("add:r"))
            assertTrue(wrapper.recordedKeys.contains("clear:r"))
        } finally {
            manager.close()
        }
    }

    @Test
    fun queuedTransactionDoesNotInvokePrivilegedApiOnDevice() {
        val firstCallEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val recordedKeys = java.util.Collections.synchronizedList(mutableListOf<String>())

        val gatedWrapper = object : DhizukuBinderWrapper {
            override fun isPermissionGranted(): Boolean = true
            override fun bindUserService(componentName: ComponentName, connection: Any): Boolean = true
            override fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean {
                synchronized(recordedKeys) { recordedKeys.add(key) }
                firstCallEntered.countDown()
                if (key == "first") release.await(10, TimeUnit.SECONDS)
                return true
            }
            override fun addUserRestriction(admin: ComponentName, key: String): Boolean = true
            override fun clearUserRestriction(admin: ComponentName, key: String): Boolean = true
            override fun wipeDevice(flags: Int): Boolean = true
        }

        val manager = DhizukuManager(context, gatedWrapper, transactionTimeoutMs = 200)
        try {
            val t1Caller = Thread {
                assertFalse(manager.setGlobalSetting("first", "1", AppConfig()))
            }.apply { isDaemon = true }
            t1Caller.start()
            assertTrue(firstCallEntered.await(5, TimeUnit.SECONDS))

            val t2Caller = Thread {
                assertFalse(manager.setGlobalSetting("second", "1", AppConfig()))
            }.apply { isDaemon = true }
            t2Caller.start()

            t1Caller.join(5_000)
            t2Caller.join(5_000)

            release.countDown()
            Thread.sleep(500)

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
    fun queuedWipeDoesNotInvokePrivilegedWipeOnDevice() {
        val firstWipeEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val wipeCallCount = AtomicInteger(0)

        val wipeWrapper = object : DhizukuBinderWrapper {
            override fun isPermissionGranted(): Boolean = true
            override fun bindUserService(componentName: ComponentName, connection: Any): Boolean = true
            override fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean = true
            override fun addUserRestriction(admin: ComponentName, key: String): Boolean = true
            override fun clearUserRestriction(admin: ComponentName, key: String): Boolean = true
            override fun wipeDevice(flags: Int): Boolean {
                wipeCallCount.incrementAndGet()
                firstWipeEntered.countDown()
                releaseFirst.await(10, TimeUnit.SECONDS)
                return true
            }
        }

        val manager = DhizukuManager(context, wipeWrapper, transactionTimeoutMs = 200)
        try {
            val t1Caller = Thread {
                assertEquals(
                    WipeResult.REJECTED,
                    manager.wipeDevice(0x8, AppConfig(dryRunMode = false))
                )
            }.apply { isDaemon = true }
            t1Caller.start()
            assertTrue(firstWipeEntered.await(5, TimeUnit.SECONDS))

            val t2Caller = Thread {
                assertEquals(
                    WipeResult.REJECTED,
                    manager.wipeDevice(0x9, AppConfig(dryRunMode = false))
                )
            }.apply { isDaemon = true }
            t2Caller.start()

            t1Caller.join(5_000)
            t2Caller.join(5_000)

            releaseFirst.countDown()
            Thread.sleep(500)

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
    fun managerRecoversAfterTimeoutOnDevice() {
        val blocking = BlockingWrapper()
        val manager = DhizukuManager(context, blocking, transactionTimeoutMs = 100)
        try {
            assertFalse(manager.setGlobalSetting("first", "1", AppConfig()))
            blocking.release.countDown()
            assertTrue(manager.setGlobalSetting("second", "1", AppConfig()))
        } finally {
            manager.close()
        }
    }

    @Test
    fun noRealDhizuku_wipeWithoutDeviceOwner_isRejectedOnDevice() {
        // Sanity baseline: without a wrapper or device-owner authority the
        // wipe must fail closed. The in-flight guard does not change this
        // behaviour; this test pins that the device-side path is still
        // fail-closed after the refactor.
        val manager = DhizukuManager(context)
        try {
            assertEquals(
                WipeResult.REJECTED,
                manager.wipeDevice(0x8, AppConfig(dryRunMode = false))
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun noRealDhizuku_setGlobalSetting_isRejectedOnDevice() {
        val manager = DhizukuManager(context)
        try {
            assertFalse(manager.setGlobalSetting("key", "value", AppConfig()))
            assertFalse(manager.addUserRestriction("restriction", AppConfig()))
            assertFalse(manager.clearUserRestriction("restriction", AppConfig()))
        } finally {
            manager.close()
        }
    }

    @Test
    fun dryRunWipe_isSimulatedOnDevice_underTheGuard() {
        // Dry-run mode short-circuits before the in-flight guard, so a dry-run
        // wipe must still report SIMULATED without invoking the privileged API.
        val wrapper = RecordingWrapper()
        val manager = DhizukuManager(context, wrapper)
        try {
            assertEquals(WipeResult.SIMULATED, manager.wipeDevice(0x8, AppConfig(dryRunMode = true)))
            assertEquals(0, wrapper.wipeCallCount)
        } finally {
            manager.close()
        }
    }
}