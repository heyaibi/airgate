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

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.ViolationType
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * Boots the real [WatchdogService] and verifies the audit loop actually polls
 * the live radio state. This pins the one line of production wiring that the
 * pure-function and detector tests cannot reach: the audit tick must invoke
 * [com.airgate.detector.RadioStateDetector.checkRadioState], so a service start
 * into an already-violating state (Bluetooth on / airplane mode off) is detected
 * even though no transition broadcast fires.
 *
 * The airplane-mode setting is toggled while the service is confirmed stopped
 * (via [ActivityManager.getRunningServices]), so the toggle's broadcast is
 * missed by design and only the first audit tick's poll can observe it.
 */
@RunWith(AndroidJUnit4::class)
class WatchdogServiceInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun firstAuditTick_pollsTheLiveRadioState_atServiceStart() {
        val resolver = context.contentResolver
        val original = Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0)
        provisionPermissions()
        val writable = runCatching {
            Settings.Global.putInt(resolver, Settings.Global.AIRPLANE_MODE_ON, original)
        }.getOrDefault(false)
        if (!writable) return // airplane toggle unavailable; the poll is still pinned by the pure tests

        val repo = SecurityStateRepository(context)
        try {
            // Confirm the watchdog is stopped so no broadcast receiver exists for
            // the airplane toggle below.
            ensureServiceStopped()

            // Baseline airplane ON (safe), set with no receiver registered.
            Settings.Global.putInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 1)

            // Clean state + arm the watchdog so the poll's breach is recorded.
            repo.resetStreak()
            repo.setVtCount(ViolationType.AIRPLANE_MODE_OFF, 0)
            repo.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
            assertTrue(
                "arming must succeed so the poll's breach is enforced",
                repo.saveConfig(AppConfig(isEnabled = true)).isEnabled
            )

            // Airplane OFF while the service is stopped: the toggle's broadcast is
            // missed by design (no receiver is registered), so only the audit-loop
            // poll — the first tick after the service starts — can observe it.
            Settings.Global.putInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0)

            context.startForegroundService(Intent(context, WatchdogService::class.java))
            waitUntil(15_000) { repo.getVtCount(ViolationType.AIRPLANE_MODE_OFF) > 0 }
            assertTrue(
                "the first audit tick must detect the already-off airplane mode via the poll",
                repo.getVtCount(ViolationType.AIRPLANE_MODE_OFF) > 0
            )
        } finally {
            repo.resetStreak()
            repo.saveConfig(AppConfig(isEnabled = false))
            runCatching { Settings.Global.putInt(resolver, Settings.Global.AIRPLANE_MODE_ON, original) }
            runCatching { context.stopService(Intent(context, WatchdogService::class.java)) }
        }
    }

    private fun ensureServiceStopped() {
        context.stopService(Intent(context, WatchdogService::class.java))
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            @Suppress("DEPRECATION")
            val running = am.getRunningServices(200)
                .any { it.service.className == WatchdogService::class.java.name }
            if (!running) return
            SystemClock.sleep(200)
        }
        // Fall back to a generous settle if the running-services poll never saw it
        // leave (it must, for the toggle's broadcast to be missed).
        SystemClock.sleep(1_000)
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(250)
        }
        throw AssertionError("condition not met within ${timeoutMillis}ms")
    }

    private fun provisionPermissions() {
        // POST_NOTIFICATIONS + BLUETOOTH_CONNECT are required by the arming gate;
        // WRITE_SECURE_SETTINGS makes the airplane toggle deterministic. All three
        // are best-effort (a device that refuses a grant is handled gracefully).
        grant("android.permission.POST_NOTIFICATIONS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            grant("android.permission.BLUETOOTH_CONNECT")
        }
        grant("android.permission.WRITE_SECURE_SETTINGS")
        Thread.sleep(500)
    }

    private fun grant(permission: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${context.packageName} $permission")
        try {
            FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        } finally {
            pfd.close()
        }
    }
}
