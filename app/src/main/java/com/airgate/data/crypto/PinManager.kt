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

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PinManager(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val keyLengthBits: Int = DEFAULT_KEY_LENGTH_BITS
) {
    companion object {
        const val DEFAULT_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val DEFAULT_ITERATIONS = 120_000
        const val DEFAULT_KEY_LENGTH_BITS = 256
        private const val SALT_LENGTH_BYTES = 16
    }

    fun generateSalt(): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH_BYTES)
        random.nextBytes(salt)
        return salt
    }

    fun hashPin(
        pin: String,
        salt: ByteArray,
        iterations: Int = this.iterations,
        algorithm: String = DEFAULT_ALGORITHM
    ): ByteArray {
        require(pin.length >= 6) { "PIN must be at least 6 digits/characters" }
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, keyLengthBits)
        try {
            val factory = SecretKeyFactory.getInstance(algorithm)
            return factory.generateSecret(spec).encoded
        } finally {
            // The derived key material is held in memory; clearing the PBEKeySpec
            // also wipes its internal key copy.
            spec.clearPassword()
        }
    }

    fun verifyPin(
        pin: String,
        salt: ByteArray,
        expectedHash: ByteArray,
        iterations: Int = this.iterations,
        algorithm: String = DEFAULT_ALGORITHM
    ): Boolean {
        if (pin.length < 6) return false
        val computedHash = hashPin(pin, salt, iterations, algorithm)
        return MessageDigest.isEqual(computedHash, expectedHash)
    }
}
