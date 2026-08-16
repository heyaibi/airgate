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

import android.content.Context
import android.content.Intent
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener

/**
 * Owns the Dhizuku activation lifecycle: binder init, availability probing and
 * the (asynchronous) permission-grant flow. Nothing here touches device policy
 * or destructive operations.
 */
internal class DhizukuConnection(
    private val context: Context,
    private val binderWrapper: DhizukuBinderWrapper?
) {
    private var initialized = false

    /**
     * Requests the Dhizuku binder. Must be called before any other Dhizuku-API
     * call. Returns true when Dhizuku is activated and the binder was received.
     */
    fun init(): Boolean {
        if (binderWrapper != null) return true
        if (initialized) return true
        return try {
            initialized = Dhizuku.init(context.applicationContext)
            initialized
        } catch (e: Exception) {
            false
        }
    }

    fun isDhizukuAvailable(): Boolean {
        if (binderWrapper != null) {
            return binderWrapper.isPermissionGranted()
        }
        return try {
            init()
            Dhizuku.isPermissionGranted()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Requests Dhizuku permission. When Dhizuku is active and running this opens
     * the official Dhizuku grant dialog; if Dhizuku cannot be reached it falls
     * back to launching the Dhizuku app.
     *
     * Returns true only when permission is already granted synchronously. The grant
     * dialog is asynchronous, so a request that launches the dialog returns false;
     * the caller is notified via [onResult] once the listener reports back.
     */
    fun requestPermission(context: Context = this.context, onResult: ((Boolean) -> Unit)? = null): Boolean {
        if (binderWrapper != null) {
            return binderWrapper.isPermissionGranted()
        }
        return try {
            if (!init()) {
                launchDhizukuApp()
                return false
            }
            if (Dhizuku.isPermissionGranted()) return true
            Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                override fun onRequestPermission(grantResult: Int) {
                    // Result is delivered asynchronously; report the authoritative post-grant state.
                    onResult?.invoke(runCatching { Dhizuku.isPermissionGranted() }.getOrDefault(false))
                }
            })
            false
        } catch (e: Exception) {
            launchDhizukuApp()
            false
        }
    }

    private fun launchDhizukuApp(): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.rosan.dhizuku")
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }
}
