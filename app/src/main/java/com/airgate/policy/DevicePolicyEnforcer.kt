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

package com.airgate.policy

import android.annotation.SuppressLint
import android.content.Context
import android.os.UserManager
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.AppConfig

class DevicePolicyEnforcer(
    private val context: Context,
    private val dhizukuManager: DhizukuManager
) {
    companion object {
        // Restriction keys are inlined string/int constants, so referencing the
        // newer keys is safe on older devices: the system simply ignores a key it
        // does not know. The newer restrictions (e.g. DISALLOW_WIFI_TETHERING on
        // API 33) are only actionable on the platforms that define them.
        @SuppressLint("InlinedApi")
        val REQUIRED_USER_RESTRICTIONS = listOf(
            UserManager.DISALLOW_CHANGE_WIFI_STATE,
            UserManager.DISALLOW_BLUETOOTH,
            UserManager.DISALLOW_BLUETOOTH_SHARING,
            UserManager.DISALLOW_CONFIG_TETHERING,

            UserManager.DISALLOW_WIFI_TETHERING,
            UserManager.DISALLOW_DATA_ROAMING,
            UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
            UserManager.DISALLOW_USB_FILE_TRANSFER,
            UserManager.DISALLOW_OUTGOING_BEAM, // NFC restriction constant
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES
        )
    }

    fun enforceAllPolicies(config: AppConfig): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()

        // Enforce global settings. Airplane mode is always forced on; ADB is only
        // pinned to 0 while the owner wants the debugging block. When the owner
        // turns the block OFF, actively restore adb_enabled + developer options so
        // the device can be reached over `adb` for install/recovery instead of
        // staying unreachable.
        results["airplane_mode_on"] = dhizukuManager.setGlobalSetting("airplane_mode_on", "1", config)
        results["adb_enabled"] = if (config.blockDebuggingFeatures) {
            dhizukuManager.setGlobalSetting("adb_enabled", "0", config)
        } else {
            dhizukuManager.setGlobalSetting("adb_enabled", "1", config)
        }
        results["development_settings_enabled"] = if (config.blockDebuggingFeatures) {
            dhizukuManager.setGlobalSetting("development_settings_enabled", "0", config)
        } else {
            dhizukuManager.setGlobalSetting("development_settings_enabled", "1", config)
        }

        // Enforce all required user restrictions
        for (restriction in REQUIRED_USER_RESTRICTIONS) {
            if (restriction == UserManager.DISALLOW_DEBUGGING_FEATURES && !config.blockDebuggingFeatures) {
                // Opt-out switch: actively clear the debugging block so developer
                // options / USB debugging are restored rather than merely skipped.
                results[restriction] = dhizukuManager.clearUserRestriction(restriction, config)
            } else {
                results[restriction] = dhizukuManager.addUserRestriction(restriction, config)
            }
        }

        return results
    }

    fun verifyActiveRestrictions(config: AppConfig = AppConfig()): Map<String, Boolean> {
        // Do NOT verify via the local unwrapped DevicePolicyManager: the admin component
        // is owned by Dhizuku, so every call throws SecurityException and every
        // restriction would be reported "missing". UserManager.userRestrictions reflects
        // the current user's actual restriction set regardless of admin ownership.
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        val restrictions = userManager?.userRestrictions
        val expected = REQUIRED_USER_RESTRICTIONS.filterNot {
            it == UserManager.DISALLOW_DEBUGGING_FEATURES && !config.blockDebuggingFeatures
        }
        return expected.associateWith { restriction ->
            restrictions?.getBoolean(restriction, false) ?: false
        }
    }

    /**
     * Reports whether the device actually honors the requested debugging block. A third-party
     * app cannot read `adb_enabled` back (it always reads 0), so the device-owner restriction
     * set via [UserManager] is the reliable in-app source of truth.
     */
    fun isDebuggingBlockEffective(config: AppConfig): Boolean {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return false
        val actuallyBlocked = userManager.userRestrictions.getBoolean(UserManager.DISALLOW_DEBUGGING_FEATURES, false)
        return actuallyBlocked == config.blockDebuggingFeatures
    }
}
