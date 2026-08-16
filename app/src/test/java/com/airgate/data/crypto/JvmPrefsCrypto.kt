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
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-JVM [PrefsCrypto] for unit tests: AES-GCM + HMAC-SHA256 via the JCA
 * providers that ship with any desktop JVM, using deterministic test keys
 * derived from [seed]. Lets the encrypted + keyed-MAC paths of
 * [com.airgate.data.repository.ProtectedPrefsStore] be exercised without
 * the Android Keystore. Two instances with different seeds hold independent
 * keys, which is how ciphertext-swap tests build a genuinely foreign blob.
 */
class JvmPrefsCrypto(seed: String = "jvm-prefs-crypto") : PrefsCrypto {

    private val aesKey = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest("$seed-aes".toByteArray(Charsets.UTF_8)), "AES"
    )
    private val hmacKey = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest("$seed-hmac".toByteArray(Charsets.UTF_8)), "HmacSHA256"
    )

    override fun encrypt(data: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey)
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(data)
        return Pair(ciphertext, cipher.iv)
    }

    override fun decrypt(ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    override fun hmac(data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey)
        return mac.doFinal(data)
    }
}
