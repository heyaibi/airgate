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

/**
 * Abstraction over the Dhizuku-privileged backend so callers and tests can swap
 * the real device-owner authority for a recording stub without touching binder
 * plumbing or reflection.
 */
interface DhizukuBinderWrapper {
    fun isPermissionGranted(): Boolean
    fun bindUserService(componentName: ComponentName, connection: Any): Boolean
    fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean
    fun addUserRestriction(admin: ComponentName, key: String): Boolean
    fun clearUserRestriction(admin: ComponentName, key: String): Boolean
    fun wipeDevice(flags: Int): Boolean
}
