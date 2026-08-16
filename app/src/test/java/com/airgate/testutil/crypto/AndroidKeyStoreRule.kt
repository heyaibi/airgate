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

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Installs the fake `AndroidKeyStore` JCE provider ([FakeAndroidKeyStore]) before
 * every test that exercises the real [com.airgate.data.crypto.KeystoreManager].
 * Robolectric does not provide the platform AndroidKeyStore on the JVM, so any
 * test that builds a [com.airgate.data.repository.SecurityStateRepository] with
 * the default AndroidKeyStore-backed crypto must declare this rule.
 */
class AndroidKeyStoreRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                FakeAndroidKeyStore.install()
                base.evaluate()
            }
        }
}
