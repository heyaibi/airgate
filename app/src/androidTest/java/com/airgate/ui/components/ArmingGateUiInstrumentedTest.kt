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

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.AppConfig
import com.airgate.policy.DevicePolicyEnforcer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rendered-behavior tests for the arming gates in the UI: the protection switch
 * (dashboard) and the watchdog / paranoid-preset toggles (settings) must refuse to
 * arm without a usable Armed PIN or without notifications being allowed, and must
 * arm normally when both preconditions hold. The central repository gate (the
 * actual security boundary) is covered separately.
 */
@RunWith(AndroidJUnit4::class)
class ArmingGateUiInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun dashboardSwitch_refusesToArm_whenNotificationsAreNotGranted() {
        var configChanged = false
        var enableBlocked = false

        composeRule.setContent {
            MasterActivationCard(
                config = AppConfig(),
                context = context,
                pinUsable = true,
                notificationsGranted = false,
                onEnableBlocked = { enableBlocked = true },
                onConfigChange = { configChanged = true }
            )
        }

        composeRule.onNodeWithTag("master_activation_switch").performClick()
        composeRule.waitForIdle()

        assertTrue("arming must be refused without notifications", enableBlocked)
        assertFalse("no config change may be committed when arming is refused", configChanged)
    }

    @Test
    fun dashboardSwitch_refusesToArm_whenPinIsNotUsable() {
        var configChanged = false
        var enableBlocked = false

        composeRule.setContent {
            MasterActivationCard(
                config = AppConfig(),
                context = context,
                pinUsable = false,
                notificationsGranted = true,
                onEnableBlocked = { enableBlocked = true },
                onConfigChange = { configChanged = true }
            )
        }

        composeRule.onNodeWithTag("master_activation_switch").performClick()
        composeRule.waitForIdle()

        assertTrue("arming must be refused without a usable PIN", enableBlocked)
        assertFalse("no config change may be committed when arming is refused", configChanged)
    }

    @Test
    fun dashboardSwitch_arms_whenPinIsUsableAndNotificationsAreGranted() {
        var configChanged: AppConfig? = null
        var enableBlocked = false

        composeRule.setContent {
            MasterActivationCard(
                config = AppConfig(),
                context = context,
                pinUsable = true,
                notificationsGranted = true,
                onEnableBlocked = { enableBlocked = true },
                onConfigChange = { configChanged = it }
            )
        }

        composeRule.onNodeWithTag("master_activation_switch").performClick()
        composeRule.waitForIdle()

        assertTrue("arming must proceed when both preconditions hold", configChanged?.isEnabled == true)
        assertFalse(enableBlocked)
    }

    @Test
    fun settingsEnableWatchdog_refusesToArm_whenNotificationsAreNotGranted() {
        var configChanged = false
        var notificationsBlocked = false

        composeRule.setContent {
            MasterControlsCard(
                config = AppConfig(),
                onConfigChange = { configChanged = true },
                onConfigFlush = {},
                context = context,
                blockEnforcer = DevicePolicyEnforcer(context, DhizukuManager(context)),
                dhizukuGranted = false,
                blockStatus = "",
                blockIsError = false,
                onBlockStatusChange = { _, _ -> },
                pinUsable = true,
                notificationsGranted = false,
                onEnableBlocked = {},
                onNotificationsBlocked = { notificationsBlocked = true }
            )
        }

        composeRule.onNodeWithTag("enable_watchdog_switch").performClick()
        composeRule.waitForIdle()

        assertTrue("arming must be refused without notifications", notificationsBlocked)
        assertFalse("no config change may be committed when arming is refused", configChanged)
    }

    @Test
    fun settingsParanoidPreset_refusesToArm_whenNotificationsAreNotGranted() {
        var configChanged = false
        var notificationsBlocked = false

        composeRule.setContent {
            MasterControlsCard(
                config = AppConfig(),
                onConfigChange = { configChanged = true },
                onConfigFlush = {},
                context = context,
                blockEnforcer = DevicePolicyEnforcer(context, DhizukuManager(context)),
                dhizukuGranted = false,
                blockStatus = "",
                blockIsError = false,
                onBlockStatusChange = { _, _ -> },
                pinUsable = true,
                notificationsGranted = false,
                onEnableBlocked = {},
                onNotificationsBlocked = { notificationsBlocked = true }
            )
        }

        composeRule.onNodeWithTag("paranoid_mode_switch").performClick()
        composeRule.waitForIdle()

        assertTrue("the preset must be refused without notifications", notificationsBlocked)
        assertFalse("no config change may be committed when the preset is refused", configChanged)
    }

    @Test
    fun settingsEnableWatchdog_arms_whenPinIsUsableAndNotificationsAreGranted() {
        var configChanged: AppConfig? = null
        var notificationsBlocked = false

        composeRule.setContent {
            MasterControlsCard(
                config = AppConfig(),
                onConfigChange = { configChanged = it },
                onConfigFlush = {},
                context = context,
                blockEnforcer = DevicePolicyEnforcer(context, DhizukuManager(context)),
                dhizukuGranted = false,
                blockStatus = "",
                blockIsError = false,
                onBlockStatusChange = { _, _ -> },
                pinUsable = true,
                notificationsGranted = true,
                onEnableBlocked = {},
                onNotificationsBlocked = { notificationsBlocked = true }
            )
        }

        composeRule.onNodeWithTag("enable_watchdog_switch").performClick()
        composeRule.waitForIdle()

        assertTrue("arming must proceed when both preconditions hold", configChanged?.isEnabled == true)
        assertFalse(notificationsBlocked)
    }
}
