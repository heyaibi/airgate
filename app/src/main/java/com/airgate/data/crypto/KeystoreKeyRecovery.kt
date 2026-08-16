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

import java.security.UnrecoverableKeyException
import javax.crypto.SecretKey

/**
 * Fetches-or-recovers a keystore key for an alias: an absent alias is generated,
 * and an alias whose key material cannot be recovered (corrupted / permanently
 * invalidated) is deleted and regenerated so protected persistence keeps
 * working. Values encrypted under the lost key can no longer be verified and
 * fail closed at the store layer, forcing the owner to re-provision.
 *
 * Recovery is triggered only by [UnrecoverableKeyException] — the documented
 * "key cannot be recovered" signal from KeyStore.getEntry. Deleting a healthy
 * key on a transient keystore blip would make every previously-encrypted value
 * unreadable and force a full re-provision, so unrelated failures are allowed
 * to propagate instead of being misread as corruption.
 *
 * Pure and JVM-testable: the keystore operations are injected as lambdas, so
 * every recovery branch runs without the Android Keystore.
 */
internal class KeystoreKeyRecovery(
    private val containsAlias: (String) -> Boolean,
    private val readSecretKey: (String) -> SecretKey,
    private val deleteAlias: (String) -> Unit,
    private val generateKey: (String) -> Unit
) {
    fun ensureKey(alias: String): SecretKey {
        if (!containsAlias(alias)) {
            generateKey(alias)
        }
        return try {
            readSecretKey(alias)
        } catch (e: UnrecoverableKeyException) {
            deleteAlias(alias)
            generateKey(alias)
            readSecretKey(alias)
        }
    }
}
