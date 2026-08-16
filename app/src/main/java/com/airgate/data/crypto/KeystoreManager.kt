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

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore-backed [PrefsCrypto]: AES-GCM for confidentiality and an
 * HMAC-SHA256 key for the tamper-detection MAC. Both keys are generated and
 * held inside the Android Keystore so the material never appears in the app
 * package or a backup.
 *
 * Key aliases are injectable so on-device tests can exercise key loss and
 * regeneration without touching the production keys. A key that exists but
 * cannot be recovered (corrupted / permanently invalidated) is deleted and
 * regenerated so protected values keep working; values encrypted under the lost
 * key can no longer be verified and fail closed at the store layer.
 */
class KeystoreManager(
    private val masterKeyAlias: String = KEY_ALIAS,
    private val hmacKeyAlias: String = HMAC_KEY_ALIAS
) : PrefsCrypto {
    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "AirgateMasterKey"
        private const val HMAC_KEY_ALIAS = "AirgateHmacKey"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }

    // Load the keystore once and cache the key references. AndroidKeyStore
    // SecretKey handles are stable for the lifetime of the process, so reusing the
    // references across every encrypt/decrypt/hmac avoids two binder round-trips
    // (KeyStore.getInstance + load + getEntry) per operation. Cipher ops still hit
    // the keystore daemon per call, which is unavoidable and thread-safe.
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private val masterKey: SecretKey = recoverKey(masterKeyAlias, ::generateMasterKey)
    private val hmacKey: SecretKey = recoverKey(hmacKeyAlias, ::generateHmacKey)

    /**
     * Fetches-or-recovers the key for [alias] via a pure, JVM-testable
     * [KeystoreKeyRecovery]: an absent key is generated, and an existing key
     * whose material cannot be recovered (corrupted) is deleted and regenerated.
     * Regeneration is the recovery path — values encrypted under the lost key
     * can no longer be verified and fail closed at the store layer, so the owner
     * re-provisions. Only `UnrecoverableKeyException` triggers regeneration: it
     * is the documented "key cannot be recovered" signal, and deleting a healthy
     * key on a transient keystore blip would force an unnecessary full
     * re-provision.
     */
    private fun recoverKey(alias: String, generate: (String) -> Unit): SecretKey {
        return KeystoreKeyRecovery(
            containsAlias = { keyStore.containsAlias(it) },
            readSecretKey = { (keyStore.getEntry(it, null) as KeyStore.SecretKeyEntry).secretKey },
            deleteAlias = { keyStore.deleteEntry(it) },
            generateKey = generate
        ).ensureKey(alias)
    }

    private fun generateMasterKey(alias: String) {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val parameterSpec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(parameterSpec)
        keyGenerator.generateKey()
    }

    private fun generateHmacKey(alias: String) {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            ANDROID_KEYSTORE
        )
        val parameterSpec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN
        )
            .setKeySize(256)
            .build()

        keyGenerator.init(parameterSpec)
        keyGenerator.generateKey()
    }

    override fun encrypt(data: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(data)
        val iv = cipher.iv
        return Pair(ciphertext, iv)
    }

    override fun decrypt(ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    override fun hmac(data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey)
        return mac.doFinal(data)
    }
}
