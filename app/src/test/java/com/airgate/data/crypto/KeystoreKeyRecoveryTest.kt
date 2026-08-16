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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.UnrecoverableKeyException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Every branch of [KeystoreKeyRecovery]: absent-key generation, healthy reads
 * without regeneration, recovery from an unrecoverable key (delete + regenerate
 * + re-read), a regeneration that still fails (propagates), and an unrelated
 * read failure that must NOT be misread as corruption (no delete, no regenerate).
 */
class KeystoreKeyRecoveryTest {

    private class FakeKeyStore {
        private val aliases = mutableMapOf<String, SecretKey>()
        val generated = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        /** When set, [read] throws it instead of returning the stored key. */
        var readError: Throwable? = null

        /** When true, [generate] does not clear the read error, so the
         *  regenerated key is also unreadable. */
        var regenerateStillFails = false

        fun contains(alias: String): Boolean = aliases.containsKey(alias)

        fun read(alias: String): SecretKey {
            readError?.let { throw it }
            return aliases.getValue(alias)
        }

        fun delete(alias: String) {
            deleted += alias
            aliases.remove(alias)
        }

        fun generate(alias: String) {
            generated += alias
            if (!regenerateStillFails) readError = null
            aliases[alias] = SecretKeySpec(alias.toByteArray(Charsets.UTF_8), "AES")
        }

        fun seed(alias: String, key: SecretKey) {
            aliases[alias] = key
        }

        fun recovery(): KeystoreKeyRecovery = KeystoreKeyRecovery(
            containsAlias = { contains(it) },
            readSecretKey = { read(it) },
            deleteAlias = { delete(it) },
            generateKey = { generate(it) }
        )
    }

    private fun fakeKey(alias: String): SecretKey =
        SecretKeySpec(("$alias-keystore").toByteArray(Charsets.UTF_8), "AES")

    @Test
    fun absentKey_isGenerated_andReturned() {
        val fake = FakeKeyStore()
        val recovery = fake.recovery()

        val key = recovery.ensureKey("k")

        assertEquals(listOf("k"), fake.generated)
        assertTrue("an absent key must not be treated as a deletion", fake.deleted.isEmpty())
        assertEquals("the returned key must be the freshly generated one", key, fake.read("k"))
    }

    @Test
    fun presentHealthyKey_isReturned_withoutRegeneration() {
        val fake = FakeKeyStore()
        fake.seed("k", fakeKey("k"))
        val recovery = fake.recovery()

        val key = recovery.ensureKey("k")

        assertTrue("a healthy key must not be regenerated", fake.generated.isEmpty())
        assertTrue("a healthy key must not be deleted", fake.deleted.isEmpty())
        assertEquals("the returned key must be the stored one", key, fake.read("k"))
    }

    @Test
    fun unrecoverableKey_isDeletedAndRegenerated() {
        val fake = FakeKeyStore()
        fake.seed("k", fakeKey("k"))
        fake.readError = UnrecoverableKeyException("key corrupted")
        val recovery = fake.recovery()

        val key = recovery.ensureKey("k")

        assertEquals("the corrupted key must be deleted", listOf("k"), fake.deleted)
        assertEquals("a replacement key must be generated", listOf("k"), fake.generated)
        assertNotEquals("the returned key must be the regenerated one", fakeKey("k"), key)
        assertEquals("the returned key must match the regenerated one", key, fake.read("k"))
    }

    @Test
    fun unrecoverableKey_regenerationStillFailing_propagates() {
        // The recovery is one-shot: if the regenerated key is also unreadable,
        // the failure propagates so the caller fails closed rather than looping.
        val fake = FakeKeyStore()
        fake.seed("k", fakeKey("k"))
        fake.readError = UnrecoverableKeyException("key corrupted")
        fake.regenerateStillFails = true
        val recovery = fake.recovery()

        assertThrows(UnrecoverableKeyException::class.java) {
            recovery.ensureKey("k")
        }

        assertEquals(listOf("k"), fake.deleted)
        assertEquals(listOf("k"), fake.generated)
    }

    @Test
    fun nonRecoverableFailure_propagates_withoutDeletingOrGenerating() {
        // A read failure that is not an unrecoverable-key signal must not be
        // misread as corruption: deleting a healthy key would invalidate every
        // previously-encrypted value and force a full re-provision.
        val fake = FakeKeyStore()
        fake.seed("k", fakeKey("k"))
        fake.readError = IllegalStateException("transient keystore blip")
        val recovery = fake.recovery()

        assertThrows(IllegalStateException::class.java) {
            recovery.ensureKey("k")
        }

        assertTrue("an unrelated failure must not delete the key", fake.deleted.isEmpty())
        assertTrue("an unrelated failure must not regenerate the key", fake.generated.isEmpty())
    }
}
