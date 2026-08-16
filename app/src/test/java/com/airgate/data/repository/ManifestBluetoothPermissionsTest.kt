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
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the Bluetooth permission surface on the installed app: the modern
 * BLUETOOTH_CONNECT runtime permission is declared (it gates both the Bluetooth
 * broadcasts and the live adapter reads on Android 12+), and the repository's
 * permission gate agrees with the platform's own check on this device.
 */
@RunWith(AndroidJUnit4::class)
class ManifestBluetoothPermissionsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun bluetoothConnect_isDeclaredInTheManifest() {
        assertTrue(
            "BLUETOOTH_CONNECT must be declared",
            requestedPermissions().contains("android.permission.BLUETOOTH_CONNECT")
        )
    }

    @Test
    fun repositoryPermissionCheck_matchesThePlatformGate() {
        val expected = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            context.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED

        assertTrue(
            "hasBluetoothConnectPermission must equal the platform gate on this device ($expected)",
            SecurityStateRepository.hasBluetoothConnectPermission(context) == expected
        )
    }

    private fun packageInfo(): PackageInfo {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
    }

    private fun requestedPermissions(): List<String> {
        return packageInfo().requestedPermissions?.toList().orEmpty()
    }
}
