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

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airgate.data.crypto.PinManager
import com.airgate.domain.model.AppConfig
import com.airgate.testutil.crypto.AndroidKeyStoreRule
import com.airgate.testutil.crypto.ShadowNotificationManagerWithFullScreenIntent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * JVM verification (Robolectric) of the arming gate: the watchdog can only be
 * *newly* enabled while the app can post notifications. Uses a throwaway prefs
 * file so no real app state is touched.
 *
 * The granted branch is exercised against the simulated permission and
 * notification state. The denied branch is exercised by injecting the same
 * decision a revoked permission produces (`areNotificationsEnabled() == false`);
 * the decision logic is covered exhaustively in the JVM suite.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [ShadowNotificationManagerWithFullScreenIntent::class])
class NotificationArmingGateTest {

    @get:Rule
    val androidKeyStoreRule = AndroidKeyStoreRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun arming_isAccepted_whenNotificationsAndFullScreenAlertsAreGranted() {
        grantNotifications()
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
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        return repository
    }

    private fun grantNotifications() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.USE_FULL_SCREEN_INTENT"
        )
        shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .setNotificationsEnabled(true)
    }
}
