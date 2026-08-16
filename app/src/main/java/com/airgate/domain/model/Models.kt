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

package com.airgate.domain.model

enum class ResponseTier {
    LOG_ONLY,
    ALARM,
    ALARM_STREAK,
    INSTANT_WIPE
}

enum class ScoringGroup(val displayName: String) {
    WIRELESS("Wireless"),
    USB("USB"),
    SYSTEM_TAMPER("System Tamper")
}

enum class ViolationType(
    val scoringGroup: ScoringGroup,
    val defaultTier: ResponseTier,
    val defaultWeight: Int,
    val description: String
) {
    WIFI_TRANSCEIVER_ENABLED(ScoringGroup.WIRELESS, ResponseTier.LOG_ONLY, 1, "Wi-Fi turned on"),
    VALIDATED_NETWORK(ScoringGroup.WIRELESS, ResponseTier.ALARM_STREAK, 1, "Network connection detected"),
    AIRPLANE_MODE_OFF(ScoringGroup.WIRELESS, ResponseTier.ALARM_STREAK, 1, "Airplane mode off"),
    BLUETOOTH_ACTIVITY(ScoringGroup.WIRELESS, ResponseTier.ALARM_STREAK, 1, "Bluetooth activity detected"),

    TETHERING_RNDIS(ScoringGroup.USB, ResponseTier.ALARM_STREAK, 1, "USB tethering on"),
    USB_HOST_LINK(ScoringGroup.USB, ResponseTier.ALARM_STREAK, 1, "USB device connected"),
    USB_FUNCTION_NOT_NONE(ScoringGroup.USB, ResponseTier.ALARM_STREAK, 1, "USB data connection on"),
    ADB_ENABLED_FLIP(ScoringGroup.USB, ResponseTier.ALARM_STREAK, 1, "ADB turned on"),
    OTG_ETHERNET_ATTACHED(ScoringGroup.USB, ResponseTier.ALARM_STREAK, 1, "Ethernet adapter connected"),

    DEVELOPER_OPTIONS_TOGGLE(ScoringGroup.SYSTEM_TAMPER, ResponseTier.ALARM_STREAK, 1, "Developer options on"),
    SYSTEM_CLOCK_CHANGED(ScoringGroup.SYSTEM_TAMPER, ResponseTier.ALARM_STREAK, 1, "System clock changed"),
    SIM_STATE_CHANGED(ScoringGroup.SYSTEM_TAMPER, ResponseTier.ALARM_STREAK, 1, "SIM card changed or removed"),
    DO_RESTRICTION_MISSING(ScoringGroup.SYSTEM_TAMPER, ResponseTier.ALARM_STREAK, 1, "Device Protection Bypassed"),
    MONITOR_REGISTRATION_FAILED(ScoringGroup.SYSTEM_TAMPER, ResponseTier.ALARM_STREAK, 1, "Network monitor registration failed")
}

data class BreachEvent(
    val id: String,
    val timestamp: Long,
    val violationType: ViolationType,
    val tier: ResponseTier,
    val weight: Int,
    val rawMetadata: Map<String, String> = emptyMap()
)

/**
 * A persisted, in-app record that a security alarm was raised and has not yet
 * been acknowledged by the owner. This is the belt-and-suspenders guarantee for
 * alarm visibility: even when the platform blocks every real-time surface (the
 * full-screen notification is denied, or the background activity start is
 * silently suppressed), the alarm still exists in this state and the dashboard
 * must present it until the owner clears it with the Armed PIN. It never gates
 * the wipe — the wipe is scheduled and fires unconditionally.
 */
data class PendingAlarm(
    val category: String,
    val description: String,
    val timestamp: Long,
    val isCountdown: Boolean
)

enum class SecurityState {
    ARMED_COMPLIANT,
    ALARM_ACTIVE,
    COUNTDOWN_WIPE,
    WIPING
}

/**
 * Outcome of executing a wipe. The platform's wipe APIs are void and
 * fire-and-forget — there is no success answer to inspect — so the honest
 * contract is "accepted" (the system has taken over the erase) rather than
 * "verified erased".
 */
enum class WipeResult {
    /** The platform accepted the wipe; the system has taken over the erase. */
    ACCEPTED,
    /** The platform refused the wipe; the device's data was not erased. */
    REJECTED,
    /** Dry-run simulation: no destructive call was made; a simulated success is reported. */
    SIMULATED
}

data class AppConfig(
    // Fresh installations start DISABLED so the owner can review the app (PIN,
    // thresholds, presets) before any detector is armed; an alarm must never fire
    // before the owner has seen what is installed. Enabled is persisted once the
    // owner flips the dashboard switch.
    val isEnabled: Boolean = false,
    val wipeThreshold: Int = 3,
    val notificationsPerBreach: Int = 3,
    val notificationTailMinutes: Int = 720, // 12 hours
    val graceWindowSeconds: Int = 60,
    val safetyNetIntervalMinutes: Int = 15,
    val clockSkewToleranceMinutes: Int = 5,
    val selfTamperTier: ResponseTier = ResponseTier.INSTANT_WIPE,
    val includeFRPData: Boolean = false,
    val aggressiveMode: Boolean = false,
    val deviceProtectionAlarmEnabled: Boolean = false,
    val blockDebuggingFeatures: Boolean = true,
    val dryRunMode: Boolean = true
) {
    companion object {
        // The preset only enforces strict limits. It deliberately leaves
        // dryRunMode and deviceProtectionAlarmEnabled untouched: those are owner
        // choices applied when turning the preset on (see SettingsScreen), so a
        // paranoid preset never silently disables dry-run simulation or flips alarm
        // categories.
        fun aggressivePreset(): AppConfig = AppConfig(
            isEnabled = true,
            wipeThreshold = 1,
            notificationsPerBreach = 5,
            notificationTailMinutes = 720,
            graceWindowSeconds = 0,
            safetyNetIntervalMinutes = 1,
            clockSkewToleranceMinutes = 1,
            selfTamperTier = ResponseTier.INSTANT_WIPE,
            includeFRPData = true,
            aggressiveMode = true,
            blockDebuggingFeatures = true
        )
    }
}


