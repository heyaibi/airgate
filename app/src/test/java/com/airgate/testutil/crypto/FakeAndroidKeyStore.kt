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

package com.airgate.testutil.crypto

import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/**
 * Registers a JCE [Provider] named `AndroidKeyStore` so the app's real
 * [com.airgate.data.crypto.KeystoreManager] runs under Robolectric.
 *
 * Robolectric cannot shadow the `java.security` classes, so the platform
 * AndroidKeyStore is absent on the JVM and `KeyStore.getInstance("AndroidKeyStore")`
 * throws "AndroidKeyStore not found". The community-standard workaround (see
 * the Robolectric issue #1518 discussion and ProAndroidDev "Testing Jetpack
 * Security with Robolectric") is to register our own JCE provider under that
 * name that backs the keystore with real crypto:
 *
 *  - keys are held in an in-memory, per-sandbox registry keyed by alias
 *    ([FakeAndroidKeyStoreRegistry]), so alias-based recovery behaves like a
 *    real keystore within one test process;
 *  - `KeyGenerator` for AES and HmacSHA256 delegates to the JVM's real
 *    implementations, reading the alias out of the [android.security.keystore.KeyGenParameterSpec];
 *  - the actual AES-GCM + HMAC-SHA256 work is the real JCE crypto
 *    ([KeystoreManager.encrypt]/[KeystoreManager.decrypt]/[KeystoreManager.hmac]
 *    use the default provider), so the protected-store tamper tests exercise
 *    genuine cipher integrity checks, not a mock.
 *
 * The provider is registered once per JVM (java.security.Security is shared
 * across Robolectric sandboxes); it is harmless to call [install] repeatedly.
 */
object FakeAndroidKeyStore {

    private const val PROVIDER_NAME = "AndroidKeyStore"

    /**
     * Registers the fake provider if it is not already present. Calling this
     * from a JUnit `@Before` (or a TestRule) of any test that builds a
     * [com.airgate.data.repository.SecurityStateRepository] with the default
     * AndroidKeyStore-backed crypto makes the real [com.airgate.data.crypto.KeystoreManager]
     * usable on the JVM.
     */
    fun install() {
        if (Security.getProvider(PROVIDER_NAME) != null) return
        Security.insertProviderAt(
            object : Provider(PROVIDER_NAME, 1.0, "Robolectric fake AndroidKeyStore backed by real JCE crypto") {
                init {
                    put("KeyStore.AndroidKeyStore", FakeAndroidKeyStoreSpi::class.java.name)
                    put("KeyGenerator.AES", FakeAesKeyGenerator::class.java.name)
                    put("KeyGenerator.HmacSHA256", FakeHmacKeyGenerator::class.java.name)
                }
            },
            1
        )
    }

    /** In-memory alias → key storage, shared by the keystore SPI and the generators. */
    internal object Registry {
        val keys = HashMap<String, SecretKey>()
    }
}

/**
 * A `KeyStoreSpi` for the fake `AndroidKeyStore` provider. Only the operations
 * [com.airgate.data.crypto.KeystoreManager] and its recovery path use are
 * supported: alias checks, secret-key reads, deletion, and get-entry (the
 * generated keys are stored by the generators via the registry, mirroring how a
 * real keystore persists key material by alias).
 */
class FakeAndroidKeyStoreSpi : KeyStoreSpi() {
    private val keys = FakeAndroidKeyStore.Registry.keys

    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit

    override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)
    override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)
    override fun engineIsCertificateEntry(alias: String): Boolean = false
    override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
    override fun engineSize(): Int = keys.size
    override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys)

    override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
    override fun engineGetCertificate(alias: String): Certificate? = null
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
    override fun engineGetCreationDate(alias: String): Date? = keys[alias]?.let { Date() }
    override fun engineGetCertificateAlias(cert: Certificate): String? = null

    override fun engineGetEntry(alias: String, protParam: KeyStore.ProtectionParameter?): KeyStore.Entry? =
        keys[alias]?.let { KeyStore.SecretKeyEntry(it) }

    override fun engineSetKeyEntry(alias: String, key: Key?, password: CharArray?, chain: Array<out Certificate>?) {
        keys[alias] = key as SecretKey
    }

    override fun engineSetKeyEntry(alias: String, key: ByteArray?, chain: Array<out Certificate>?) =
        throw UnsupportedOperationException("byte[] key material is not supported by the fake keystore")

    override fun engineSetCertificateEntry(alias: String, cert: Certificate) =
        throw UnsupportedOperationException("certificate entries are not supported by the fake keystore")
}

/**
 * Delegating [KeyGeneratorSpi]: generates a real key of [algorithm] on the JVM
 * and stores it in the fake keystore's registry under the alias carried by the
 * [android.security.keystore.KeyGenParameterSpec], exactly as the platform
 * AndroidKeyStore does.
 */
abstract class FakeKeyGeneratorSpi(private val algorithm: String) : KeyGeneratorSpi() {
    private var alias: String = ""
    private var random: SecureRandom = SecureRandom()

    override fun engineInit(random: SecureRandom?) { this.random = random ?: SecureRandom() }

    override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
        @Suppress("DEPRECATION")
        this.alias = (params as android.security.keystore.KeyGenParameterSpec).keystoreAlias
        this.random = random ?: SecureRandom()
    }

    override fun engineInit(keysize: Int, random: SecureRandom?) { this.random = random ?: SecureRandom() }

    override fun engineGenerateKey(): SecretKey {
        // Ask a real JCE provider, never the fake "AndroidKeyStore" provider: an
        // unqualified lookup would resolve back to this SPI and recurse forever.
        val generator = KeyGenerator.getInstance(algorithm, REAL_JCE_PROVIDER)
        generator.init(random)
        val key = generator.generateKey()
        FakeAndroidKeyStore.Registry.keys[alias] = key
        return key
    }

    private companion object {
        const val REAL_JCE_PROVIDER = "SunJCE"
    }
}

/** AES key generation for the fake `AndroidKeyStore` provider. */
class FakeAesKeyGenerator : FakeKeyGeneratorSpi("AES")

/** HmacSHA256 key generation for the fake `AndroidKeyStore` provider. */
class FakeHmacKeyGenerator : FakeKeyGeneratorSpi("HmacSHA256")
