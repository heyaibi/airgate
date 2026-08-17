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

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.WipeResult

/**
 * Executes the destructive device-owner operation — the factory reset. This is
 * the only Dhizuku operation gated by dry-run mode.
 *
 * The platform wipe API is void and fire-and-forget: it does not report whether
 * the wipe was performed. The only honest signals are "the system accepted the
 * request" (the call returned without throwing) and "the system refused it" (it
 * threw, or no device-owner authority is available). Success is never fabricated.
 *
 * Every entry point consults [isInvalidated] before invoking the destructive
 * binder call so a transaction whose caller has already timed out, been
 * interrupted, or seen the executor shut down refuses to issue the platform
 * wipe even if the worker thread has not been interrupted.
 */
internal class DhizukuDestructiveOps(
    private val bridge: DhizukuDpmBridge,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) {
    /**
     * Requests a full factory reset. Returns [WipeResult.SIMULATED] in dry-run
     * mode (no destructive call is made), [WipeResult.ACCEPTED] when the
     * device-owner authority accepted the request, and [WipeResult.REJECTED]
     * when the request was refused, no authority is available, or the
     * transaction was invalidated by its caller.
     *
     * On API 34+ the device-owner `wipeDevice` API is used: `wipeData` throws
     * `IllegalStateException` on the primary user (the only user a device owner
     * can run as) for apps targeting SDK 34+, so it would never erase anything.
     * Below API 34 `wipeData` still performs a device-wide reset from the
     * primary user and is the only option available.
     */
    fun wipeDevice(flags: Int, config: AppConfig, isInvalidated: () -> Boolean): WipeResult {
        if (config.dryRunMode) {
            return WipeResult.SIMULATED
        }
        if (isInvalidated()) return WipeResult.REJECTED
        if (bridge.wrapper != null) {
            return try {
                if (isInvalidated()) WipeResult.REJECTED
                else bridge.wrapper.wipeDevice(flags).toWipeResult()
            } catch (e: Exception) {
                WipeResult.REJECTED
            }
        }
        val dpm = bridge.wrappedDpm() ?: return WipeResult.REJECTED
        if (isInvalidated()) return WipeResult.REJECTED
        return try {
            performPlatformWipe(dpm, flags)
            WipeResult.ACCEPTED
        } catch (e: Exception) {
            WipeResult.REJECTED
        }
    }

    // The API-34 call below is gated by shouldUseWipeDevice(sdkInt); lint cannot
    // see through the injectable sdk boundary, so the guard's correctness is
    // enforced by unit tests instead of by lint's static SDK tracking.
    @SuppressLint("NewApi")
    private fun performPlatformWipe(dpm: DevicePolicyManager, flags: Int) {
        if (shouldUseWipeDevice(sdkInt)) {
            wipeDeviceApi34(dpm, flags)
        } else {
            dpm.wipeData(flags)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun wipeDeviceApi34(dpm: DevicePolicyManager, flags: Int) {
        dpm.wipeDevice(flags)
    }

    /**
     * The device-owner `wipeDevice` API exists only on API 34+. Below that the
     * device-wide reset from the primary user must go through `wipeData`.
     */
    internal fun shouldUseWipeDevice(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
}

private fun Boolean.toWipeResult(): WipeResult = if (this) WipeResult.ACCEPTED else WipeResult.REJECTED
