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
import com.airgate.domain.model.AppConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * On-device verification of the arming gate: the watchdog can only be *newly*
 * enabled while the app can post notifications. Uses a throwaway prefs file so no
 * real app state is touched.
 *
 * The granted branch is exercised against the real permission via `pm grant` (which
 * does not disturb a running process). The denied branch is exercised by injecting
 * the same decision a revoked permission produces (`areNotificationsEnabled() ==
 * false`), because revoking POST_NOTIFICATIONS mid-instrumentation force-stops the
 * app process that is running the test; the decision logic is covered exhaustively
 * in the JVM suite.
 */
@RunWith(AndroidJUnit4::class)
class NotificationArmingGateInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun arming_isAccepted_whenNotificationsAndFullScreenAlertsAreGranted() {
        setNotificationsPermission(granted = true)
        // The real notification-path check: notifications AND full-screen alerts
        // (the full-screen-intent permission is independently revocable on 14+).
        val repository = repository(provider = {
            SecurityStateRepository.canPostAlarmNotifications(context)
        })

        val requested = repository.saveConfig(AppConfig(isEnabled = true))

        assertTrue(
            "arming must be accepted while the full alarm notification path is granted",
            requested.isEnabled
        )
        assertTrue(repository.getConfig().isEnabled)
    }

    @Test
    fun arming_isRefused_whenNotificationPathDecidesDisabled() {
        // Mirrors a revoked POST_NOTIFICATIONS (or revoked full-screen alerts):
        // canPostAlarmNotifications() returns false, so the enable request must be
        // coerced back to disabled.
        val repository = repository(provider = { false })

        val requested = repository.saveConfig(AppConfig(isEnabled = true))

        assertFalse("arming must be refused while notifications are not allowed", requested.isEnabled)
        assertFalse(repository.getConfig().isEnabled)
    }

    private fun repository(provider: () -> Boolean): SecurityStateRepository {
        val prefs = context.getSharedPreferences(
            "arming_gate_instrumented_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        val repository = SecurityStateRepository(prefs, null, provider)
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        return repository
    }

    private fun setNotificationsPermission(granted: Boolean) {
        val pkg = context.packageName
        val permission = "android.permission.POST_NOTIFICATIONS"
        val command = if (granted) "pm grant $pkg $permission" else "pm revoke $pkg $permission"
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        try {
            FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        } finally {
            pfd.close()
        }
        Thread.sleep(500)
    }
}
