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

package com.airgate.signing

import java.io.File
import java.security.KeyStore
import java.security.MessageDigest

/**
 * Result of resolving which signature fingerprint to pin for a build type.
 */
sealed class SignaturePin {
    /** A real, well-formed SHA-256 (lowercase hex) of the signing certificate. */
    data class Pinned(val sha256Hex: String) : SignaturePin()

    /** The build must not proceed: no trustworthy pin can be established. */
    data class Failed(val reason: String) : SignaturePin()
}

/**
 * Computes the SHA-256 fingerprint of the certificate inside a signing keystore.
 *
 * The fingerprint is the digest of the certificate's DER-encoded bytes, rendered
 * as lowercase hex — the same value the running app computes at runtime via
 * [android.content.pm.Signature] and the same value `apksigner` reports as the
 * certificate digest.
 */
object SigningCertificateHasher {

    /**
     * Returns the lowercase-hex SHA-256 of the certificate stored under [keyAlias]
     * in [storeFile], or `null` when the keystore cannot be read with the given
     * credentials, the alias is absent, or the file is not a keystore at all.
     *
     * A `null` result must be treated as a hard build failure — never silenced by
     * falling back to another keystore or an empty fingerprint.
     */
    fun certificateSha256(
        storeFile: File,
        storePassword: String,
        keyAlias: String,
        keyPassword: String
    ): String? {
        val cert = loadCertificate(storeFile, storePassword, keyAlias) ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun loadCertificate(
        storeFile: File,
        storePassword: String,
        keyAlias: String
    ): java.security.cert.Certificate? {
        if (!storeFile.isFile) return null
        for (type in listOf("PKCS12", "JKS")) {
            try {
                val keyStore = KeyStore.getInstance(type)
                storeFile.inputStream().use { keyStore.load(it, storePassword.toCharArray()) }
                val cert = keyStore.getCertificate(keyAlias)
                if (cert != null) return cert
            } catch (e: Exception) {
                // Try the next supported store type.
            }
        }
        return null
    }
}

/**
 * Decides, per build type, which signing certificate fingerprint to pin into
 * `EXPECTED_SIGNATURE_HASH`, or why the build must fail instead.
 *
 * Debug builds are signed with the standard debug keystore, so the debug pin is
 * the debug keystore's own certificate. Release builds are signed with the
 * configured release keystore and must never fall back to the (public, widely
 * known) debug key — a release build without a configured release keystore is a
 * build failure, never a silently mis-pinned artifact.
 */
object SignaturePinResolver {

    const val DEBUG_KEYSTORE_PASSWORD = "android"
    const val DEBUG_KEY_ALIAS = "androiddebugkey"
    const val DEBUG_KEY_PASSWORD = "android"

    /** Path of the `keytool` binary shipped with the JDK running the build. */
    fun defaultKeytool(): File = File(System.getProperty("java.home"), "bin/keytool")

    /**
     * Ensures the debug keystore exists at [storeFile], creating it (when missing)
     * with the exact credentials Android Gradle Plugin uses, so the debug build is
     * signed with a keystore whose fingerprint we can pin deterministically.
     */
    fun ensureDebugKeystore(storeFile: File, keytool: File = defaultKeytool()): Boolean {
        if (storeFile.isFile) return true
        storeFile.parentFile?.mkdirs()
        return try {
            val process = ProcessBuilder(
                keytool.absolutePath,
                "-genkeypair",
                "-alias", DEBUG_KEY_ALIAS,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "10000",
                "-keystore", storeFile.absolutePath,
                "-storetype", "PKCS12",
                "-storepass", DEBUG_KEYSTORE_PASSWORD,
                "-keypass", DEBUG_KEY_PASSWORD,
                "-dname", "CN=Android Debug,O=Android,C=US"
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val ok = process.waitFor() == 0 && storeFile.isFile
            if (!ok) println("keytool failed: $output")
            ok
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Resolves the fingerprint to pin for the debug build type, using the exact
     * keystore path Android Gradle Plugin will sign the debug APK with. The file
     * is created (when missing) with the standard debug credentials so the
     * fingerprint is deterministic and always matches the signing key.
     */
    fun debugPin(storeFile: File?): SignaturePin {
        if (storeFile == null) {
            return SignaturePin.Failed(
                "Debug build cannot establish a signature pin: no debug signing " +
                    "keystore is configured."
            )
        }
        if (!ensureDebugKeystore(storeFile)) {
            return SignaturePin.Failed(
                "Debug build cannot establish a signature pin: unable to create " +
                    "the debug keystore at ${storeFile.absolutePath}"
            )
        }
        val hash = SigningCertificateHasher.certificateSha256(
            storeFile, DEBUG_KEYSTORE_PASSWORD, DEBUG_KEY_ALIAS, DEBUG_KEY_PASSWORD
        )
        return if (hash != null) {
            SignaturePin.Pinned(hash)
        } else {
            SignaturePin.Failed(
                "Debug build cannot establish a signature pin: unreadable debug " +
                    "keystore at ${storeFile.absolutePath}"
            )
        }
    }

    /** Resolves the fingerprint to pin for the release build type. */
    fun releasePin(
        storeFile: File?,
        storePassword: String?,
        keyAlias: String?,
        keyPassword: String?
    ): SignaturePin {
        if (storeFile == null) {
            return SignaturePin.Failed(
                "Release build requires a configured release signing keystore " +
                    "(AG_RELEASE_STORE_FILE). A release must never fall back to the " +
                    "public debug key or ship without a pinned signature."
            )
        }
        if (storePassword.isNullOrEmpty() || keyAlias.isNullOrEmpty() || keyPassword.isNullOrEmpty()) {
            return SignaturePin.Failed(
                "Release signing keystore configured without full credentials " +
                    "(AG_RELEASE_STORE_PASSWORD / AG_RELEASE_KEY_ALIAS / AG_RELEASE_KEY_PASSWORD)."
            )
        }
        val hash = SigningCertificateHasher.certificateSha256(storeFile, storePassword, keyAlias, keyPassword)
        return if (hash != null) {
            SignaturePin.Pinned(hash)
        } else {
            SignaturePin.Failed(
                "Release signing keystore ${storeFile.absolutePath} could not be " +
                    "read with the configured credentials."
            )
        }
    }
}
