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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification (real SharedPreferences + Android Keystore) that the
 * removed "reset streak on unlock" setting leaves no trace: saving config never
 * writes its key, an older install's value under that key is purged on save, and
 * a lingering value never disturbs config reads. Uses a throwaway prefs file so
 * no real app state is touched.
 */
@RunWith(AndroidJUnit4::class)
class AppConfigStoreInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val legacyKey = "config_user_unlock_resets"

    @Test
    fun savingConfig_neverWritesTheRemovedSettingKey_onRealStorage() {
        val prefs = newPrefs()
        val repository = SecurityStateRepository(prefs, null, notificationsAllowedProvider = { true })
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))

        repository.saveConfig(AppConfig(wipeThreshold = 5))

        assertFalse("the removed key must not be persisted on save", prefs.contains(legacyKey))
        assertEquals(5, repository.getConfig().wipeThreshold)
    }

    @Test
    fun savingConfig_purgesALegacyValue_onRealStorage() {
        val prefs = newPrefs()
        // A standalone protected store on the same prefs stands in for the write
        // an older install made under the removed key.
        ProtectedPrefsStore(prefs, null).protectedPutBoolean(legacyKey, true)
        assertTrue("the legacy value must be present before the save", prefs.contains(legacyKey))

        val repository = SecurityStateRepository(prefs, null, notificationsAllowedProvider = { true })
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        repository.saveConfig(AppConfig())

        assertFalse("saving config must purge the legacy value on-device", prefs.contains(legacyKey))
    }

    @Test
    fun aLegacyValue_doesNotDisturbConfigReads_onRealStorage() {
        val prefs = newPrefs()
        val repository = SecurityStateRepository(prefs, null, notificationsAllowedProvider = { true })
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        repository.saveConfig(AppConfig(wipeThreshold = 7))

        ProtectedPrefsStore(prefs, null).protectedPutBoolean(legacyKey, true)

        // The legacy key sits outside the config fingerprint, so reads keep
        // returning the saved config and the key stays until the next save.
        assertEquals(7, repository.getConfig().wipeThreshold)
        assertTrue(prefs.contains(legacyKey))
    }

    private fun newPrefs(): android.content.SharedPreferences {
        val prefs = context.getSharedPreferences(
            "app_config_instrumented_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        return prefs
    }
}
