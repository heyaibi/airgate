/*
 * Copyright (C) 2026 The Airgate project contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation: either version 3 of the License, or
 * (at your option) any later version.
 */

package com.airgate.dhizuku

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import com.airgate.domain.model.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DhizukuServerIdentityTest {
    private val validComponent = ComponentName(
        DhizukuServerIdentity.PACKAGE_NAME,
        DhizukuServerIdentity.ADMIN_CLASS_NAME
    )

    @Test
    fun `expected owner requires the trusted package and admin component`() {
        assertTrue(DhizukuServerIdentity.isExpectedOwner(DhizukuServerIdentity.PACKAGE_NAME, validComponent))
        assertFalse(DhizukuServerIdentity.isExpectedOwner(null, validComponent))
        assertFalse(DhizukuServerIdentity.isExpectedOwner("com.example.fake", validComponent))
        assertFalse(DhizukuServerIdentity.isExpectedOwner(DhizukuServerIdentity.PACKAGE_NAME, null))
        assertFalse(
            DhizukuServerIdentity.isExpectedOwner(
                DhizukuServerIdentity.PACKAGE_NAME,
                ComponentName("com.example.fake", DhizukuServerIdentity.ADMIN_CLASS_NAME)
            )
        )
        assertFalse(
            DhizukuServerIdentity.isExpectedOwner(
                DhizukuServerIdentity.PACKAGE_NAME,
                ComponentName(DhizukuServerIdentity.PACKAGE_NAME, "com.rosan.dhizuku.server.OtherReceiver")
            )
        )
    }

    @Test
    fun `expected signer requires exactly one matching certificate`() {
        val expected = "a".repeat(64)
        assertTrue(DhizukuServerIdentity.isExpectedSigner(listOf(expected), expected))
        assertTrue(DhizukuServerIdentity.isExpectedSigner(listOf(expected.uppercase()), expected))
        assertFalse(DhizukuServerIdentity.isExpectedSigner(emptyList(), expected))
        assertFalse(DhizukuServerIdentity.isExpectedSigner(listOf("b".repeat(64)), expected))
        assertFalse(DhizukuServerIdentity.isExpectedSigner(listOf(expected, expected), expected))
        assertFalse(DhizukuServerIdentity.isExpectedSigner(listOf(expected), "not-a-fingerprint"))
    }

    @Test
    fun `availability requires permission and trusted owner`() {
        assertEquals(DhizukuAvailability.UNAVAILABLE, DhizukuServerIdentity.resolveAvailability(false, false))
        assertEquals(DhizukuAvailability.UNAVAILABLE, DhizukuServerIdentity.resolveAvailability(false, true))
        assertEquals(DhizukuAvailability.UNTRUSTED_SERVER, DhizukuServerIdentity.resolveAvailability(true, false))
        assertEquals(DhizukuAvailability.AUTHORIZED, DhizukuServerIdentity.resolveAvailability(true, true))
    }

    @Test
    fun `certificate checker accepts only one matching signer`() {
        val context = object : ContextWrapper(null) {
            override fun getPackageName(): String = "com.airgate"
        }
        val expected = "a".repeat(64)
        val owner = validComponent

        fun checker(digests: List<String>, fingerprint: String = expected) =
            PackageManagerDhizukuServerIdentityChecker(
                context,
                fingerprint,
                DhizukuSignerReader { digests }
            )

        assertTrue(checker(listOf(expected)).isTrusted(DhizukuServerIdentity.PACKAGE_NAME, owner))
        assertFalse(checker(listOf("b".repeat(64))).isTrusted(DhizukuServerIdentity.PACKAGE_NAME, owner))
        assertFalse(checker(emptyList()).isTrusted(DhizukuServerIdentity.PACKAGE_NAME, owner))
        assertFalse(checker(listOf(expected, expected)).isTrusted(DhizukuServerIdentity.PACKAGE_NAME, owner))
        assertFalse(checker(listOf(expected), "malformed").isTrusted(DhizukuServerIdentity.PACKAGE_NAME, owner))
    }

    @Test
    fun `signer reader uses modern and legacy package manager flags`() {
        val bytes = byteArrayOf(1, 2, 3)
        val signature = Signature(bytes)
        val modernPackage = PackageInfo()
        val legacyPackage = PackageInfo().apply { signatures = arrayOf(signature) }
        var modernFlags = 0
        var legacyFlags = 0

        val modernReader = AndroidDhizukuSignerReader(
            DhizukuPackageInfoReader { _, flags -> modernFlags = flags; modernPackage },
            28
        )
        val legacyReader = AndroidDhizukuSignerReader(
            DhizukuPackageInfoReader { _, flags -> legacyFlags = flags; legacyPackage },
            27
        )

        assertEquals(null, modernReader.readSignerDigests())
        assertEquals(listOf(DhizukuServerIdentity.sha256Hex(bytes)), legacyReader.readSignerDigests())
        assertEquals(PackageManager.GET_SIGNING_CERTIFICATES, modernFlags)
        assertEquals(PackageManager.GET_SIGNATURES, legacyFlags)
    }

    @Test
    fun `signer reader fails closed for missing data and lookup errors`() {
        assertEquals(
            null,
            AndroidDhizukuSignerReader(DhizukuPackageInfoReader { _, _ -> PackageInfo() }, 28).readSignerDigests()
        )
        assertEquals(
            null,
            AndroidDhizukuSignerReader(DhizukuPackageInfoReader { _, _ -> throw SecurityException() }, 27).readSignerDigests()
        )
    }

    @Test
    fun `bridge rejects an owner that the identity checker does not trust`() {
        val context = object : ContextWrapper(null) {
            override fun getPackageName(): String = "com.airgate"
        }
        val bridge = DhizukuDpmBridge(
            context = context,
            connection = DhizukuConnection(context, null),
            wrapper = null,
            identityChecker = DhizukuServerIdentityChecker { _, _ -> false }
        )

        assertFalse(bridge.getAdminComponent() != null)
        assertFalse(bridge.wrappedDpm() != null)
    }

    @Test
    fun `owner lookup failure never produces a local admin fallback`() {
        val context = object : ContextWrapper(null) {
            override fun getPackageName(): String = "com.airgate"
        }
        val bridge = DhizukuDpmBridge(
            context = context,
            connection = DhizukuConnection(context, null),
            wrapper = null,
            ownerResolver = object : DhizukuOwnerResolver {
                override fun ownerPackageName(): String = throw IllegalStateException("not initialized")
                override fun ownerComponent(): ComponentName = throw IllegalStateException("not initialized")
            }
        )

        assertTrue(bridge.getAdminComponent() == null)
    }

    @Test
    fun `test backend without an explicit admin cannot write policy`() {
        val context = object : ContextWrapper(null) {
            override fun getPackageName(): String = "com.airgate"
        }
        val wrapper = object : DhizukuBinderWrapper {
            var calls = 0
            override fun isPermissionGranted() = true
            override fun bindUserService(componentName: ComponentName, connection: Any) = true
            override fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean {
                calls++
                return true
            }
            override fun addUserRestriction(admin: ComponentName, key: String): Boolean {
                calls++
                return true
            }
            override fun clearUserRestriction(admin: ComponentName, key: String): Boolean {
                calls++
                return true
            }
            override fun wipeDevice(flags: Int) = true
        }
        val bridge = DhizukuDpmBridge(
            context = context,
            connection = DhizukuConnection(context, wrapper),
            wrapper = wrapper,
            injectedAdminComponent = null
        )
        val writer = DhizukuPolicyWriter(bridge)

        assertFalse(writer.setGlobalSetting("key", "value", AppConfig(), isInvalidated = { false }))
        assertFalse(writer.addUserRestriction("restriction", AppConfig(), isInvalidated = { false }))
        assertFalse(writer.clearUserRestriction("restriction", AppConfig(), isInvalidated = { false }))
        assertTrue(wrapper.calls == 0)
    }
}
