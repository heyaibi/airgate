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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Branch coverage for the build-time signature pin resolution.
 *
 * The hasher and resolver are pure JVM logic (no Android or Gradle APIs), so every
 * outcome is exercised here: a readable keystore yields the correct lowercase-hex
 * SHA-256 of its certificate; unreadable/missing/wrong-credential inputs yield null
 * (never an empty sentinel); debug and release pins are resolved independently; and
 * a release build without its own keystore fails closed.
 */
class SigningPinTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var keytool: File

    private val passwd = "changeit"

    @Before
    fun setUp() {
        keytool = File(System.getProperty("java.home"), "bin/keytool")
        assertTrue("keytool must be available for keystore fixtures", keytool.isFile)
    }

    // --- keystore fixture helpers (via keytool, mirroring AGP's own generation) ---

    private fun genKey(
        storeFile: File,
        alias: String = "releasekey",
        storeType: String = "PKCS12",
        password: String = passwd,
        keyPassword: String = passwd
    ) {
        val cmd = listOf(
            keytool.absolutePath,
            "-genkeypair",
            "-alias", alias,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "3650",
            "-keystore", storeFile.absolutePath,
            "-storetype", storeType,
            "-storepass", password,
            "-keypass", keyPassword,
            "-dname", "CN=Airgate Test,O=Test,C=US"
        )
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val exit = p.waitFor()
        assertTrue("keytool gen failed: $out", exit == 0)
    }

    private fun certSha256Fingerprint(storeFile: File, alias: String, password: String = passwd): String {
        val cmd = listOf(
            keytool.absolutePath,
            "-list",
            "-alias", alias,
            "-keystore", storeFile.absolutePath,
            "-storetype", "PKCS12",
            "-storepass", password,
            "-v"
        )
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        val line = out.lineSequence().firstOrNull { it.trim().startsWith("SHA256:") }
            ?: error("no SHA256 fingerprint in keytool output: $out")
        return line.substringAfter("SHA256:").replace(":", "").trim().lowercase()
    }

    // --- certificateSha256 ---

    @Test
    fun certificateSha256_validPkcs12_matchesKeytoolFingerprint() {
        val store = File(tmp.root, "valid.p12")
        genKey(store)

        val hash = SigningCertificateHasher.certificateSha256(store, passwd, "releasekey", passwd)

        assertNotNull(hash)
        assertTrue("must be 64 lowercase hex chars", hash!!.matches(Regex("[0-9a-f]{64}")))
        assertEquals(certSha256Fingerprint(store, "releasekey"), hash)
    }

    @Test
    fun certificateSha256_validJks_matchesKeytoolFingerprint() {
        val store = File(tmp.root, "valid.jks")
        genKey(store, storeType = "JKS")

        val hash = SigningCertificateHasher.certificateSha256(store, passwd, "releasekey", passwd)

        assertNotNull(hash)
        assertTrue(hash!!.matches(Regex("[0-9a-f]{64}")))
        assertEquals(certSha256Fingerprint(store, "releasekey"), hash)
    }

    @Test
    fun certificateSha256_missingFile_returnsNull() {
        val store = File(tmp.root, "does-not-exist.p12")

        assertNull(SigningCertificateHasher.certificateSha256(store, passwd, "releasekey", passwd))
    }

    @Test
    fun certificateSha256_wrongStorePassword_returnsNull() {
        val store = File(tmp.root, "wrongpass.p12")
        genKey(store)

        assertNull(SigningCertificateHasher.certificateSha256(store, "wrong", "releasekey", passwd))
    }

    @Test
    fun certificateSha256_wrongAlias_returnsNull() {
        val store = File(tmp.root, "wrongalias.p12")
        genKey(store)

        assertNull(SigningCertificateHasher.certificateSha256(store, passwd, "nosuchalias", passwd))
    }

    @Test
    fun certificateSha256_notAKeystore_returnsNull() {
        val notStore = tmp.newFile("junk.bin")
        notStore.writeBytes(ByteArray(256) { 0x42 })

        assertNull(SigningCertificateHasher.certificateSha256(notStore, passwd, "releasekey", passwd))
    }

    @Test
    fun certificateSha256_emptyFile_returnsNull() {
        val empty = tmp.newFile("empty.bin")

        assertNull(SigningCertificateHasher.certificateSha256(empty, passwd, "releasekey", passwd))
    }

    // --- ensureDebugKeystore ---

    @Test
    fun ensureDebugKeystore_createsReadableKeystore_whenMissing() {
        val store = File(tmp.root, ".android/debug.keystore")

        val created = SignaturePinResolver.ensureDebugKeystore(store, keytool)

        assertTrue(created)
        assertTrue(store.isFile)
        // Must be readable with the standard debug credentials and alias.
        assertNotNull(
            SigningCertificateHasher.certificateSha256(
                store,
                SignaturePinResolver.DEBUG_KEYSTORE_PASSWORD,
                SignaturePinResolver.DEBUG_KEY_ALIAS,
                SignaturePinResolver.DEBUG_KEY_PASSWORD
            )
        )
    }

    @Test
    fun ensureDebugKeystore_existingFile_isReused() {
        val store = File(tmp.root, ".android/debug.keystore")
        assertTrue(SignaturePinResolver.ensureDebugKeystore(store, keytool))
        val before = store.lastModified()

        val again = SignaturePinResolver.ensureDebugKeystore(store, keytool)

        assertTrue(again)
        assertEquals("existing debug keystore must not be regenerated", before, store.lastModified())
    }

    @Test
    fun ensureDebugKeystore_missingKeytool_returnsFalse() {
        val store = File(tmp.root, ".android/debug.keystore")
        val bogusKeytool = File(tmp.root, "no-such-keytool")

        assertFalse(SignaturePinResolver.ensureDebugKeystore(store, bogusKeytool))
        assertFalse("keystore must not be created without a working keytool", store.exists())
    }

    // --- debugPin ---

    @Test
    fun debugPin_existingDebugKeystore_pinsItsFingerprint() {
        val store = File(tmp.root, ".android/debug.keystore")
        assertTrue(SignaturePinResolver.ensureDebugKeystore(store, keytool))

        val pin = SignaturePinResolver.debugPin(store)

        assertTrue(pin is SignaturePin.Pinned)
        val hash = (pin as SignaturePin.Pinned).sha256Hex
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
        assertEquals(
            certSha256Fingerprint(
                store,
                SignaturePinResolver.DEBUG_KEY_ALIAS,
                SignaturePinResolver.DEBUG_KEYSTORE_PASSWORD
            ),
            hash
        )
    }

    @Test
    fun debugPin_missingKeystore_createsItAndPins() {
        val store = File(tmp.root, ".android/debug.keystore")

        val pin = SignaturePinResolver.debugPin(store)

        assertTrue(pin is SignaturePin.Pinned)
        assertTrue((pin as SignaturePin.Pinned).sha256Hex.matches(Regex("[0-9a-f]{64}")))
        assertTrue(store.isFile)
    }

    @Test
    fun debugPin_nullStoreFile_failsClosed() {
        val pin = SignaturePinResolver.debugPin(null)

        assertTrue(pin is SignaturePin.Failed)
        assertTrue((pin as SignaturePin.Failed).reason.isNotBlank())
    }

    @Test
    fun debugPin_unusableKeytool_failsClosed() {
        val noKeytoolHome = tmp.newFolder("nohome")
        val store = File(tmp.root, ".android/debug.keystore")
        // Point defaultKeytool resolution at a broken keytool path.
        val original = System.getProperty("java.home")
        try {
            System.setProperty("java.home", noKeytoolHome.absolutePath)
            val pin = SignaturePinResolver.debugPin(store)

            assertTrue(pin is SignaturePin.Failed)
            assertTrue((pin as SignaturePin.Failed).reason.isNotBlank())
        } finally {
            System.setProperty("java.home", original)
        }
    }

    // --- releasePin ---

    @Test
    fun releasePin_noStoreFile_failsClosed() {
        val pin = SignaturePinResolver.releasePin(null, passwd, "releasekey", passwd)

        assertTrue(pin is SignaturePin.Failed)
        assertTrue((pin as SignaturePin.Failed).reason.contains("AG_RELEASE_STORE_FILE"))
    }

    @Test
    fun releasePin_missingCredentials_failsClosed() {
        val store = File(tmp.root, "creds.p12")
        genKey(store)

        assertTrue(SignaturePinResolver.releasePin(store, null, "releasekey", passwd) is SignaturePin.Failed)
        assertTrue(SignaturePinResolver.releasePin(store, passwd, null, passwd) is SignaturePin.Failed)
        assertTrue(SignaturePinResolver.releasePin(store, passwd, "releasekey", null) is SignaturePin.Failed)
        assertTrue(SignaturePinResolver.releasePin(store, "", "releasekey", passwd) is SignaturePin.Failed)
    }

    @Test
    fun releasePin_validReleaseKeystore_pinsItsFingerprint() {
        val store = File(tmp.root, "release.p12")
        genKey(store)

        val pin = SignaturePinResolver.releasePin(store, passwd, "releasekey", passwd)

        assertTrue(pin is SignaturePin.Pinned)
        assertEquals(certSha256Fingerprint(store, "releasekey"), (pin as SignaturePin.Pinned).sha256Hex)
    }

    @Test
    fun releasePin_wrongPassword_failsClosed() {
        val store = File(tmp.root, "wrongpw.p12")
        genKey(store)

        val pin = SignaturePinResolver.releasePin(store, "not-the-password", "releasekey", passwd)

        assertTrue(pin is SignaturePin.Failed)
    }

    @Test
    fun releasePin_wrongAlias_failsClosed() {
        val store = File(tmp.root, "wrongal.p12")
        genKey(store)

        val pin = SignaturePinResolver.releasePin(store, passwd, "other-alias", passwd)

        assertTrue(pin is SignaturePin.Failed)
    }

    @Test
    fun releasePin_missingStoreFile_failsClosed() {
        val store = File(tmp.root, "no-such-release.p12")

        val pin = SignaturePinResolver.releasePin(store, passwd, "releasekey", passwd)

        assertTrue(pin is SignaturePin.Failed)
    }

    @Test
    fun releasePin_neverReturnsEmptyFingerprint() {
        val store = File(tmp.root, "never-empty.p12")
        genKey(store)

        val pin = SignaturePinResolver.releasePin(store, passwd, "releasekey", passwd)

        assertTrue(pin is SignaturePin.Pinned)
        assertFalse((pin as SignaturePin.Pinned).sha256Hex.isEmpty())
    }

    @Test
    fun debugPin_neverReturnsEmptyFingerprint() {
        val store = File(tmp.root, ".android/debug.keystore")

        val pin = SignaturePinResolver.debugPin(store)

        assertTrue(pin is SignaturePin.Pinned)
        assertFalse((pin as SignaturePin.Pinned).sha256Hex.isEmpty())
    }
}
