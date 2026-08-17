/*
 * Copyright (C) 2026 The Airgate project contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation: either version 3 of the License, or
 * (at your option) any later version.
 */

package com.airgate.dhizuku

import android.content.ComponentName
import com.rosan.dhizuku.api.Dhizuku

internal interface DhizukuOwnerResolver {
    fun ownerPackageName(): String
    fun ownerComponent(): ComponentName
}

internal object RealDhizukuOwnerResolver : DhizukuOwnerResolver {
    override fun ownerPackageName(): String = Dhizuku.getOwnerPackageName()

    override fun ownerComponent(): ComponentName = Dhizuku.getOwnerComponent()
}
