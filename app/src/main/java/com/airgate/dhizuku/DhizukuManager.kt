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
import android.content.Context
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.WipeResult

/**
 * Facade over the Dhizuku device-owner integration. The public surface and the
 * constructor signature are the stable API used across the app and the test
 * suite; the actual work is delegated to single-purpose collaborators:
 *
 *  - [DhizukuConnection] — binder init, availability, permission granting
 *  - [DhizukuDpmBridge] — hidden-API binder rewrap and admin-component resolution
 *  - [DhizukuPolicyWriter] — non-destructive policy writes (never dry-run gated)
 *  - [DhizukuDestructiveOps] — wipe / user removal (dry-run gated)
 */
class DhizukuManager(
    private val context: Context,
    binderWrapper: DhizukuBinderWrapper? = null
) {
    private val connection = DhizukuConnection(context, binderWrapper)
    private val bridge = DhizukuDpmBridge(context, connection, binderWrapper)
    private val policyWriter = DhizukuPolicyWriter(bridge)
    private val destructiveOps = DhizukuDestructiveOps(bridge)

    /**
     * Requests the Dhizuku binder. Must be called before any other Dhizuku-API
     * call. Returns true when Dhizuku is activated and the binder was received.
     */
    fun init(): Boolean = connection.init()

    fun isDhizukuAvailable(): Boolean = connection.isDhizukuAvailable()

    /**
     * Requests Dhizuku permission. When Dhizuku is active and running this opens
     * the official Dhizuku grant dialog; if Dhizuku cannot be reached it falls
     * back to launching the Dhizuku app.
     *
     * Returns true only when permission is already granted synchronously. The grant
     * dialog is asynchronous, so a request that launches the dialog returns false;
     * the caller is notified via [onResult] once the listener reports back.
     */
    fun requestPermission(context: Context = this.context, onResult: ((Boolean) -> Unit)? = null): Boolean =
        connection.requestPermission(context, onResult)

    /**
     * Requests a full factory reset via the Dhizuku-wrapped device-owner authority.
     * Dry-run mode returns [com.airgate.domain.model.WipeResult.SIMULATED]
     * without calling the destructive API.
     */
    fun wipeDevice(flags: Int, config: AppConfig): WipeResult = destructiveOps.wipeDevice(flags, config)

    fun getAdminComponent(): ComponentName = bridge.getAdminComponent()

    fun setGlobalSetting(key: String, value: String, config: AppConfig): Boolean =
        policyWriter.setGlobalSetting(key, value, config)

    fun addUserRestriction(restrictionKey: String, config: AppConfig): Boolean =
        policyWriter.addUserRestriction(restrictionKey, config)

    fun clearUserRestriction(restrictionKey: String, config: AppConfig): Boolean =
        policyWriter.clearUserRestriction(restrictionKey, config)
}
