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

import android.app.admin.DevicePolicyManager
import android.os.Build
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowDevicePolicyManager

/**
 * Extends Robolectric's [ShadowDevicePolicyManager] with the two platform wipe
 * calls so [DhizukuDestructiveOps]'s literal dpm invocations can be asserted on
 * the JVM. Robolectric's stock shadow records `wipeData` but does not shadow
 * `wipeDevice` (API 34+), so that call would fall through to the real
 * implementation and throw. This shadow routes both calls through a controllable
 * recording: a successful call records which method was invoked and the flags
 * (driving [com.airgate.domain.model.WipeResult.ACCEPTED]), and a [wipeThrows]
 * call throws a [SecurityException] — the platform's rejection signal for an
 * unauthorized wipe — (driving [com.airgate.domain.model.WipeResult.REJECTED]).
 */
@Implements(DevicePolicyManager::class)
class ShadowDevicePolicyManagerForWipe : ShadowDevicePolicyManager() {

    enum class WipeCall { WIPE_DEVICE, WIPE_DATA }

    var lastWipeCall: WipeCall? = null
    var lastWipeFlags = -1
    var wipeThrows = false

    @Implementation(minSdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun wipeDevice(flags: Int) {
        record(WipeCall.WIPE_DEVICE, flags)
    }

    @Implementation
    override fun wipeData(flags: Int) {
        record(WipeCall.WIPE_DATA, flags)
    }

    private fun record(call: WipeCall, flags: Int) {
        lastWipeCall = call
        lastWipeFlags = flags
        if (wipeThrows) throw SecurityException("device-owner wipe refused")
    }
}