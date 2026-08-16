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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airgate.data.crypto.PinManager
import com.airgate.data.repository.PinLockoutPolicy
import com.airgate.data.repository.SecurityStateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reusable PIN verification gate for security-sensitive actions (alarm disarm,
 * streak reset, wipe recovery). Enforces a progressive lockout after repeated
 * failures so an attacker cannot brute-force the PIN. The lockout deadline is
 * tracked on the monotonic clock, so rolling the device clock backward cannot
 * clear it.
 */
@Composable
fun PinVerifyDialog(
    repository: SecurityStateRepository,
    title: String,
    description: String,
    confirmLabel: String = "Verify",
    onDismiss: () -> Unit,
    onVerified: () -> Unit
) {
    var pinText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(repository.getMonotonicNow()) }
    var lockoutUntil by remember { mutableLongStateOf(repository.getPinLockoutUntil()) }
    var isVerifying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val remainingLockedMs = (lockoutUntil - now).coerceAtLeast(0L)
    val isLocked = remainingLockedMs > 0

    // Tick while locked so the countdown text updates live.
    LaunchedEffect(isLocked) {
        while (lockoutUntil > repository.getMonotonicNow()) {
            delay(1000)
            now = repository.getMonotonicNow()
        }
    }

    val remainingSeconds = (remainingLockedMs / 1000L).coerceAtLeast(1L)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = description,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isLocked) {
                    Text(
                        text = "Too many failed attempts. PIN locked for $remainingSeconds s.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    OutlinedTextField(
                        value = pinText,
                        onValueChange = { input ->
                            pinText = input.filter { it.isDigit() }
                            errorMessage = ""
                        },
                        label = { Text("Armed PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        isError = errorMessage.isNotEmpty(),
                        supportingText = if (errorMessage.isNotEmpty()) {
                            { Text(errorMessage, color = MaterialTheme.colorScheme.error) }
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isLocked || isVerifying) return@Button
                    // Fail closed: a missing or unreadable PIN must never authorize.
                    val pinToVerify = pinText
                    val pinManager = PinManager()
                    when (val decision = resolvePinGate(repository)) {
                        is PinGateDecision.NoPinConfigured -> {
                            errorMessage = "No Armed PIN configured. Set one in Settings before using this action."
                        }
                        is PinGateDecision.PinUnreadable -> {
                            errorMessage = "PIN data is unreadable. Action blocked."
                        }
                        is PinGateDecision.Verify -> {
                            // PBKDF2 (120k rounds) takes ~100-300ms; verify off the UI thread.
                            isVerifying = true
                            scope.launch {
                                val ok = withContext(Dispatchers.Default) {
                                    pinManager.verifyPin(pinToVerify, decision.salt, decision.expectedHash)
                                }
                                isVerifying = false
                                if (ok) {
                                    repository.resetPinFailedAttempts()
                                    repository.setPinLockoutUntil(0L)
                                    onVerified()
                                } else {
                                    val attempts = repository.incrementPinFailedAttempts()
                                    if (attempts >= PinLockoutPolicy.MAX_ATTEMPTS) {
                                        val lockoutMs = PinLockoutPolicy.lockoutMs(attempts)
                                        lockoutUntil = repository.getMonotonicNow() + lockoutMs
                                        repository.setPinLockoutUntil(lockoutUntil)
                                    }
                                    errorMessage = "Incorrect PIN. Attempt ${attempts.coerceAtMost(PinLockoutPolicy.MAX_ATTEMPTS)} of ${PinLockoutPolicy.MAX_ATTEMPTS}."
                                }
                            }
                        }
                    }
                },
                enabled = !isLocked && !isVerifying,
                modifier = Modifier.height(46.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(confirmLabel, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
