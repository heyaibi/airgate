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

import android.content.SharedPreferences

/**
 * Persistence of the appearance preference ("Use System Colors").
 *
 * Deliberately a plain (unencrypted) SharedPreferences value rather than a
 * [ProtectedPrefsStore] entry: it is an appearance toggle, not security posture
 * data, and it is read on every theme composition — it must never touch the
 * AndroidKeyStore.
 */
internal class ThemePrefsStore(private val prefs: SharedPreferences) {
    companion object {
        private const val KEY_USE_SYSTEM_COLORS = "use_system_colors"
    }

    fun getUseSystemColors(): Boolean = prefs.getBoolean(KEY_USE_SYSTEM_COLORS, false)

    fun setUseSystemColors(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_SYSTEM_COLORS, enabled).apply()
    }
}
