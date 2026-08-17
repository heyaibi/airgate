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

package com.airgate.ui.components

import com.airgate.data.crypto.JvmPrefsCrypto
import com.airgate.data.crypto.PinManager
import com.airgate.data.repository.SecurityStateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinGateTest {

    private class InMemorySharedPreferences : android.content.SharedPreferences {
        private val map = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? {
            // Mirror real SharedPreferencesImpl: reading a non-string value as a
            // string throws ClassCastException rather than returning the default.
            val v = map[key] ?: return defValue
            return v as? String ?: throw ClassCastException("$v cannot be cast to String")
        }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = Editor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class Editor(private val map: MutableMap<String, Any?>) : android.content.SharedPreferences.Editor {
            private val tempMap = mutableMapOf<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { tempMap[key!!] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor { tempMap[key!!] = values; return this }
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { tempMap[key!!] = value; return this }
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { tempMap[key!!] = value; return this }
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { tempMap[key!!] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { tempMap[key!!] = value; return this }
            override fun remove(key: String?): android.content.SharedPreferences.Editor { tempMap[key!!] = null; return this }
            override fun clear(): android.content.SharedPreferences.Editor { clearFlag = true; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearFlag) map.clear()
                tempMap.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            }
        }
    }

    private val prefs = InMemorySharedPreferences()
    private val repository = SecurityStateRepository(prefs, JvmPrefsCrypto())

    @Test
    fun `no configured pin resolves to NoPinConfigured`() {
        assertEquals(PinGateDecision.NoPinConfigured, resolvePinGate(repository))
    }

    @Test
    fun `configured pin with readable material resolves to Verify`() {
        val hash = byteArrayOf(1, 2, 3, 4, 5)
        val salt = byteArrayOf(6, 7, 8, 9)
        repository.savePin(hash, salt, PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)

        val decision = resolvePinGate(repository)

        assertTrue(decision is PinGateDecision.Verify)
        decision as PinGateDecision.Verify
        assertEquals(hash.toList(), decision.expectedHash.toList())
        assertEquals(salt.toList(), decision.salt.toList())
    }

    @Test
    fun `configured pin with unreadable material resolves to PinUnreadable`() {
        // The PIN keys exist (so the PIN is "configured") but the protected blobs
        // cannot be decoded/decrypted — the tamper/corruption case. The gate must
        // fail closed, never treat this as authorization.
        prefs.edit()
            .putString("pin_hash", "enc:broken")
            .putString("pin_salt", "enc:broken")
            .apply()

        assertEquals(PinGateDecision.PinUnreadable, resolvePinGate(repository))
    }

    @Test
    fun `unreadable pin material never resolves to Verify`() {
        prefs.edit()
            .putString("pin_hash", "enc:broken")
            .putString("pin_salt", "enc:broken")
            .apply()

        assertTrue(resolvePinGate(repository) !is PinGateDecision.Verify)
    }

    @Test
    fun `verify decision carries the material used to accept a correct pin`() {
        val pinManager = com.airgate.data.crypto.PinManager()
        val salt = byteArrayOf(6, 7, 8, 9)
        val hash = pinManager.hashPin("123456", salt)
        repository.savePin(hash, salt, PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)

        val decision = resolvePinGate(repository) as PinGateDecision.Verify

        assertTrue(pinManager.verifyPin("123456", decision.salt, decision.expectedHash, decision.iterations, decision.algorithm))
    }

    @Test
    fun `verify decision rejects an incorrect pin`() {
        val pinManager = com.airgate.data.crypto.PinManager()
        val salt = byteArrayOf(6, 7, 8, 9)
        val hash = pinManager.hashPin("123456", salt)
        repository.savePin(hash, salt, PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)

        val decision = resolvePinGate(repository) as PinGateDecision.Verify

        assertFalse(pinManager.verifyPin("000000", decision.salt, decision.expectedHash, decision.iterations, decision.algorithm))
    }
}
