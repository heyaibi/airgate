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

package com.airgate.ui.screens

import com.airgate.domain.model.ViolationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the in-app violations catalogue ([guideItems]) to the [ViolationType]
 * enum so the two lists cannot silently drift: every violation type must have
 * exactly one guide entry, and every entry must describe a real violation.
 */
class ViolationGuideCoverageTest {

    @Test
    fun `every violation type has exactly one guide entry`() {
        val listed = guideItems.map { it.violationType }

        assertEquals(
            "the guide must list every violation type exactly once",
            ViolationType.entries.toSet(),
            listed.toSet()
        )
        assertEquals(
            "no violation type may be listed twice",
            listed.size,
            listed.distinct().size
        )
    }

    @Test
    fun `every guide entry describes a real violation`() {
        assertTrue(
            "every guide entry must map to a declared violation type",
            guideItems.all { it.violationType in ViolationType.entries }
        )
    }
}