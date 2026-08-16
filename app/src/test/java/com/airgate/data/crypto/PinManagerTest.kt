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

package com.airgate.data.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinManagerTest {
    private val pinManager = PinManager(iterations = 1000, keyLengthBits = 256)

    @Test
    fun `hashPin and verifyPin success with valid pin`() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin(pin, salt)

        assertTrue(pinManager.verifyPin(pin, salt, hash))
    }

    @Test
    fun `verifyPin fails with incorrect pin`() {
        val pin = "123456"
        val wrongPin = "654321"
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin(pin, salt)

        assertFalse(pinManager.verifyPin(wrongPin, salt, hash))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `hashPin throws exception for short pin`() {
        val pin = "12345"
        val salt = pinManager.generateSalt()
        pinManager.hashPin(pin, salt)
    }
}
