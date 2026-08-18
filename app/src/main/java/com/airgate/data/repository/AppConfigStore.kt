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
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.ResponseTier

/**
 * Persistence of the AppConfig with a fingerprint cache.
 *
 * Config is the hottest read in the app: it is fetched on every breach, every 10s
 * settings poll and every screen composition. Each read decrypts ~17 values via
 * AndroidKeyStore (a binder round-trip each) on the calling thread. The decoded
 * AppConfig is cached against the raw stored strings: reading the raw values back
 * is an in-memory prefs lookup, and while the raw values are unchanged the cached
 * config can be returned without touching the keystore. If prefs are written
 * directly (legacy migration, tests), the fingerprint differs and the cache
 * rebuilds, so the cache can never serve stale data.
 *
 * The cache is primed only after a save has been written AND read back to
 * match: the whole config is committed atomically in a single synchronous
 * SharedPreferences commit, then every field is re-read and compared to the
 * requested value. A partial, refused, or corrupting write clears the cache
 * instead, so the next [getConfig] rebuilds from the actual on-disk state and
 * never serves a value that never landed.
 */
internal class AppConfigStore(
    private val prefs: SharedPreferences,
    private val store: ProtectedPrefsStore
) {
    companion object {
        // Config Keys
        private const val KEY_IS_ENABLED = "config_is_enabled"
        private const val KEY_WIPE_THRESHOLD = "config_wipe_threshold"
        private const val KEY_NOTIF_PER_BREACH = "config_notif_per_breach"
        private const val KEY_NOTIF_TAIL_MINS = "config_notif_tail_mins"
        private const val KEY_GRACE_WINDOW_SECS = "config_grace_window_secs"
        private const val KEY_SAFETY_NET_MINS = "config_safety_net_mins"
        private const val KEY_CLOCK_SKEW_MINS = "config_clock_skew_mins"

        private const val KEY_INCLUDE_FRP = "config_include_frp"
        private const val KEY_AGGRESSIVE_MODE = "config_aggressive_mode"
        private const val KEY_DRY_RUN_MODE = "config_dry_run_mode"
        private const val KEY_SELF_TAMPER_TIER = "config_self_tamper_tier"
        private const val KEY_DEVICE_PROTECTION_ALARM = "config_device_protection_alarm"
        private const val KEY_BLOCK_DEBUGGING_FEATURES = "config_block_debugging_features"

        // Persisted key of a config setting that has since been removed. Older
        // installs may still hold a protected value under this key; it is purged
        // whenever config is saved so no orphaned setting survives the removal.
        private const val LEGACY_KEY_USER_UNLOCK_RESETS = "config_user_unlock_resets"
    }

    private val configKeys = listOf(
        KEY_IS_ENABLED,
        KEY_WIPE_THRESHOLD,
        KEY_NOTIF_PER_BREACH,
        KEY_NOTIF_TAIL_MINS,
        KEY_GRACE_WINDOW_SECS,
        KEY_SAFETY_NET_MINS,
        KEY_CLOCK_SKEW_MINS,
        KEY_INCLUDE_FRP,
        KEY_AGGRESSIVE_MODE,
        KEY_DRY_RUN_MODE,
        KEY_SELF_TAMPER_TIER,
        KEY_DEVICE_PROTECTION_ALARM,
        KEY_BLOCK_DEBUGGING_FEATURES
    )

    @Volatile
    private var configCache: AppConfig? = null

    @Volatile
    private var configRawCache: List<String?>? = null

    fun getConfig(): AppConfig {
        // Snapshot prefs once: prefs.all returns a full map copy; reading it
        // per-key would make 17 copies for the fingerprint alone on every getConfig().
        val snapshot = try {
            prefs.all
        } catch (e: Exception) {
            emptyMap<String, Any?>()
        }
        val raw = configKeys.map { key -> store.rawToString(snapshot[key]) }
        val cached = configCache
        if (cached != null && configRawCache == raw) {
            return cached
        }
        val config = buildConfig()
        configCache = config
        configRawCache = raw
        return config
    }

    private fun buildConfig(): AppConfig {
        val defaultConfig = AppConfig()
        val selfTamperTierName = store.protectedGetString(KEY_SELF_TAMPER_TIER, defaultConfig.selfTamperTier.name)
        val selfTamperTier = runCatching { ResponseTier.valueOf(selfTamperTierName) }.getOrDefault(defaultConfig.selfTamperTier)
        return AppConfig(
            isEnabled = store.protectedGetBoolean(KEY_IS_ENABLED, defaultConfig.isEnabled),
            wipeThreshold = store.protectedGetInt(KEY_WIPE_THRESHOLD, defaultConfig.wipeThreshold),
            notificationsPerBreach = store.protectedGetInt(KEY_NOTIF_PER_BREACH, defaultConfig.notificationsPerBreach),
            notificationTailMinutes = store.protectedGetInt(KEY_NOTIF_TAIL_MINS, defaultConfig.notificationTailMinutes),
            graceWindowSeconds = store.protectedGetInt(KEY_GRACE_WINDOW_SECS, defaultConfig.graceWindowSeconds),
            safetyNetIntervalMinutes = store.protectedGetInt(KEY_SAFETY_NET_MINS, defaultConfig.safetyNetIntervalMinutes),
            clockSkewToleranceMinutes = store.protectedGetInt(KEY_CLOCK_SKEW_MINS, defaultConfig.clockSkewToleranceMinutes),
            includeFRPData = store.protectedGetBoolean(KEY_INCLUDE_FRP, defaultConfig.includeFRPData),
            aggressiveMode = store.protectedGetBoolean(KEY_AGGRESSIVE_MODE, defaultConfig.aggressiveMode),
            dryRunMode = store.protectedGetBoolean(KEY_DRY_RUN_MODE, defaultConfig.dryRunMode),
            selfTamperTier = selfTamperTier,
            deviceProtectionAlarmEnabled = store.protectedGetBoolean(KEY_DEVICE_PROTECTION_ALARM, defaultConfig.deviceProtectionAlarmEnabled),
            blockDebuggingFeatures = store.protectedGetBoolean(KEY_BLOCK_DEBUGGING_FEATURES, defaultConfig.blockDebuggingFeatures)
        )
    }

    fun saveConfig(config: AppConfig) {
        val committed = store.protectedPutAll(
            listOf(
                KEY_IS_ENABLED to config.isEnabled.toString(),
                KEY_WIPE_THRESHOLD to config.wipeThreshold.toString(),
                KEY_NOTIF_PER_BREACH to config.notificationsPerBreach.toString(),
                KEY_NOTIF_TAIL_MINS to config.notificationTailMinutes.toString(),
                KEY_GRACE_WINDOW_SECS to config.graceWindowSeconds.toString(),
                KEY_SAFETY_NET_MINS to config.safetyNetIntervalMinutes.toString(),
                KEY_CLOCK_SKEW_MINS to config.clockSkewToleranceMinutes.toString(),
                KEY_INCLUDE_FRP to config.includeFRPData.toString(),
                KEY_AGGRESSIVE_MODE to config.aggressiveMode.toString(),
                KEY_DRY_RUN_MODE to config.dryRunMode.toString(),
                KEY_SELF_TAMPER_TIER to config.selfTamperTier.name,
                KEY_DEVICE_PROTECTION_ALARM to config.deviceProtectionAlarmEnabled.toString(),
                KEY_BLOCK_DEBUGGING_FEATURES to config.blockDebuggingFeatures.toString()
            )
        )
        // Purge the removed setting's legacy persisted value (a no-op on fresh
        // installs). The legacy purge is best-effort: it is a cleanup of a dead
        // key and must not gate the cache update.
        store.removeProtected(LEGACY_KEY_USER_UNLOCK_RESETS)
        // Publish the cache only after a complete write-and-read verification:
        // the whole batch committed to disk AND every field reads back to the
        // requested value. A partial, refused, or corrupting write leaves the
        // cache empty so the next getConfig() rebuilds from the actual on-disk
        // state instead of serving values that never landed.
        if (committed && buildConfig() == config) {
            configCache = config
            val snapshot = try {
                prefs.all
            } catch (e: Exception) {
                emptyMap<String, Any?>()
            }
            configRawCache = configKeys.map { key -> store.rawToString(snapshot[key]) }
        } else {
            configCache = null
            configRawCache = null
        }
    }
}
