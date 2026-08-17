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

package com.airgate

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.data.repository.ThemePrefsStore
import com.airgate.domain.model.SecurityState
import com.airgate.service.WatchdogService
import com.airgate.ui.components.QuickActionsBottomBar
import com.airgate.ui.screens.AuthPinScreen
import com.airgate.ui.screens.DashboardScreen
import com.airgate.ui.screens.GuideScreen
import com.airgate.ui.screens.PinManagementScreen
import com.airgate.ui.screens.SettingsScreen
import com.airgate.ui.screens.SimulatedWipeScreen

enum class Screen {
    AUTH_PIN,
    DASHBOARD,
    SETTINGS,
    PIN_MANAGEMENT,
    BREACH_DETAILS,
    VIOLATION_GUIDE
}

class MainActivity : ComponentActivity() {

    private lateinit var repository: SecurityStateRepository
    private lateinit var dhizukuManager: com.airgate.dhizuku.DhizukuManager

    // Hoisted to class scope so the lifecycle observer and the
    // recomposition layer share the same single source of truth.
    private val currentScreen = mutableStateOf(Screen.AUTH_PIN)
    // Appearance preference: off by default, so the app renders with its brand
    // #0055EA accent. Toggled from Settings; the theme recomposes immediately.
    private val useSystemColors = mutableStateOf(false)
    private lateinit var themePrefs: ThemePrefsStore
    // Which Guide tab to open next (0 = Protection Vectors, 1 = Violations).
    // Breach Details jumps to the Violations tab; the nav bar defaults to Vectors.
    private var guideInitialTab by mutableIntStateOf(0)
    private val relockHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoRelockRunnable = Runnable {
        currentScreen.value = Screen.AUTH_PIN
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        enableEdgeToEdge()

        repository = SecurityStateRepository(applicationContext)

        themePrefs = ThemePrefsStore(getSharedPreferences("airgate_ui_prefs", MODE_PRIVATE))
        useSystemColors.value = themePrefs.getUseSystemColors()

        // Init Dhizuku client. Permission + overlay + battery grants are handled
        // in the "Required Access & Permissions" section of the settings page so
        // the owner is not bombarded with dialogs on first launch. Binder init
        // runs off the main thread: a slow or wedged Dhizuku server at launch
        // must never block the UI thread.
        dhizukuManager = com.airgate.dhizuku.DhizukuManager(applicationContext)
        Thread { dhizukuManager.init() }.start()

        // Start Watchdog service
        WatchdogService.startService(applicationContext)

        // Auto-relock when the app is backgrounded. A short grace period lets
        // the owner hop back in without re-entering the PIN; after 30s the app re-locks.
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                relockHandler.postDelayed(autoRelockRunnable, 30_000L)
            }

            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                relockHandler.removeCallbacks(autoRelockRunnable)
            }
        })

        setContent {
            // The security state is collected from the repository's process-wide
            // flow, so a breach that flips the state to WIPING in the background
            // (watchdog service, audit loop, receivers) surfaces the wipe screen
            // immediately — no lifecycle event or re-read required.
            val securityState by repository.securityStateFlow.collectAsStateWithLifecycle()
            com.airgate.ui.theme.AirgateTheme(useSystemColors = useSystemColors.value) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Quick Actions as a persistent bottom navigation bar. The bar stays
                    // visible on every screen; on the lock and wipe screens the tabs are
                    // disabled so the Armed PIN gate and the emergency flow cannot be
                    // bypassed.
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        bottomBar = {
                            if (securityState != SecurityState.WIPING) {
                                QuickActionsBottomBar(
                                    currentScreen = currentScreen.value,
                                    enabled = currentScreen.value != Screen.AUTH_PIN,
                                    onSelect = { screen ->
                                        if (screen == Screen.VIOLATION_GUIDE) guideInitialTab = 0
                                        currentScreen.value = screen
                                    }
                                )
                            }
                        }
                    ) { innerPadding ->
                        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                            // System back gesture handler
                            BackHandler(enabled = currentScreen.value != Screen.DASHBOARD && currentScreen.value != Screen.AUTH_PIN && securityState != SecurityState.WIPING) {
                                when (currentScreen.value) {
                                    Screen.SETTINGS -> currentScreen.value = Screen.DASHBOARD
                                    Screen.PIN_MANAGEMENT -> currentScreen.value = Screen.SETTINGS
                                    Screen.BREACH_DETAILS -> currentScreen.value = Screen.DASHBOARD
                                    Screen.VIOLATION_GUIDE -> currentScreen.value = Screen.DASHBOARD
                                    else -> {}
                                }
                            }

                            WipeGate(
                                securityState = securityState,
                                repository = repository,
                                onResetStreakRequested = {
                                    repository.resetStreak()
                                    currentScreen.value = Screen.DASHBOARD
                                }
                            ) {
                                when (currentScreen.value) {
                                    Screen.AUTH_PIN -> AuthPinScreen(
                                        repository = repository,
                                        onAuthenticated = { currentScreen.value = Screen.DASHBOARD }
                                    )
                                    Screen.DASHBOARD -> DashboardScreen(
                                        repository = repository,
                                        onNavigateToBreaches = { currentScreen.value = Screen.BREACH_DETAILS },
                                        onClearStreakRequested = {
                                            repository.resetStreak()
                                        }
                                    )
                                    Screen.SETTINGS -> SettingsScreen(
                                        repository = repository,
                                        onNavigateToPinManagement = { currentScreen.value = Screen.PIN_MANAGEMENT },
                                        onBack = { currentScreen.value = Screen.DASHBOARD },
                                        useSystemColors = useSystemColors.value,
                                        onUseSystemColorsChange = { enabled ->
                                            themePrefs.setUseSystemColors(enabled)
                                            useSystemColors.value = enabled
                                        }
                                    )
                                    Screen.PIN_MANAGEMENT -> PinManagementScreen(
                                        repository = repository,
                                        onBack = { currentScreen.value = Screen.SETTINGS }
                                    )
                                    Screen.BREACH_DETAILS -> com.airgate.ui.screens.BreachDetailsScreen(
                                        repository = repository,
                                        onBack = { currentScreen.value = Screen.DASHBOARD },
                                        onNavigateToViolationGuide = {
                                            guideInitialTab = 1
                                            currentScreen.value = Screen.VIOLATION_GUIDE
                                        }
                                    )
                                    Screen.VIOLATION_GUIDE -> GuideScreen(
                                        repository = repository,
                                        onBack = { currentScreen.value = Screen.DASHBOARD },
                                        initialTab = guideInitialTab
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders the emergency wipe screen whenever the security state is WIPING,
 * replacing the normal navigation content. The state is collected from the
 * repository's process-wide flow by the caller, so a background breach that
 * flips the state to WIPING surfaces this screen immediately.
 */
@Composable
fun WipeGate(
    securityState: SecurityState,
    repository: SecurityStateRepository,
    onResetStreakRequested: () -> Unit,
    content: @Composable () -> Unit
) {
    if (securityState == SecurityState.WIPING) {
        SimulatedWipeScreen(
            repository = repository,
            onResetStreakRequested = onResetStreakRequested
        )
    } else {
        content()
    }
}
