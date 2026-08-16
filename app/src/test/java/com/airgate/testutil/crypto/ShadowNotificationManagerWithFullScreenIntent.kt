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

package com.airgate.testutil.crypto

import android.app.Application
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Extends Robolectric's [ShadowNotificationManager] with the full-screen-intent
 * gate. Robolectric does not shadow
 * [android.app.NotificationManager.canUseFullScreenIntent], so on the JVM it
 * returns its default (`false`) regardless of the grant state — which would
 * make [androidx.core.app.NotificationManagerCompat.canUseFullScreenIntent]
 * always report "not allowed" and refuse the notification arming gate.
 *
 * The real platform behavior (Android 14+) is: the app can send full-screen
 * intents only while it holds the `USE_FULL_SCREEN_INTENT` runtime permission.
 * This shadow mirrors that exactly against Robolectric's permission set, so the
 * decision logic in [com.airgate.data.repository.SecurityStateRepository.canPostAlarmNotifications]
 * can be verified on the JVM.
 */
@Implements(NotificationManager::class)
class ShadowNotificationManagerWithFullScreenIntent : ShadowNotificationManager() {

    @Implementation
    fun canUseFullScreenIntent(): Boolean {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return app.packageManager.checkPermission(
            "android.permission.USE_FULL_SCREEN_INTENT", app.packageName
        ) == PackageManager.PERMISSION_GRANTED
    }
}
