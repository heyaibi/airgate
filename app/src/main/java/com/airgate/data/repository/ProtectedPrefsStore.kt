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

package com.airgate.data.repository

import android.content.SharedPreferences
import androidx.core.content.edit
import com.airgate.data.crypto.KeystoreManager
import com.airgate.data.crypto.PrefsCrypto
import java.security.MessageDigest
import java.util.Base64

/**
 * [PrefsCrypto]-backed typed access to SharedPreferences that never degrades to
 * plaintext. The AndroidKeyStore-backed implementation is the default; pure-JVM
 * tests inject a [PrefsCrypto] so the encrypted path runs without the keystore.
 * If no crypto is available at all (keystore init failure) or an encryption
 * operation throws, protected writes are refused and the failure is latched as
 * a consumable tamper flag — a security-sensitive value is never persisted in
 * the clear. [protectedPutAll] additionally batches a set of values into one
 * atomic synchronous SharedPreferences commit, so a refusal or disk failure
 * on any member refuses the whole batch: callers can rely on its Boolean being
 * the real persistence signal rather than an optimistic in-memory apply.
 *
 * Every protected value is stored as `enc:<iv>:<ciphertext>:<mac>` where the
 * MAC is an HMAC-SHA256 over `key || iv || ciphertext` (encrypt-then-MAC) and
 * the AES-GCM operation itself is bound to the pref key via associated data,
 * so a ciphertext moved between keys fails at the crypto layer as well as the
 * MAC check. Binding the pref key into both the MAC and the GCM tag means a
 * value moved between keys (a ciphertext swap) verifies under only its own key
 * and is otherwise detected as tampering. A value that no longer verifies or
 * decrypts — i.e. an attacker rewrote the enforcement state — is surfaced as a
 * consumable tamper flag so callers can fail closed instead of trusting a
 * corrupt default. A plaintext value found under a protected key is
 * indistinguishable from an attacker-written value and is treated as tampering
 * too: it is never read through as if it were trusted.
 */
