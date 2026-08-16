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

package com.airgate.ui.alarm

import android.content.Context
import android.content.Intent

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airgate.data.crypto.PinManager
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.SecurityState
import com.airgate.engine.ThreatEngine
import com.airgate.ui.components.PinVerifyDialog
import com.airgate.ui.components.PrimaryActionButton
import com.airgate.ui.theme.AirgateTheme

class AlarmActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private lateinit var repository: SecurityStateRepository
    private lateinit var threatEngine: ThreatEngine
    private var showPinDialog by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full screen lock screen flags
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SECURE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        repository = SecurityStateRepository(applicationContext)
        threatEngine = ThreatEngine(
            applicationContext,
            repository,
            DhizukuManager(applicationContext)
        )

        if (repository.getSecurityState() == SecurityState.WIPING) {
            // Once phone is wiped, immediately dismiss AlarmActivity so SimulatedWipeScreen takes priority
            finish()
            return
        }

        val breachCategory = intent.getStringExtra("breach_category") ?: "SECURITY BREACH"

        val breachDescription = intent.getStringExtra("breach_description") ?: "Unauthorized activity detected"
        val isCountdown = intent.getBooleanExtra("is_countdown", false)

        startAlarmAudioAndVibration()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Block back button; disarm required
            }
        })

        setContent {
            AirgateTheme {
                if (showPinDialog) {
                    PinVerifyDialog(
                        repository = repository,
                        title = "Disarm & Dismiss Alarm",
                        description = "Enter your Armed PIN to cancel the pending wipe and dismiss this alarm.",
                        confirmLabel = "Disarm",
                        onDismiss = { showPinDialog = false },
                        onVerified = {
                            showPinDialog = false
                            // Cancel any scheduled grace-window wipe: the owner disarmed in time.
                            // The accumulated threat score is intentionally preserved; PIN-gating
                            // dismissal must not zero the streak. The owner's PIN also clears the
                            // persistent in-app alarm marker: this is the acknowledgment path.
                            threatEngine.cancelPendingWipe()
                            repository.setSecurityState(SecurityState.ARMED_COMPLIANT)
                            repository.clearPendingAlarm()
                            stopAlarmAudioAndVibration()
                            finish()
                        }
                    )
                }
                AlarmScreen(
                    breachCategory = breachCategory,
                    breachDescription = breachDescription,
                    isCountdown = isCountdown,
                    onDismissAlarm = {
                        showPinDialog = true
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlarmAudioAndVibration()
    }

    private fun startAlarmAudioAndVibration() {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, alarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            // Audio setup fallback
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibrator = vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            val pattern = longArrayOf(0, 500, 200, 500)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } catch (e: Exception) {
            // Vibration fallback
        }
    }

    private fun stopAlarmAudioAndVibration() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {}

        try {
            vibrator?.cancel()
            vibrator = null
        } catch (e: Exception) {}
    }
}

@Composable
fun AlarmScreen(
    breachCategory: String,
    breachDescription: String,
    isCountdown: Boolean,
    onDismissAlarm: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.error

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 28.dp, vertical = 48.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(accent.copy(alpha = 0.12f), CircleShape)
                    .border(1.dp, accent.copy(alpha = 0.28f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.GppMaybe,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            Text(
                text = breachCategory,
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.8.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = if (isCountdown) "Wipe Imminent" else "Security Breach",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = breachDescription,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.weight(1f))

            PrimaryActionButton(
                text = if (isCountdown) "Disarm & Cancel Wipe" else "Disarm & Dismiss Alarm",
                onClick = onDismissAlarm,
                height = 54.dp,
                cornerRadius = 16.dp,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = if (isCountdown)
                    "Wipe imminent · PIN required to cancel"
                else
                    "Alarm active · PIN required",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
