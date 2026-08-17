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
    internal val wrapper: DhizukuBinderWrapper?,
    /**
     * Test-only override: when present, [wrappedDpm] returns it directly, so the
     * platform wipe path can be exercised against a Robolectric-shadowed
     * [DevicePolicyManager] on the JVM without the Dhizuku binder plumbing.
     */
    private val injectedDpm: DevicePolicyManager? = null,
    private val identityChecker: DhizukuServerIdentityChecker =
        PackageManagerDhizukuServerIdentityChecker(context),
    private val injectedAdminComponent: ComponentName? = null,
    private val ownerResolver: DhizukuOwnerResolver = RealDhizukuOwnerResolver
) {
    fun availability(): DhizukuAvailability {
        if (wrapper != null) {
            val testWrapper = wrapper
            return runCatching {
                if (injectedAdminComponent != null && testWrapper.isPermissionGranted()) {
                    DhizukuAvailability.AUTHORIZED
                } else {
                    DhizukuAvailability.UNAVAILABLE
                }
            }.getOrDefault(DhizukuAvailability.UNAVAILABLE)
        }
        if (!connection.init()) return DhizukuAvailability.UNAVAILABLE
        val permissionGranted = runCatching { Dhizuku.isPermissionGranted() }.getOrDefault(false)
        return DhizukuServerIdentity.resolveAvailability(permissionGranted, permissionGranted && trustedOwner() != null)
    }

    fun getAdminComponent(): ComponentName? {
        if (wrapper != null) return injectedAdminComponent
        return trustedOwner()
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
        if (injectedDpm != null) return injectedDpm
        if (!connection.init()) return null
        return try {
            if (!Dhizuku.isPermissionGranted()) return null

            // Exempt hidden APIs so the DPM service binder can be rewrapped.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.setHiddenApiExemptions("")
            }

            val ownerComponent = trustedOwner() ?: return null
            val ownerPackage = ownerComponent.packageName
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

    private fun trustedOwner(): ComponentName? {
        val ownerPackage = runCatching { ownerResolver.ownerPackageName() }.getOrNull()
        val ownerComponent = runCatching { ownerResolver.ownerComponent() }.getOrNull()
        return ownerComponent.takeIf { identityChecker.isTrusted(ownerPackage, it) }
    }
}
