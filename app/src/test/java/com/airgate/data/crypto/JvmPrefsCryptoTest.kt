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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * AAD (associated-data) binding of the AES-GCM ciphertext at the crypto layer.
 * The authentication tag must cover the AAD, so a blob encrypted for one key
 * name never decrypts under another — this is what makes a ciphertext swap
 * between pref keys undetectable-proof at the cipher level, independent of the
 * store's outer keyed-MAC.
 */
class JvmPrefsCryptoTest {

    private val crypto = JvmPrefsCrypto()

    private val keyA = "config_is_enabled".toByteArray(Charsets.UTF_8)
    private val keyB = "config_dry_run_mode".toByteArray(Charsets.UTF_8)

    @Test
    fun encryptDecrypt_roundTrips_withMatchingAad() {
        val plaintext = "true".toByteArray(Charsets.UTF_8)
        val (ciphertext, iv) = crypto.encrypt(plaintext, keyA)

        assertArrayEquals(plaintext, crypto.decrypt(ciphertext, iv, keyA))
    }

    @Test
    fun decrypt_withDifferentAad_throws() {
        val (ciphertext, iv) = crypto.encrypt("true".toByteArray(Charsets.UTF_8), keyA)

        assertThrows(AEADBadTagException::class.java) {
            crypto.decrypt(ciphertext, iv, keyB)
        }
    }

    @Test
    fun decrypt_withEmptyAad_whenEncryptedWithKeyAad_throws() {
        // An attacker that strips the key binding and treats the blob as legacy
        // (no AAD) must fail the GCM tag verification.
        val (ciphertext, iv) = crypto.encrypt("true".toByteArray(Charsets.UTF_8), keyA)

        assertThrows(AEADBadTagException::class.java) {
            crypto.decrypt(ciphertext, iv, ByteArray(0))
        }
    }

    @Test
    fun decrypt_withKeyAad_whenEncryptedWithEmptyAad_throws() {
        // The reverse: a legacy blob encrypted with no AAD must fail to decrypt
        // under the key-name AAD, so the store fails closed on legacy values.
        val (ciphertext, iv) = crypto.encrypt("true".toByteArray(Charsets.UTF_8), ByteArray(0))

        assertThrows(AEADBadTagException::class.java) {
            crypto.decrypt(ciphertext, iv, keyA)
        }
    }

    @Test
    fun sameAad_anyPlaintext_roundTrips() {
        val (ciphertext, iv) = crypto.encrypt("VALUE_B".toByteArray(Charsets.UTF_8), keyA)

        assertArrayEquals(
            "VALUE_B".toByteArray(Charsets.UTF_8),
            crypto.decrypt(ciphertext, iv, keyA)
        )
    }
}
