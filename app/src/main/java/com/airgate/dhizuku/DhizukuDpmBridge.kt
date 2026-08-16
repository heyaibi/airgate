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
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import com.rosan.dhizuku.api.Dhizuku
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Resolves the device-owner authority: the admin [ComponentName] and a
 * [DevicePolicyManager] whose binder is routed through Dhizuku's device-owner
 * process so privileged calls (global settings, user restrictions, wipe) run
 * with the device owner's authority. Collapses all hidden-API access into one
 * place.
 */
internal class DhizukuDpmBridge(
    private val context: Context,
    private val connection: DhizukuConnection,
    internal val wrapper: DhizukuBinderWrapper?
) {
    fun getAdminComponent(): ComponentName {
        if (wrapper != null) {
            return ComponentName(context.packageName, "${context.packageName}.DeviceAdminReceiver")
        }
        return try {
            Dhizuku.getOwnerComponent()
        } catch (e: Exception) {
            ComponentName(context.packageName, "${context.packageName}.DeviceAdminReceiver")
        }
    }

    /**
     * Returns a DevicePolicyManager whose binder is routed through Dhizuku's
     * device-owner process, so privileged calls (global settings, user
     * restrictions, wipe) run with the device owner's authority.
     *
     * Binder rewrapping requires access to the (hidden) DPM service field; this
     * is a deliberate, guarded use of a non-SDK interface and degrades to
     * failure (no enforcement) when restricted on future platforms.
     */
    @SuppressLint("SoonBlockedPrivateApi", "PrivateApi")
    fun wrappedDpm(): DevicePolicyManager? {
        if (!connection.init()) return null
        return try {
            if (!Dhizuku.isPermissionGranted()) return null

            // Exempt hidden APIs so the DPM service binder can be rewrapped.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.setHiddenApiExemptions("")
            }

            val ownerPackage = Dhizuku.getOwnerPackageName()
            val ownerContext = context.createPackageContext(ownerPackage, Context.CONTEXT_IGNORE_SECURITY)
            val manager = ownerContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

            val field = DevicePolicyManager::class.java.getDeclaredField("mService")
            field.isAccessible = true
            val oldBinder = (field.get(manager) as IInterface).asBinder()
            val wrappedBinder = Dhizuku.binderWrapper(oldBinder)

            val stubClass = Class.forName("android.app.admin.IDevicePolicyManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            field.set(manager, asInterface.invoke(null, wrappedBinder))
            manager
        } catch (e: Exception) {
            null
        }
    }
}