internal class ProtectedPrefsStore(
    private val prefs: SharedPreferences,
    crypto: PrefsCrypto? = null,
    private val cryptoFactory: () -> PrefsCrypto? = { runCatching { KeystoreManager() }.getOrNull() }
) {
    companion object {
        private const val ENC_PREFIX = "enc:"
        private const val ENCRYPTED_PARTS = 3
        private val processTamperLatch = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * True when any previously integrity-protected value failed to verify/decrypt
         * across any store instance in the process. Consumed by the periodic audit;
         * consuming clears the flag atomically.
         */
        fun consumeProcessTamperFlag(): Boolean = processTamperLatch.getAndSet(false)

        /**
         * Latches the process-wide tamper flag.
         */
        fun markTampered() {
            processTamperLatch.set(true)
        }
    }

    private val injectedCrypto: PrefsCrypto? = crypto

    // Cached lazily so that merely constructing a store never performs keystore
    // binder work; the first protected read/write pays that cost instead. A
    // successful construction is cached for the process, but a FAILED attempt is
    // not: the next access re-tries, so a transient keystore outage cannot
    // permanently freeze protected writes for the rest of the process.
    @Volatile
    private var fallbackCrypto: PrefsCrypto? = null

    private val keystore: PrefsCrypto?
        get() = injectedCrypto ?: fallbackCryptoOrCreate()

    private fun fallbackCryptoOrCreate(): PrefsCrypto? {
        fallbackCrypto?.let { return it }
        synchronized(this) {
            fallbackCrypto?.let { return it }
            val created = cryptoFactory()
            if (created != null) fallbackCrypto = created
            return created
        }
    }

    /**
     * True when a previously integrity-protected value failed to verify/decrypt.
     * Consumed by the periodic audit; consuming clears the flag across all repository instances.
     */
    fun consumeTamperFlag(): Boolean = consumeProcessTamperFlag()

    /** Coerces a raw prefs value (string or legacy primitive) to its string form. */
    fun rawToString(value: Any?): String? = when (value) {
        is String -> value
        is Int -> value.toString()
        is Long -> value.toString()
        is Float -> value.toString()
        is Double -> value.toString()
        is Boolean -> value.toString()
        else -> null
    }

    /**
     * Reads the raw stored value for a key without decrypting it. Coerces legacy
     * primitive-typed values (putInt/putBoolean from pre-encryption builds) to their
     * string form so the fingerprint comparison never throws and always stays stable.
     */
    fun readRawPref(key: String): String? {
        val value = try {
            prefs.all[key]
        } catch (e: Exception) {
            return null
        }
        return rawToString(value)
    }

    /**
     * Encrypts [value] and stamps it with a keyed-MAC bound to [key].
     * A blob protected for one key never verifies under another.
     *
     * Fails closed: when no crypto is available or the operation throws, the
     * value is NOT returned in plaintext — an [IllegalStateException] is thrown
     * and the tamper flag is latched so the failure surfaces to the audit.
     */
    fun protectString(key: String, value: String): String {
        val ks = keystore
        if (ks == null) {
            markTampered()
            throw IllegalStateException("no crypto available; refusing to persist '$key' in plaintext")
        }
        return try {
            val aad = key.toByteArray(Charsets.UTF_8)
            val (ciphertext, iv) = ks.encrypt(value.toByteArray(Charsets.UTF_8), aad)
            val mac = ks.hmac(aad + iv + ciphertext)
            ENC_PREFIX +
                Base64.getEncoder().encodeToString(iv) + ":" +
                Base64.getEncoder().encodeToString(ciphertext) + ":" +
                Base64.getEncoder().encodeToString(mac)
        } catch (e: Exception) {
            markTampered()
            throw IllegalStateException("failed to protect value for key '$key'", e)
        }
    }

    fun unprotectString(key: String, stored: String, default: String): String {
        return tryUnprotect(key, stored) ?: run {
            markTampered()
            default
        }
    }

    /**
     * Reads a protected value that cannot be safely defaulted, returning null
     * when it is missing or cannot be verified/decrypted (tamper/corruption).
     * Callers use this to fail closed: an unreadable value is never silently
     * coerced into a "compliant" default.
     */
    fun readProtectedValueOrNull(key: String): String? {
        val stored = readStoredString(key) ?: return null
        return tryUnprotect(key, stored) ?: run {
            markTampered()
            null
        }
    }

    private fun tryUnprotect(key: String, stored: String): String? {
        // A non-"enc:" value under a protected key is either a legacy plaintext
        // write or an attacker's plaintext; it carries no integrity or key
        // binding and cannot be distinguished from tampering, so it fails closed
        // exactly like a corrupt blob.
        if (!stored.startsWith(ENC_PREFIX)) return null
        val ks = keystore
        if (ks == null) return null
        return runCatching {
            val parts = stored.removePrefix(ENC_PREFIX).split(":")
            if (parts.size != ENCRYPTED_PARTS) {
                // The only supported format is the current enc:<iv>:<cipher>:<mac>.
                // Older two-part blobs (AES-GCM without the keyed-MAC or AAD
                // binding) are deliberately not readable: without the key binding
                // they cannot prove which pref key they belong to, so a ciphertext
                // swap could not be ruled out. They fail closed like any tamper.
                throw IllegalStateException("malformed protected value")
            }
            val iv = Base64.getDecoder().decode(parts[0])
            val ciphertext = Base64.getDecoder().decode(parts[1])
            val mac = Base64.getDecoder().decode(parts[2])
            val aad = key.toByteArray(Charsets.UTF_8)
            val computed = ks.hmac(aad + iv + ciphertext)
            if (!MessageDigest.isEqual(computed, mac)) {
                throw IllegalStateException("integrity check failed")
            }
            String(ks.decrypt(ciphertext, iv, aad), Charsets.UTF_8)
        }.getOrNull()
    }

    fun protectedPutString(key: String, value: String): Boolean {
        return try {
            prefs.edit { putString(key, protectString(key, value)) }
            true
        } catch (e: IllegalStateException) {
            // The value cannot be protected, so it is not persisted at all —
            // never in plaintext. The latched tamper flag surfaces the failure
            // to the periodic audit instead of leaving a silent downgrade.
            markTampered()
            false
        }
    }

    /**
     * Encrypts and persists a batch of key/value pairs as a single atomic
     * SharedPreferences commit.
     *
     * Every value is protected (encrypt + keyed-MAC) before anything is
     * written; if any value cannot be protected the whole batch is refused and
     * nothing is persisted — never a torn, partially-encrypted state. The
     * commit is synchronous, so the returned Boolean is the real disk-write
     * success signal, unlike [protectedPutString] whose asynchronous apply()
     * cannot report disk failures. A refused or failed commit latches the
     * tamper flag so the failure reaches the periodic audit.
     */
    fun protectedPutAll(entries: List<Pair<String, String>>): Boolean {
        val protected = try {
            entries.map { (key, value) -> key to protectString(key, value) }
        } catch (e: IllegalStateException) {
            // protectString already latched the tamper flag; nothing was written.
            return false
        }
        val editor = prefs.edit()
        protected.forEach { (key, blob) -> editor.putString(key, blob) }
        val committed = editor.commit()
        if (!committed) markTampered()
        return committed
    }

    fun protectedGetString(key: String, default: String): String {
        val stored = readStoredString(key) ?: return default
        return unprotectString(key, stored, default)
    }

    fun protectedPutInt(key: String, value: Int): Boolean = protectedPutString(key, value.toString())

    fun protectedGetInt(key: String, default: Int): Int =
        protectedGetString(key, default.toString()).toIntOrNull() ?: default

    fun protectedPutBoolean(key: String, value: Boolean): Boolean = protectedPutString(key, value.toString())

    fun protectedGetBoolean(key: String, default: Boolean): Boolean =
        protectedGetString(key, default.toString()).toBooleanStrictOrNull() ?: default

    /**
     * Removes a persisted key entirely. Used to clear optional protected state
     * (e.g. the acknowledged-pending-alarm marker) rather than leaving a value
     * behind.
     */
    fun removeProtected(key: String) {
        prefs.edit { remove(key) }
    }

    private fun readStoredString(key: String): String? {
        return try {
            prefs.getString(key, null)
        } catch (e: ClassCastException) {
            // Legacy installs stored this key with putInt/putBoolean before the
            // encryption migration; getString would throw, so coerce the old
            // typed value to its string form instead of crashing.
            when (val legacy = prefs.all[key]) {
                is Int -> legacy.toString()
                is Long -> legacy.toString()
                is Float -> legacy.toString()
                is Double -> legacy.toString()
                is Boolean -> legacy.toString()
                else -> null
            }
        }
    }
}
