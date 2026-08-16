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

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airgate.domain.model.AppConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import com.airgate.testutil.crypto.AndroidKeyStoreRule
import org.junit.Rule

/**
 * JVM verification (Robolectric) of the Bluetooth arming gate: the watchdog can
 * only be *newly* enabled while the app can read Bluetooth state
 * (BLUETOOTH_CONNECT on Android 12+). Uses a throwaway prefs file so no real app
 * state is touched.
 *
 * The granted branch is exercised against the simulated permission state (the
 * same set [android.content.Context.checkSelfPermission] consults). The denied
 * branch is exercised by injecting the same decision a revoked permission
 * produces; the decision logic is covered exhaustively in the JVM suite.
 */
@RunWith(AndroidJUnit4::class)
class BluetoothArmingGateTest {

    @get:Rule
    val androidKeyStoreRule = AndroidKeyStoreRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun arming_isAccepted_whenBluetoothConnectIsGranted() {
        grantBluetoothConnect()
        val repository = repository(provider = {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        })

        val requested = repository.saveConfig(AppConfig(isEnabled = true))

        assertTrue(
            "arming must be accepted while BLUETOOTH_CONNECT is granted",
            requested.isEnabled
        )
        assertTrue(repository.getConfig().isEnabled)
    }

    @Test
    fun arming_isRefused_whenBluetoothConnectDecidesDenied() {
        // Mirrors a revoked BLUETOOTH_CONNECT: the provider returns false, so the
        // enable request must be coerced back to disabled.
        val repository = repository(provider = { false })

        val requested = repository.saveConfig(AppConfig(isEnabled = true))

        assertFalse("arming must be refused while bluetooth state cannot be read", requested.isEnabled)
        assertFalse(repository.getConfig().isEnabled)
    }

    @Test
    fun isBluetoothConnectAllowed_reflectsTheRealGrantState() {
        // The Context-constructed repository must wire the real permission check.
        val repository = SecurityStateRepository(context)
        val expected = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        assertTrue(
            "isBluetoothConnectAllowed must equal the real permission state ($expected)",
            repository.isBluetoothConnectAllowed() == expected
        )
    }

    private fun repository(provider: () -> Boolean): SecurityStateRepository {
        val prefs = context.getSharedPreferences(
            "bluetooth_arming_gate_instrumented_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        val repository = SecurityStateRepository(prefs, null, { true }, provider)
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        return repository
    }

    private fun grantBluetoothConnect() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
    }
}
