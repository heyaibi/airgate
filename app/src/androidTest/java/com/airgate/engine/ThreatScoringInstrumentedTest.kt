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

package com.airgate.engine

import android.content.ComponentName
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.dhizuku.DhizukuBinderWrapper
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.BreachEvent
import com.airgate.domain.model.ResponseTier
import com.airgate.domain.model.ScoringGroup
import com.airgate.domain.model.SecurityState
import com.airgate.domain.model.ViolationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * On-device verification of the threat-scoring contract: the scoring-group daily
 * point may only be consumed by escalation-tier (ALARM_STREAK) events, so a
 * benign record-only event can never starve the wipe trigger.
 *
 * All tests use a throwaway SharedPreferences store so no real app state is
 * touched, a recording Dhizuku stub so no device-owner authority is needed, and
 * a silent alarm notifier so no real notification is raised.
 */
@RunWith(AndroidJUnit4::class)
class ThreatScoringInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val dayMs = 86_400_000L

    private fun throwawayRepository(): SecurityStateRepository {
        val prefs = context.getSharedPreferences(
            "scoring_instrumented_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        val repository = SecurityStateRepository(prefs)
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        return repository
    }

    private fun recordingManager(): DhizukuManager =
        DhizukuManager(context, RecordingBinder())

    private fun silentEngine(repository: SecurityStateRepository): ThreatEngine =
        ThreatEngine(
            context,
            repository,
            recordingManager(),
            customWindowMs = dayMs,
            alarmNotifier = SilentAlarmNotifier(context)
        )

    private fun breach(violationType: ViolationType, tier: ResponseTier): BreachEvent =
        BreachEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            violationType = violationType,
            tier = tier,
            weight = 1
        )

    @Test
    fun logOnlyBreach_doesNotConsumeTheGroupPointOnDevice() {
        val repository = throwawayRepository()
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, wipeThreshold = 5))
        val engine = silentEngine(repository)

        // The benign first event of the day (Wi-Fi turned on) is record-only.
        engine.processBreach(breach(ViolationType.WIFI_TRANSCEIVER_ENABLED, ResponseTier.LOG_ONLY))

        // Its audit record is preserved...
        assertEquals(1, repository.getVtCount(ViolationType.WIFI_TRANSCEIVER_ENABLED))
        // ...but it never scores or alarms.
        assertEquals(0, repository.getStreak())
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())

        // The real threat later the same day still earns the group's point.
        engine.processBreach(breach(ViolationType.VALIDATED_NETWORK, ResponseTier.ALARM_STREAK))

        assertEquals(1, repository.getStreak())
        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
        assertTrue(repository.isScoringGroupClaimedToday(ScoringGroup.WIRELESS, dayMs))
    }

    @Test
    fun alarmStreakBreach_claimsThePointOncePerDayOnDevice() {
        val repository = throwawayRepository()
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, wipeThreshold = 5))
        val engine = silentEngine(repository)

        engine.processBreach(breach(ViolationType.VALIDATED_NETWORK, ResponseTier.ALARM_STREAK))
        engine.processBreach(breach(ViolationType.BLUETOOTH_ACTIVITY, ResponseTier.ALARM_STREAK))

        // The two real threats share the group's single daily point.
        assertEquals(1, repository.getStreak())
        assertTrue(repository.isScoringGroupClaimedToday(ScoringGroup.WIRELESS, dayMs))
    }

    @Test
    fun logOnlyBreach_cannotBlockAnAlarmStreakWipeOnDevice() {
        val repository = throwawayRepository()
        repository.saveConfig(
            AppConfig(isEnabled = true, dryRunMode = true, wipeThreshold = 1, graceWindowSeconds = 0)
        )
        val engine = silentEngine(repository)

        // A benign Wi-Fi-on event alone must not trip the aggressive wipe.
        engine.processBreach(breach(ViolationType.WIFI_TRANSCEIVER_ENABLED, ResponseTier.LOG_ONLY))
        assertEquals(0, repository.getStreak())
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())

        // And it must not have spent the point: the first real threat wipes.
        engine.processBreach(breach(ViolationType.VALIDATED_NETWORK, ResponseTier.ALARM_STREAK))
        assertEquals(1, repository.getStreak())
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun claimScoringGroupPoint_roundTripsThroughRealPrefs() {
        val repository = throwawayRepository()
        repository.setStreak(1)

        // First claim of the window earns the point and is visible as claimed.
        assertTrue(repository.claimScoringGroupPoint(ViolationType.VALIDATED_NETWORK, dayMs))
        assertTrue(repository.isScoringGroupClaimedToday(ScoringGroup.WIRELESS, dayMs))

        // A second claim within the same window is debounced.
        assertFalse(repository.claimScoringGroupPoint(ViolationType.VALIDATED_NETWORK, dayMs))
    }

    @Test
    fun instantWipeBreach_doesNotClaimTheGroupPointOnDevice() {
        val repository = throwawayRepository()
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, wipeThreshold = 5, graceWindowSeconds = 0))
        val engine = silentEngine(repository)

        engine.processBreach(breach(ViolationType.AIRPLANE_MODE_OFF, ResponseTier.INSTANT_WIPE))

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertEquals(0, repository.getStreak())
        // The point is still available for escalation: a fresh claim on the same
        // group succeeds only because the INSTANT_WIPE event did not spend it.
        assertTrue(repository.claimScoringGroupPoint(ViolationType.VALIDATED_NETWORK, dayMs))
    }

    private class RecordingBinder : DhizukuBinderWrapper {
        override fun isPermissionGranted(): Boolean = true
        override fun bindUserService(componentName: ComponentName, connection: Any): Boolean = true
        override fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean = true
        override fun addUserRestriction(admin: ComponentName, key: String): Boolean = true
        override fun clearUserRestriction(admin: ComponentName, key: String): Boolean = true
        override fun wipeDevice(flags: Int): Boolean = true
    }

    private class SilentAlarmNotifier(context: Context) : AlarmNotifier(context) {
        override fun launch(event: BreachEvent) = Unit
        override fun launchCountdown() = Unit
        override fun launchWipeFailure() = Unit
    }
}
