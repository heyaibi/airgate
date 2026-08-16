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

import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File

/**
 * Configuration-cache-compatible source of the debug signature pin.
 *
 * The debug keystore is created on first use by spawning the external `keytool`
 * process. Gradle's configuration cache forbids starting external processes
 * during configuration, so the creation + hashing must run inside a
 * [ValueSource]: a `ValueSource` is exempt from that restriction and its result
 * is tracked as a build configuration input. The keystore is created only once
 * (when missing); every later evaluation is a fast read-only hash.
 */
abstract class DebugSignatureHashValueSource : ValueSource<String, DebugSignatureHashValueSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        val storeFile: Property<File>
    }

    override fun obtain(): String {
        val storeFile = parameters.storeFile.orNull
        return when (val pin = SignaturePinResolver.debugPin(storeFile)) {
            is SignaturePin.Pinned -> pin.sha256Hex
            is SignaturePin.Failed -> throw GradleException(pin.reason)
        }
    }
}
