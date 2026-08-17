/*
 * Copyright (C) 2026 The Airgate project contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 */

package com.airgate.ui.screens

import com.airgate.policy.ShieldLayerStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionVectorsContentTest {

    @Test
    fun `policy-backed vectors show checking before shield data arrives`() {
        assertEquals("Checking…", resolveVectorStatus(emptyList(), 0))
        assertEquals("Checking…", resolveVectorStatus(emptyList(), 1))
        assertEquals("Checking…", resolveVectorStatus(emptyList(), 2))
    }

    @Test
    fun `policy-backed vectors show the shield status including Unknown`() {
        val statuses = listOf(
            ShieldLayerStatus("owner", "", "Enforced", true),
            ShieldLayerStatus("wireless", "", "Unknown", false),
            ShieldLayerStatus("usb", "", "At Risk", false)
        )

        assertEquals("Enforced", resolveVectorStatus(statuses, 0))
        assertEquals("Unknown", resolveVectorStatus(statuses, 1))
        assertEquals("At Risk", resolveVectorStatus(statuses, 2))
    }

    @Test
    fun `network vector remains active because it has no shield row`() {
        assertEquals("Active", resolveVectorStatus(emptyList(), null))
    }
}
