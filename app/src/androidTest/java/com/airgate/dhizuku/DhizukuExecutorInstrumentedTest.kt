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
import android.os.Looper
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

/**
 * On-device verification of the [DhizukuManager] threading contract: every
 * Dhizuku/DPM transaction executes on the manager's single serialized worker
 * thread — never the Android main thread — is bounded by the transaction
 * timeout, and fails closed on expiry. On a real device the main thread is the
 * Looper main thread, so "not the main thread" is an exact, observable claim
 * (the pure-JVM suite can only compare against the calling thread).
 */
@RunWith(AndroidJUnit4::class)
class DhizukuExecutorInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun identityValidation_rejectsThisAppAndMissingServer_onDevice() {
        val ownPackage = context.packageName
        val ownComponent = ComponentName(ownPackage, "$ownPackage.DeviceAdminReceiver")
        val validDhizukuComponent = ComponentName(
            DhizukuServerIdentity.PACKAGE_NAME,
            DhizukuServerIdentity.ADMIN_CLASS_NAME
        )

        assertFalse(
            PackageManagerDhizukuServerIdentityChecker(context)
                .isTrusted(ownPackage, ownComponent)
        )
        assertFalse(
            PackageManagerDhizukuServerIdentityChecker(context)
                .isTrusted(DhizukuServerIdentity.PACKAGE_NAME, validDhizukuComponent)
        )
    }

    @Test
    fun identityValidation_requiresExactOwnerComponent_onDevice() {
        assertFalse(
            DhizukuServerIdentity.isExpectedOwner(
                DhizukuServerIdentity.PACKAGE_NAME,
                ComponentName(DhizukuServerIdentity.PACKAGE_NAME, "com.rosan.dhizuku.server.OtherReceiver")
            )
        )
    }

    @Test
    fun manager_doesNotReturnAirgateAdminWhenDhizukuIsUnavailable_onDevice() {
        val manager = DhizukuManager(context)
        try {
            assertEquals(null, manager.getAdminComponent())
            assertEquals(DhizukuAvailability.UNAVAILABLE, manager.getDhizukuAvailability())
        } finally {
            manager.close()
        }
    }

    private class RecordingWrapper : DhizukuBinderWrapper {
        val executedOnMainThread = mutableListOf<Boolean>()
        val executingThreads = mutableListOf<String>()
        val globalSettings = mutableMapOf<String, String>()
        var wipeCalled = false

        private fun record() {
            executedOnMainThread.add(Looper.getMainLooper().isCurrentThread)
            executingThreads.add(Thread.currentThread().name)
        }

        override fun isPermissionGranted(): Boolean = true

        override fun bindUserService(componentName: ComponentName, connection: Any): Boolean = true

        override fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean {
            record()
            globalSettings[key] = value
            return true
        }

        override fun addUserRestriction(admin: ComponentName, key: String): Boolean {
            record()
            return true
        }

        override fun clearUserRestriction(admin: ComponentName, key: String): Boolean {
            record()
            return true
        }

        override fun wipeDevice(flags: Int): Boolean {
            record()
            wipeCalled = true
            return true
        }
    }

    private class BlockingWrapper : DhizukuBinderWrapper {
        val release = CountDownLatch(1)

        private fun block() {
            release.await(10, TimeUnit.SECONDS)
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

    @Test
    fun transaction_neverRunsOnTheAndroidMainThread_onDevice() {
        val wrapper = RecordingWrapper()
        val manager = DhizukuManager(context, wrapper)
        try {
            manager.setGlobalSetting("airplane_mode_on", "1", AppConfig())

            assertEquals(1, wrapper.executedOnMainThread.size)
            assertFalse(
                "the Dhizuku transaction must not execute on the Android main thread",
                wrapper.executedOnMainThread.first()
            )
            assertFalse(
                "the transaction must not run on the calling thread either",
                wrapper.executingThreads.first() == Thread.currentThread().name
            )
            assertEquals("dhizuku-transaction", wrapper.executingThreads.first())
            assertEquals("1", wrapper.globalSettings["airplane_mode_on"])
        } finally {
            manager.close()
        }
    }

    @Test
    fun wipe_withoutDeviceOwnerAuthority_isRejectedOnDevice() {
        val manager = DhizukuManager(context)
        try {
            val result = manager.wipeDevice(0x8, AppConfig(dryRunMode = false))

            // The emulator has no Dhizuku device-owner authority, so the real
            // wrappedDpm() resolves to null and the wipe must fail closed.
            assertEquals(WipeResult.REJECTED, result)
        } finally {
            manager.close()
        }
    }

    @Test
    fun dryRunWipe_isSimulatedOnDevice() {
        val manager = DhizukuManager(context)
        try {
            assertEquals(WipeResult.SIMULATED, manager.wipeDevice(0x8, AppConfig(dryRunMode = true)))
        } finally {
            manager.close()
        }
    }

    @Test
    fun wedgedTransaction_failsClosedAfterTheTimeout_onDevice() {
        val wrapper = BlockingWrapper()
        val manager = DhizukuManager(context, wrapper, transactionTimeoutMs = 200)
        try {
            assertEquals(
                "a wedged wipe must fail closed on the device",
                WipeResult.REJECTED,
                manager.wipeDevice(0x8, AppConfig(dryRunMode = false))
            )
            assertFalse(manager.setGlobalSetting("airplane_mode_on", "1", AppConfig()))
        } finally {
            wrapper.release.countDown()
            manager.close()
        }
    }

    @Test
    fun timedOutTransaction_returnsWithinTheBound_onDevice() {
        val wrapper = BlockingWrapper()
        val manager = DhizukuManager(context, wrapper, transactionTimeoutMs = 200)
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
    fun concurrentTransactions_serializeOntoOneWorker_onDevice() {
        val wrapper = RecordingWrapper()
        val manager = DhizukuManager(context, wrapper)
        try {
            val callers = (1..8).map {
                Thread { manager.setGlobalSetting("key$it", "1", AppConfig()) }
            }
            callers.forEach { it.start() }
            callers.forEach { it.join(5_000) }

            assertEquals(8, wrapper.executingThreads.size)
            assertEquals(
                "every transaction must run on the same single worker thread",
                1,
                wrapper.executingThreads.distinct().size
            )
            assertEquals("dhizuku-transaction", wrapper.executingThreads.first())
            assertTrue(wrapper.executedOnMainThread.none { it })
        } finally {
            manager.close()
        }
    }

    @Test
    fun callsAfterClose_failClosed_onDevice() {
        val wrapper = RecordingWrapper()
        val manager = DhizukuManager(context, wrapper)
        manager.close()

        assertEquals(WipeResult.REJECTED, manager.wipeDevice(0x8, AppConfig(dryRunMode = false)))
        assertFalse(manager.setGlobalSetting("airplane_mode_on", "1", AppConfig()))
        assertFalse(manager.isDhizukuAvailable())
    }
}
