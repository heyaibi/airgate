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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.ui.components.BackNavigationTopAppBar
import com.airgate.ui.components.ErrorText
import com.airgate.ui.components.HeroIconBadge
import com.airgate.ui.components.InfoIconRow
import com.airgate.ui.components.PrimaryActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinManagementScreen(
    repository: SecurityStateRepository,
    onBack: () -> Unit
) {
    var newPinText by remember { mutableStateOf("") }
    var confirmPinText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var successMessage by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            BackNavigationTopAppBar(
                title = "Change PIN",
                onBack = onBack,
                titleFontSize = 18.sp
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)
        ) {
            HeroIconBadge(
                icon = Icons.Filled.LockReset,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Change Armed PIN",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Replace the PIN used to protect app entry, alarm disarm, streak clearing, and setting edits.",
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
                    value = newPinText,
                    onValueChange = { input ->
                        newPinText = input.filter { it.isDigit() }
                        errorMessage = ""
                        successMessage = ""
                    },
                    label = { Text("New PIN (6+ digits)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = errorMessage.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = confirmPinText,
                    onValueChange = { input ->
                        confirmPinText = input.filter { it.isDigit() }
                        errorMessage = ""
                        successMessage = ""
                    },
                    label = { Text("Confirm New PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = errorMessage.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage.isNotEmpty()) {
                    ErrorText(errorMessage)
                }
            }

            InfoIconRow(
                text = "Stored as a PBKDF2-HMAC-SHA256 hash (120,000 rounds). Your old PIN stops working immediately."
            )

            if (successMessage.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = successMessage,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            PrimaryActionButton(
                text = "Update PIN",
                onClick = {
                    if (isSubmitting) return@PrimaryActionButton
                    if (newPinText.length < 6) {
                        errorMessage = "PIN must be at least 6 digits long."
                        successMessage = ""
                    } else if (newPinText != confirmPinText) {
                        errorMessage = "PINs do not match."
                        successMessage = ""
                    } else {
                        // PBKDF2 (120k rounds) takes ~100-300ms; never block the UI thread.
                        val newPin = newPinText
                        val pinManager = PinManager()
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
                                newPinText = ""
                                confirmPinText = ""
                                errorMessage = ""
                                successMessage = "PIN updated successfully"
                            } else {
                                errorMessage = "Could not save PIN. Please try again."
                                successMessage = ""
                            }
                        }
                    }
                },
                enabled = !isSubmitting
            )
        }
    }
}
