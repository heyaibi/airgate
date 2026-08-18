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

package com.airgate.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airgate.data.crypto.PinManager
import com.airgate.data.repository.PinLockoutPolicy
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.ui.components.AuthPinSubmitDecision
import com.airgate.ui.components.ErrorText
import com.airgate.ui.components.HeroIconBadge
import com.airgate.ui.components.InfoIconRow
import com.airgate.ui.components.PrimaryActionButton
import com.airgate.ui.components.decideAuthPinSubmit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AuthPinScreen(
    repository: SecurityStateRepository,
    onAuthenticated: () -> Unit
) {
    val isSetupMode = !repository.isPinSet()
    val activity = androidx.activity.compose.LocalActivity.current
    // Re-provision mode: the owner is setting a fresh PIN because the stored
    // material is missing or unreadable. Overrides first-time-setup detection so
    // an owner whose PIN data cannot be read is not permanently locked out.
    var reProvisionMode by remember { mutableStateOf(false) }
    val inSetupMode = isSetupMode || reProvisionMode
    val pinUnreadable = !inSetupMode && !repository.isPinUsable()
    var pinText by remember { mutableStateOf("") }
    var confirmPinText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(repository.getMonotonicNow()) }
    var lockoutUntil by remember { mutableLongStateOf(repository.getPinLockoutUntil()) }
    var isSubmitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val remainingLockedMs = (lockoutUntil - now).coerceAtLeast(0L)
    // The lockout throttles guesses against a readable PIN. It never applies in
    // setup/re-provision (there is no credential to guess yet) and never against
    // unreadable material (a storage fault is not a wrong guess).
    val isLocked = remainingLockedMs > 0 && !inSetupMode && !pinUnreadable

    LaunchedEffect(isLocked) {
        while (lockoutUntil > repository.getMonotonicNow()) {
            delay(1000)
            now = repository.getMonotonicNow()
        }
    }

    val remainingSeconds = (remainingLockedMs / 1000L).coerceAtLeast(1L)

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)
        ) {
            HeroIconBadge(
                icon = Icons.Filled.Lock,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (inSetupMode) "Create Armed PIN" else "Enter Armed PIN",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = if (inSetupMode) {
                        "Set a PIN to protect app entry, alarm disarm, streak clearing, and setting edits."
                    } else {
                        "Unlock Airgate with your Armed PIN."
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = pinText,
                    onValueChange = { input ->
                        pinText = input.filter { it.isDigit() }
                        errorMessage = ""
                    },
                    label = { Text(if (inSetupMode) "New PIN (6+ digits)" else "Enter PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = errorMessage.isNotEmpty() || isLocked || pinUnreadable,
                    readOnly = isLocked || pinUnreadable,
                    modifier = Modifier.fillMaxWidth()
                )

                if (inSetupMode) {
                    OutlinedTextField(
                        value = confirmPinText,
                        onValueChange = { input ->
                            confirmPinText = input.filter { it.isDigit() }
                            errorMessage = ""
                        },
                        label = { Text("Confirm PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        isError = errorMessage.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (errorMessage.isNotEmpty()) {
                    ErrorText(errorMessage)
                }

                if (pinUnreadable) {
                    ErrorText(
                        "PIN data is unreadable. App entry is blocked — this can happen if the protected storage was corrupted. Re-provision the Armed PIN to continue."
                    )
                }

                if (reProvisionMode) {
                    ErrorText(
                        "Your previous Armed PIN could not be read, so a new one is being set."
                    )
                }

                if (isLocked) {
                    Text(
                        text = "Too many failed attempts. PIN locked for $remainingSeconds s.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (inSetupMode) {
                InfoIconRow(
                    text = "Stored as a PBKDF2-HMAC-SHA256 hash (120,000 rounds). Not recoverable — remember it."
                )
            }

            PrimaryActionButton(
                text = when {
                    isLocked -> "Locked ($remainingSeconds s)"
                    inSetupMode -> "Set PIN & Continue"
                    pinUnreadable -> "Re-provision PIN"
                    else -> "Unlock"
                },
                onClick = {
                    if (isLocked || isSubmitting) return@PrimaryActionButton
                    val pinManager = PinManager()
                    if (inSetupMode) {
                        if (pinText.length < 6) {
                            errorMessage = "PIN must be at least 6 digits."
                        } else if (pinText != confirmPinText) {
                            errorMessage = "PINs do not match."
                        } else {
                            // PBKDF2 (120k rounds) takes ~100-300ms; never block the UI thread.
                            val newPin = pinText
                            isSubmitting = true
                            scope.launch {
                                val salt = withContext(Dispatchers.Default) { pinManager.generateSalt() }
                                val hash = withContext(Dispatchers.Default) { pinManager.hashPin(newPin, salt) }
                                // Re-derive the hash from the typed PIN and confirm it matches
                                // before anything is persisted: never commit a credential that
                                // cannot verify the PIN it is meant to represent.
                                val saved = withContext(Dispatchers.Default) {
                                    pinManager.verifyPin(
                                        newPin,
                                        salt,
                                        hash,
                                        PinManager.DEFAULT_ITERATIONS,
                                        PinManager.DEFAULT_ALGORITHM
                                    )
                                } && repository.savePin(
                                    hash,
                                    salt,
                                    PinManager.DEFAULT_ITERATIONS,
                                    PinManager.DEFAULT_ALGORITHM
                                )
                                isSubmitting = false
                                if (saved) {
                                    // A fresh credential supersedes any earlier failures: entering
                                    // with the new PIN must not inherit a stale lockout or counter.
                                    repository.resetPinFailedAttempts()
                                    repository.setPinLockoutUntil(0L)
                                    // Alarm full-screen notifications are a hard requirement
                                    // on 13+; request the runtime permission at PIN setup time.
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                        activity?.requestPermissions(
                                            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                                            100
                                        )
                                    }
                                    onAuthenticated()
                                } else {
                                    errorMessage = "Could not save PIN. Please try again."
                                }
                            }
                        }
                    } else if (pinUnreadable) {
                        reProvisionMode = true
                    } else {
                        if (isSubmitting) return@PrimaryActionButton
                        val pinToVerify = pinText
                        isSubmitting = true
                        scope.launch {
                            // PBKDF2 (120k rounds) takes ~100-300ms; never block the UI thread.
                            val outcome = withContext(Dispatchers.Default) {
                                decideAuthPinSubmit(repository, pinManager::verifyPin, pinToVerify)
                            }
                            isSubmitting = false
                            when (outcome) {
                                is AuthPinSubmitDecision.NoPinConfigured,
                                is AuthPinSubmitDecision.PinUnreadable -> {
                                    // Fail closed: the gate resolved with no readable
                                    // credential (it may have changed since the screen
                                    // rendered). Route the owner into re-provisioning
                                    // rather than leaving them on a dead unlock button —
                                    // and never count these as wrong guesses.
                                    reProvisionMode = true
                                    errorMessage = ""
                                }
                                is AuthPinSubmitDecision.IncorrectPin -> {
                                    val attempts = repository.incrementPinFailedAttempts()
                                    if (attempts >= PinLockoutPolicy.MAX_ATTEMPTS) {
                                        val lockoutMs = PinLockoutPolicy.lockoutMs(attempts)
                                        lockoutUntil = repository.getMonotonicNow() + lockoutMs
                                        repository.setPinLockoutUntil(lockoutUntil)
                                    }
                                    errorMessage = "Incorrect PIN. Attempt ${attempts.coerceAtMost(PinLockoutPolicy.MAX_ATTEMPTS)} of ${PinLockoutPolicy.MAX_ATTEMPTS}."
                                }
                                is AuthPinSubmitDecision.Unlock -> {
                                    repository.resetPinFailedAttempts()
                                    repository.setPinLockoutUntil(0L)
                                    onAuthenticated()
                                }
                            }
                        }
                    }
                },
                enabled = !isLocked && !isSubmitting
            )
        }
    }
}
