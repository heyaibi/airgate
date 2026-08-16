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

/**
 * Encryption + keyed-MAC (integrity) over persisted protected values.
 *
 * The keyed-MAC is the tamper-detection layer: every protected value is stamped
 * with a message authentication code computed under a key that never leaves the
 * Android Keystore, and the stamp is re-verified before the value is trusted.
 * AES-GCM already authenticates the ciphertext, but the MAC makes the integrity
 * check explicit and independent of the encryption cipher, and it is what a
 * tampered value fails before the code even attempts a decryption.
 *
 * Every ciphertext is additionally bound to the pref key that owns it: [aad] is
 * fed to the AES-GCM operation as associated data, so the authentication tag
 * only verifies when the exact same [aad] is supplied on decrypt. A ciphertext
 * moved between keys therefore fails decryption (not just the MAC check).
 */
interface PrefsCrypto {
    /** Encrypts [data] bound to [aad], returning (ciphertext, iv). */
    fun encrypt(data: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray>

    /** Decrypts [ciphertext] produced by [encrypt] with the same [iv] and [aad]. */
    fun decrypt(ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray

    /** Keyed-MAC over [data] under a keystore-held key. */
    fun hmac(data: ByteArray): ByteArray
}
