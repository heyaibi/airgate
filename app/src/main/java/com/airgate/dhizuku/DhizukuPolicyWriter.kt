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

import com.airgate.domain.model.AppConfig

/**
 * Applies non-destructive device-policy writes (global settings and user
 * restrictions) through the Dhizuku-wrapped device-owner authority.
 *
 * Policy writes below are never gated by dry-run mode; they run for real in every mode.
 * Dry-run only changes whether the destructive wipe/remove executes (see
 * DhizukuDestructiveOps). `config` is retained on the signatures for uniformity with
 * the destructive methods but is unused here.
 *
 * Every privileged call consults [isInvalidated] immediately before the binder
 * invocation so a transaction whose caller has already timed out, been
 * interrupted, or seen the executor shut down refuses to apply the policy
 * change. A late policy write is just as much a security failure as a late
 * wipe: the caller has already reported failure to the owner.
 */
internal class DhizukuPolicyWriter(
    private val bridge: DhizukuDpmBridge
) {
    fun setGlobalSetting(key: String, value: String, config: AppConfig, isInvalidated: () -> Boolean): Boolean {
        if (isInvalidated()) return false
        val admin = bridge.getAdminComponent() ?: return false
        if (isInvalidated()) return false
        if (bridge.wrapper != null) {
            return try {
                bridge.wrapper.setGlobalSetting(admin, key, value)
            } catch (e: Exception) {
                false
            }
        }
        val dpm = bridge.wrappedDpm() ?: return false
        if (isInvalidated()) return false
        return try {
            dpm.setGlobalSetting(admin, key, value)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun addUserRestriction(restrictionKey: String, config: AppConfig, isInvalidated: () -> Boolean): Boolean {
        if (isInvalidated()) return false
        val admin = bridge.getAdminComponent() ?: return false
        if (isInvalidated()) return false
        if (bridge.wrapper != null) {
            return try {
                bridge.wrapper.addUserRestriction(admin, restrictionKey)
            } catch (e: Exception) {
                false
            }
        }
        val dpm = bridge.wrappedDpm() ?: return false
        if (isInvalidated()) return false
        return try {
            dpm.addUserRestriction(admin, restrictionKey)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clearUserRestriction(restrictionKey: String, config: AppConfig, isInvalidated: () -> Boolean): Boolean {
        if (isInvalidated()) return false
        val admin = bridge.getAdminComponent() ?: return false
        if (isInvalidated()) return false
        if (bridge.wrapper != null) {
            return try {
                bridge.wrapper.clearUserRestriction(admin, restrictionKey)
            } catch (e: Exception) {
                false
            }
        }
        val dpm = bridge.wrappedDpm() ?: return false
        if (isInvalidated()) return false
        return try {
            dpm.clearUserRestriction(admin, restrictionKey)
            true
        } catch (e: Exception) {
            false
        }
    }
}
