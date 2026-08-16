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

package com.airgate.detector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimStateFilterTest {

    @Test
    fun `present states are treated as a breach`() {
        assertTrue(isSimPresentState("READY"))
        assertTrue(isSimPresentState("LOADED"))
        assertTrue(isSimPresentState("IMSI"))
        assertTrue(isSimPresentState("LOCKED"))
        assertTrue(isSimPresentState("CARD_IO_ERROR"))
        assertTrue(isSimPresentState("CARD_RESTRICTED"))
    }

    @Test
    fun `absent and transient states are ignored`() {
        assertFalse(isSimPresentState("ABSENT"))
        assertFalse(isSimPresentState("NOT_READY"))
        assertFalse(isSimPresentState("UNKNOWN"))
    }

    @Test
    fun `missing sim state extra is ignored`() {
        assertFalse(isSimPresentState(null))
    }
}
