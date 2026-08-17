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
import android.content.Context
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.os.Build
import androidx.annotation.RequiresApi
import java.security.MessageDigest

enum class DhizukuAvailability {
    AUTHORIZED,
    UNAVAILABLE,
    UNTRUSTED_SERVER
}

internal fun interface DhizukuServerIdentityChecker {
    fun isTrusted(ownerPackageName: String?, ownerComponent: ComponentName?): Boolean
}

internal fun interface DhizukuPackageInfoReader {
    fun read(packageName: String, flags: Int): PackageInfo
}

private class AndroidDhizukuPackageInfoReader(
    private val context: Context
) : DhizukuPackageInfoReader {
    override fun read(packageName: String, flags: Int): PackageInfo =
        context.packageManager.getPackageInfo(packageName, flags)
}

internal fun interface DhizukuSignerReader {
    fun readSignerDigests(): List<String>?
}

internal class AndroidDhizukuSignerReader(
    private val packageInfoReader: DhizukuPackageInfoReader,
    private val apiLevel: Int
) : DhizukuSignerReader {
    @SuppressLint("NewApi")
    override fun readSignerDigests(): List<String>? {
        return try {
            if (apiLevel >= Build.VERSION_CODES.P) {
                readModernSignerDigests()
            } else {
                @Suppress("DEPRECATION")
                val signatures = packageInfoReader.read(
                    DhizukuServerIdentity.PACKAGE_NAME,
                    PackageManager.GET_SIGNATURES
                ).signatures ?: return null
                signatures.map { DhizukuServerIdentity.sha256Hex(it.toByteArray()) }
            }
        } catch (_: Exception) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun readModernSignerDigests(): List<String>? {
        val signingInfo = packageInfoReader.read(
            DhizukuServerIdentity.PACKAGE_NAME,
            PackageManager.GET_SIGNING_CERTIFICATES
        ).signingInfo ?: return null
        return signingInfo.apkContentsSigners.map { DhizukuServerIdentity.sha256Hex(it.toByteArray()) }
    }
}

internal class PackageManagerDhizukuServerIdentityChecker(
    private val context: Context,
    private val expectedCertificateSha256: String = EXPECTED_CERTIFICATE_SHA256,
    private val signerReader: DhizukuSignerReader = AndroidDhizukuSignerReader(
        AndroidDhizukuPackageInfoReader(context),
        Build.VERSION.SDK_INT
    )
) : DhizukuServerIdentityChecker {
    override fun isTrusted(ownerPackageName: String?, ownerComponent: ComponentName?): Boolean {
        if (!DhizukuServerIdentity.isExpectedOwner(ownerPackageName, ownerComponent)) return false
        if (!DhizukuServerIdentity.isValidFingerprint(expectedCertificateSha256)) return false
        return DhizukuServerIdentity.isExpectedSigner(
            signerReader.readSignerDigests() ?: return false,
            expectedCertificateSha256
        )
    }
}

internal object DhizukuServerIdentity {
    const val PACKAGE_NAME = "com.rosan.dhizuku"
    const val ADMIN_CLASS_NAME = "com.rosan.dhizuku.server.DhizukuDAReceiver"

    fun isExpectedOwner(ownerPackageName: String?, ownerComponent: ComponentName?): Boolean =
        ownerPackageName == PACKAGE_NAME &&
            ownerComponent?.packageName == PACKAGE_NAME &&
            ownerComponent.className == ADMIN_CLASS_NAME

    fun isExpectedSigner(signers: List<String>, expected: String): Boolean =
        isValidFingerprint(expected) && signers.size == 1 && signers[0].equals(expected, ignoreCase = true)

    fun isValidFingerprint(value: String): Boolean = value.matches(Regex("[0-9a-fA-F]{64}"))

    fun resolveAvailability(permissionGranted: Boolean, ownerTrusted: Boolean): DhizukuAvailability =
        when {
            !permissionGranted -> DhizukuAvailability.UNAVAILABLE
            ownerTrusted -> DhizukuAvailability.AUTHORIZED
            else -> DhizukuAvailability.UNTRUSTED_SERVER
        }

    fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }
}

// Certificate fingerprint of the signed Dhizuku distribution trusted by Airgate.
private const val EXPECTED_CERTIFICATE_SHA256 =
    "8934df3b402d650e0ec7b5d1e3a8cfcc1181b4378e1b8df257ea126d474c685a"
