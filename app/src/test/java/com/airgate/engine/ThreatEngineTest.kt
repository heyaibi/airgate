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

import com.airgate.data.crypto.JvmPrefsCrypto
import com.airgate.data.crypto.PinManager
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.dhizuku.DhizukuBinderWrapper
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.BreachEvent
import com.airgate.domain.model.ResponseTier
import com.airgate.domain.model.SecurityState
import com.airgate.domain.model.ViolationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID

class ThreatEngineTest {

    private class MockSharedPreferences : android.content.SharedPreferences {
        private val map = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = Editor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class Editor(private val map: MutableMap<String, Any?>) : android.content.SharedPreferences.Editor {
            private val tempMap = mutableMapOf<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { tempMap[key!!] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor { tempMap[key!!] = values; return this }
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { tempMap[key!!] = value; return this }
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { tempMap[key!!] = value; return this }
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { tempMap[key!!] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { tempMap[key!!] = value; return this }
            override fun remove(key: String?): android.content.SharedPreferences.Editor { tempMap[key!!] = null; return this }
            override fun clear(): android.content.SharedPreferences.Editor { clearFlag = true; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearFlag) map.clear()
                tempMap.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            }
        }
    }

    private class MockDhizukuBinder : DhizukuBinderWrapper {
        val globalSettings = mutableMapOf<String, String>()
        val userRestrictions = mutableSetOf<String>()
        var wipeCalled = false
        var wipeFlags = 0
        var wipeAccepted = true

        override fun isPermissionGranted(): Boolean = true
        override fun bindUserService(componentName: android.content.ComponentName, connection: Any): Boolean = true
        override fun setGlobalSetting(admin: android.content.ComponentName, key: String, value: String): Boolean {
            globalSettings[key] = value
            return true
        }
        override fun addUserRestriction(admin: android.content.ComponentName, key: String): Boolean {
            userRestrictions.add(key)
            return true
        }
        override fun clearUserRestriction(admin: android.content.ComponentName, key: String): Boolean {
            userRestrictions.remove(key)
            return true
        }
        override fun wipeDevice(flags: Int): Boolean {
            wipeCalled = true
            wipeFlags = flags
            return wipeAccepted
        }
    }

    private class DummyContext : android.content.ContextWrapper(null) {
        override fun getPackageName(): String = "com.airgate"
        override fun getSystemService(name: String): Any? = null
    }

    private class RecordingAlarmNotifier(context: android.content.Context) : AlarmNotifier(context) {
        var wipeFailures = 0
        var countdownLaunches = 0
        var alarmLaunches = 0

        override fun launch(event: BreachEvent) {
            alarmLaunches++
        }

        override fun launchCountdown() {
            countdownLaunches++
        }

        override fun launchWipeFailure() {
            wipeFailures++
        }
    }

    private class RecordingGraceWipeScheduler : GraceWipeScheduler(DummyContext(), { 0L }) {
        val scheduleDelays = mutableListOf<Long>()
        var scheduleCalls = 0
        var cancelCalls = 0
        var exactAlarmCapability = true
        var scheduleResult = GraceWipeScheduler.WipeScheduleResult.EXACT_SCHEDULED
        var scheduleDelayResult = GraceWipeScheduler.WipeScheduleResult.EXACT_SCHEDULED

        override fun canScheduleExactAlarms(): Boolean = exactAlarmCapability

        override fun schedule(config: AppConfig): GraceWipeScheduler.WipeScheduleResult {
            scheduleCalls++
            return scheduleResult
        }

        override fun scheduleDelay(delayMs: Long): GraceWipeScheduler.WipeScheduleResult {
            scheduleDelays.add(delayMs)
            return scheduleDelayResult
        }

        override fun cancel() {
            cancelCalls++
        }
    }

    private lateinit var context: DummyContext
    private lateinit var repository: SecurityStateRepository
    private lateinit var dhizukuManager: DhizukuManager
    private lateinit var threatEngine: ThreatEngine
    private lateinit var notifier: RecordingAlarmNotifier

    // Controllable monotonic clock: tests advance it to drive deadline expiry.
    private var fakeElapsed = 0L

    @Before
    fun setUp() {
        val prefs = MockSharedPreferences()
        repository = SecurityStateRepository(prefs, JvmPrefsCrypto()) { fakeElapsed }
        // The watchdog may only be armed after a PIN is configured.
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 0))
        repository.resetStreak()

        repository.setSecurityState(SecurityState.ARMED_COMPLIANT)
        
        context = DummyContext()
        val mockBinder = MockDhizukuBinder()
        dhizukuManager = DhizukuManager(context, mockBinder)
        notifier = RecordingAlarmNotifier(context)
        // The precise countdown is only ever armed while exact-alarm capability is
        // present; the shared engine uses a recording scheduler that reports it so
        // the countdown paths are exercised (the DummyContext has no AlarmManager).
        threatEngine = ThreatEngine(
            context, repository, dhizukuManager,
            customWindowMs = 0L, alarmNotifier = notifier,
            graceWipeScheduler = RecordingGraceWipeScheduler()
        )
    }

    @Test
    fun `processBreach ALARM_STREAK increments streak correctly`() {
        val event = BreachEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            violationType = ViolationType.WIFI_TRANSCEIVER_ENABLED,
            tier = ResponseTier.ALARM_STREAK,
            weight = 1
        )

        threatEngine.processBreach(event)

        assertEquals(1, repository.getStreak())
        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
    }

    @Test
    fun `processing any breach never decreases the accumulated streak`() {
        // There is no automatic streak reset: enforcement may only ever increase
        // or hold the streak, never zero it. Only the PIN-gated owner actions (and
        // the dry-run test harness) reset the streak, so every enforcement path —
        // every violation type and every response tier — must leave the streak
        // untouched or higher than it was.
        var previous = repository.getStreak()

        fun assertNonDecreasing(step: String) {
            val current = repository.getStreak()
            assertTrue(
                "$step must not reduce the streak (was $previous, now $current)",
                current >= previous
            )
            previous = current
        }

        // Every violation type at its default tier (LOG_ONLY and ALARM_STREAK
        // paths, including the suppressed device-protection and debugging tiers).
        for (violationType in ViolationType.values()) {
            threatEngine.processBreach(
                BreachEvent(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    violationType = violationType,
                    tier = violationType.defaultTier,
                    weight = violationType.defaultWeight
                )
            )
            assertNonDecreasing("processing a ${violationType.name} breach")
        }

        // The remaining tiers an event can be routed to: ALARM (harden + alarm,
        // no scoring) and INSTANT_WIPE (bypasses the streak and wipes).
        threatEngine.processBreach(
            BreachEvent(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                violationType = ViolationType.WIFI_TRANSCEIVER_ENABLED,
                tier = ResponseTier.ALARM,
                weight = 1
            )
        )
        assertNonDecreasing("an ALARM-tier breach")

        threatEngine.processBreach(
            BreachEvent(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                violationType = ViolationType.BLUETOOTH_ACTIVITY,
                tier = ResponseTier.INSTANT_WIPE,
                weight = 1
            )
        )
        assertNonDecreasing("an INSTANT_WIPE-tier breach")

        assertTrue(
            "processing every violation type must have accumulated a non-zero streak",
            repository.getStreak() > 0
        )
    }

    @Test
    fun `reaching the wipe threshold does not reset the streak`() {
        // The wipe fires without zeroing the score: only an owner action (the
        // PIN-gated clear, or the reset after the wipe screen) zeroes it. Crossing
        // the threshold must leave the accumulated streak intact so the escalation
        // history survives the wipe.
        val defaultWindowEngine = ThreatEngine(context, repository, dhizukuManager, alarmNotifier = notifier)
        val threeGroups = listOf(
            ViolationType.BLUETOOTH_ACTIVITY,
            ViolationType.USB_HOST_LINK,
            ViolationType.SYSTEM_CLOCK_CHANGED
        )
        threeGroups.forEach { violationType ->
            defaultWindowEngine.processBreach(
                BreachEvent(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    violationType = violationType,
                    tier = violationType.defaultTier,
                    weight = violationType.defaultWeight
                )
            )
        }

        assertEquals(3, repository.getStreak())
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun `airplane mode off breach enforces and scores as a wireless streak`() {
        val event = BreachEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            violationType = ViolationType.AIRPLANE_MODE_OFF,
            tier = ViolationType.AIRPLANE_MODE_OFF.defaultTier,
            weight = ViolationType.AIRPLANE_MODE_OFF.defaultWeight
        )

        threatEngine.processBreach(event)

        assertEquals(1, repository.getStreak())
        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
    }

    @Test
    fun `bluetooth activity breach enforces and scores as a wireless streak`() {
        val event = BreachEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            violationType = ViolationType.BLUETOOTH_ACTIVITY,
            tier = ViolationType.BLUETOOTH_ACTIVITY.defaultTier,
            weight = ViolationType.BLUETOOTH_ACTIVITY.defaultWeight
        )

        threatEngine.processBreach(event)

        assertEquals(1, repository.getStreak())
        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
    }

    @Test
    fun `processBreach debounces rapid consecutive breaches within same scoring group on same day`() {
        val engineWithDayWindow = ThreatEngine(context, repository, dhizukuManager, customWindowMs = 86400000L)
        val event1 = BreachEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            violationType = ViolationType.WIFI_TRANSCEIVER_ENABLED,
            tier = ResponseTier.ALARM_STREAK,
            weight = 1
        )
        val event2 = BreachEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            violationType = ViolationType.BLUETOOTH_ACTIVITY,
            tier = ResponseTier.ALARM_STREAK,
            weight = 1
        )

        engineWithDayWindow.processBreach(event1)
        engineWithDayWindow.processBreach(event2)

        // Multiple VTs in the same ScoringGroup (WIRELESS) on the same day consume only 1 streak point
        assertEquals(1, repository.getStreak())
    }

    @Test
    fun `USB data transfer is enforced even when blockDebuggingFeatures is off`() {
        // Turning OFF the debugging block authorizes ADB/developer-options flips, but
        // USB data transfer is a separate feature and must still alarm + consume a point.
        repository.saveConfig(
            AppConfig(isEnabled = true, dryRunMode = true, blockDebuggingFeatures = false)
        )
        val usbFunction = BreachEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            violationType = ViolationType.USB_FUNCTION_NOT_NONE,
            tier = ViolationType.USB_FUNCTION_NOT_NONE.defaultTier,
            weight = 1,
            rawMetadata = mapOf("mtp" to "true")
        )
        threatEngine.processBreach(usbFunction)

        assertEquals(1, repository.getStreak())
        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
    }

    @Test
    fun `USB host link is enforced even when blockDebuggingFeatures is off`() {
        repository.saveConfig(
            AppConfig(isEnabled = true, dryRunMode = true, blockDebuggingFeatures = false)
        )
        val usbHost = BreachEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            violationType = ViolationType.USB_HOST_LINK,
            tier = ViolationType.USB_HOST_LINK.defaultTier,
            weight = 1,
            rawMetadata = mapOf("devices" to "venderid:1234")
        )
        threatEngine.processBreach(usbHost)

        assertEquals(1, repository.getStreak())
        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
    }

    @Test
    fun `ADB and dev-options flips stay suppressed when blockDebuggingFeatures is off`() {
        repository.saveConfig(
            AppConfig(isEnabled = true, dryRunMode = true, blockDebuggingFeatures = false)
        )
        threatEngine.processBreach(
            BreachEvent(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                violationType = ViolationType.ADB_ENABLED_FLIP,
                tier = ViolationType.ADB_ENABLED_FLIP.defaultTier,
                weight = 1
            )
        )
        threatEngine.processBreach(
            BreachEvent(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                violationType = ViolationType.DEVELOPER_OPTIONS_TOGGLE,
                tier = ViolationType.DEVELOPER_OPTIONS_TOGGLE.defaultTier,
                weight = 1
            )
        )

        // Authorized during recovery/install: recorded but no point, no alarm state.
        assertEquals(0, repository.getStreak())
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
    }

    @Test
    fun `executeWipeState enters WIPING only when the live wipe is accepted`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = false, graceWindowSeconds = 0))
        val mockBinder = MockDhizukuBinder()
        val manager = DhizukuManager(context, mockBinder)
        val engine = ThreatEngine(context, repository, manager, customWindowMs = 0L, alarmNotifier = notifier)

        engine.executeWipeState()

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertTrue(mockBinder.wipeCalled)
        // An accepted wipe must never raise the wipe-failure alarm.
        assertEquals(0, notifier.wipeFailures)
    }

    @Test
    fun `executeWipeState reverts to ALARM_ACTIVE and raises the alarm when the live wipe is refused`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = false, graceWindowSeconds = 0))
        val mockBinder = MockDhizukuBinder().apply { wipeAccepted = false }
        val manager = DhizukuManager(context, mockBinder)
        val engine = ThreatEngine(context, repository, manager, customWindowMs = 0L, alarmNotifier = notifier)

        engine.executeWipeState()

        // A refused wipe must never be shown as WIPING: the data is still present,
        // and the failure must be surfaced loudly to the owner.
        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
        assertTrue(mockBinder.wipeCalled)
        assertEquals(1, notifier.wipeFailures)
    }

    @Test
    fun `executeWipeState shows the simulation screen in dry-run without calling the wipe`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 0))
        val mockBinder = MockDhizukuBinder()
        val manager = DhizukuManager(context, mockBinder)
        val engine = ThreatEngine(context, repository, manager, customWindowMs = 0L, alarmNotifier = notifier)

        engine.executeWipeState()

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertFalse(mockBinder.wipeCalled)
        // A simulated wipe is not a failure; it must not raise the wipe-failure alarm.
        assertEquals(0, notifier.wipeFailures)
    }

    @Test
    fun `executeWipeState with a grace window schedules a countdown instead of wiping`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 60))
        val mockBinder = MockDhizukuBinder()
        val manager = DhizukuManager(context, mockBinder)
        val engine = ThreatEngine(
            context, repository, manager,
            customWindowMs = 0L, alarmNotifier = notifier,
            graceWipeScheduler = RecordingGraceWipeScheduler()
        )

        engine.executeWipeState()

        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
        assertFalse(mockBinder.wipeCalled)
        assertEquals(1, notifier.countdownLaunches)
        assertEquals(0, notifier.wipeFailures)
    }

    @Test
    fun `INSTANT_WIPE breach with an accepted wipe ends in WIPING`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = false, graceWindowSeconds = 0))
        val mockBinder = MockDhizukuBinder()
        val manager = DhizukuManager(context, mockBinder)
        val engine = ThreatEngine(context, repository, manager, customWindowMs = 0L, alarmNotifier = notifier)
        val event = BreachEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            violationType = ViolationType.AIRPLANE_MODE_OFF,
            tier = ResponseTier.INSTANT_WIPE,
            weight = 1
        )

        engine.processBreach(event)

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertTrue(mockBinder.wipeCalled)
        assertEquals(0, notifier.wipeFailures)
        // The wipe bypassed the streak, and its group point stays available: a
        // fresh claim on the same group must succeed. (isScoringGroupClaimedToday
        // cannot prove this here — it reads false whenever the streak is zero.)
        assertTrue(repository.claimScoringGroupPoint(ViolationType.VALIDATED_NETWORK, 86_400_000L))
    }

    @Test
    fun `INSTANT_WIPE breach with a refused wipe reverts to ALARM_ACTIVE and raises the alarm`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = false, graceWindowSeconds = 0))
        val mockBinder = MockDhizukuBinder().apply { wipeAccepted = false }
        val manager = DhizukuManager(context, mockBinder)
        val engine = ThreatEngine(context, repository, manager, customWindowMs = 0L, alarmNotifier = notifier)
        val event = BreachEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            violationType = ViolationType.AIRPLANE_MODE_OFF,
            tier = ResponseTier.INSTANT_WIPE,
            weight = 1
        )

        engine.processBreach(event)

        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
        assertTrue(mockBinder.wipeCalled)
        assertEquals(1, notifier.wipeFailures)
        // A refused wipe still never consumes the group's daily point: a fresh
        // claim on the same group must succeed.
        assertTrue(repository.claimScoringGroupPoint(ViolationType.VALIDATED_NETWORK, 86_400_000L))
    }

    @Test
    fun `ALARM_STREAK breach reaching the wipe threshold shows the dry-run simulation`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, wipeThreshold = 1, graceWindowSeconds = 0))
        val mockBinder = MockDhizukuBinder()
        val manager = DhizukuManager(context, mockBinder)
        val engine = ThreatEngine(context, repository, manager, customWindowMs = 0L, alarmNotifier = notifier)
        val event = BreachEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            violationType = ViolationType.AIRPLANE_MODE_OFF,
            tier = ResponseTier.ALARM_STREAK,
            weight = 1
        )

        engine.processBreach(event)

        assertEquals(1, repository.getStreak())
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertFalse(mockBinder.wipeCalled)
        assertEquals(0, notifier.wipeFailures)
    }

    private fun dayWindowEngine() =
        ThreatEngine(context, repository, dhizukuManager, customWindowMs = 86_400_000L, alarmNotifier = notifier)

    private fun breach(violationType: ViolationType, tier: ResponseTier): BreachEvent =
        BreachEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            violationType = violationType,
            tier = tier,
            weight = 1
        )

    @Test
    fun `LOG_ONLY event does not consume the group point so a same-day ALARM_STREAK still scores`() {
        val engine = dayWindowEngine()

        // The benign first event of the day (Wi-Fi turned on) is record-only.
        engine.processBreach(breach(ViolationType.WIFI_TRANSCEIVER_ENABLED, ResponseTier.LOG_ONLY))

        assertEquals(1, repository.getVtCount(ViolationType.WIFI_TRANSCEIVER_ENABLED))
        assertEquals(0, repository.getStreak())
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())

        // The real threat later the same day still earns the group's point.
        engine.processBreach(breach(ViolationType.VALIDATED_NETWORK, ResponseTier.ALARM_STREAK))

        assertEquals(1, repository.getStreak())
        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
    }

    @Test
    fun `LOG_ONLY event alone never advances the streak or claims the group`() {
        val engine = dayWindowEngine()

        engine.processBreach(breach(ViolationType.WIFI_TRANSCEIVER_ENABLED, ResponseTier.LOG_ONLY))

        assertEquals(0, repository.getStreak())
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
        // The point stays claimable: a fresh claim on the same group succeeds.
        assertTrue(repository.claimScoringGroupPoint(ViolationType.VALIDATED_NETWORK, 86_400_000L))
    }

    @Test
    fun `ALARM tier event does not consume the group point so a later ALARM_STREAK scores`() {
        val engine = dayWindowEngine()

        engine.processBreach(breach(ViolationType.WIFI_TRANSCEIVER_ENABLED, ResponseTier.ALARM))

        assertEquals(0, repository.getStreak())

        engine.processBreach(breach(ViolationType.VALIDATED_NETWORK, ResponseTier.ALARM_STREAK))

        assertEquals(1, repository.getStreak())
        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
    }

    @Test
    fun `INSTANT_WIPE event never claims the group point`() {
        val engine = dayWindowEngine()

        engine.processBreach(breach(ViolationType.AIRPLANE_MODE_OFF, ResponseTier.INSTANT_WIPE))

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertEquals(0, repository.getStreak())
        // The point is still available for escalation: a fresh claim on the same
        // group succeeds only because the INSTANT_WIPE event did not spend it.
        assertTrue(repository.claimScoringGroupPoint(ViolationType.VALIDATED_NETWORK, 86_400_000L))
    }

    @Test
    fun `LOG_ONLY event cannot block an ALARM_STREAK wipe under the aggressive preset`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, wipeThreshold = 1, graceWindowSeconds = 0))
        val engine = dayWindowEngine()

        engine.processBreach(breach(ViolationType.WIFI_TRANSCEIVER_ENABLED, ResponseTier.LOG_ONLY))
        assertEquals(0, repository.getStreak())
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())

        engine.processBreach(breach(ViolationType.VALIDATED_NETWORK, ResponseTier.ALARM_STREAK))

        assertEquals(1, repository.getStreak())
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun `repeated ALARM_STREAK events in one group and day cannot farm the streak`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, wipeThreshold = 3))
        val engine = dayWindowEngine()

        engine.processBreach(breach(ViolationType.VALIDATED_NETWORK, ResponseTier.ALARM_STREAK))
        engine.processBreach(breach(ViolationType.AIRPLANE_MODE_OFF, ResponseTier.ALARM_STREAK))
        engine.processBreach(breach(ViolationType.BLUETOOTH_ACTIVITY, ResponseTier.ALARM_STREAK))

        // The three real threats share one daily point: only the first scores.
        assertEquals(1, repository.getStreak())
        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
    }

    @Test
    fun `ALARM_STREAK claims a fresh point once a new window begins`() {
        val firstDay = dayWindowEngine()
        firstDay.processBreach(breach(ViolationType.VALIDATED_NETWORK, ResponseTier.ALARM_STREAK))
        assertEquals(1, repository.getStreak())

        // An elapsed window (simulated by a zero-length window) grants a fresh claim.
        val nextDay = ThreatEngine(context, repository, dhizukuManager, customWindowMs = 0L, alarmNotifier = notifier)
        nextDay.processBreach(breach(ViolationType.BLUETOOTH_ACTIVITY, ResponseTier.ALARM_STREAK))

        assertEquals(2, repository.getStreak())
    }

    // --- Persistent in-app alarm marker ---

    @Test
    fun `ALARM breach records a persistent pending alarm`() {
        threatEngine.processBreach(
            breach(ViolationType.WIFI_TRANSCEIVER_ENABLED, ResponseTier.ALARM)
        )

        val pending = repository.getPendingAlarm()
        assertNotNull(pending)
        assertEquals(ViolationType.WIFI_TRANSCEIVER_ENABLED.scoringGroup.displayName, pending?.category)
        assertEquals(ViolationType.WIFI_TRANSCEIVER_ENABLED.description, pending?.description)
        assertFalse(pending?.isCountdown == true)
    }

    @Test
    fun `ALARM_STREAK below the wipe threshold records a persistent pending alarm`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, wipeThreshold = 3))
        threatEngine.processBreach(
            breach(ViolationType.VALIDATED_NETWORK, ResponseTier.ALARM_STREAK)
        )

        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
        val pending = repository.getPendingAlarm()
        assertNotNull(pending)
        assertEquals(ViolationType.VALIDATED_NETWORK.scoringGroup.displayName, pending?.category)
        assertEquals(ViolationType.VALIDATED_NETWORK.description, pending?.description)
        assertFalse(pending?.isCountdown == true)
    }

    @Test
    fun `ALARM_STREAK reaching the threshold with a grace window records a countdown pending alarm`() {
        repository.saveConfig(
            AppConfig(isEnabled = true, dryRunMode = true, wipeThreshold = 1, graceWindowSeconds = 60)
        )
        threatEngine.processBreach(
            breach(ViolationType.AIRPLANE_MODE_OFF, ResponseTier.ALARM_STREAK)
        )

        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
        val pending = repository.getPendingAlarm()
        assertNotNull(pending)
        assertEquals(ThreatEngine.COUNTDOWN_ALARM_CATEGORY, pending?.category)
        assertTrue(pending?.isCountdown == true)
        assertEquals(1, notifier.countdownLaunches)
    }

    @Test
    fun `executeWipeState with a grace window records a countdown pending alarm`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 60))

        threatEngine.executeWipeState()

        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
        val pending = repository.getPendingAlarm()
        assertNotNull(pending)
        assertEquals(ThreatEngine.COUNTDOWN_ALARM_CATEGORY, pending?.category)
        assertTrue(pending?.isCountdown == true)
    }

    @Test
    fun `executeWipeState accepted wipe records a pending alarm`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = false, graceWindowSeconds = 0))
        val mockBinder = MockDhizukuBinder()
        val engine = ThreatEngine(context, repository, DhizukuManager(context, mockBinder), customWindowMs = 0L, alarmNotifier = notifier)

        engine.executeWipeState()

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        val pending = repository.getPendingAlarm()
        assertNotNull(pending)
        assertEquals(ThreatEngine.WIPE_ALARM_CATEGORY, pending?.category)
        assertFalse(pending?.isCountdown == true)
    }

    @Test
    fun `executeWipeState refused wipe records a WIPE FAILED pending alarm`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = false, graceWindowSeconds = 0))
        val mockBinder = MockDhizukuBinder().apply { wipeAccepted = false }
        val engine = ThreatEngine(context, repository, DhizukuManager(context, mockBinder), customWindowMs = 0L, alarmNotifier = notifier)

        engine.executeWipeState()

        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
        val pending = repository.getPendingAlarm()
        assertNotNull(pending)
        assertEquals(ThreatEngine.WIPE_FAILED_ALARM_CATEGORY, pending?.category)
        assertFalse(pending?.isCountdown == true)
        assertEquals(1, notifier.wipeFailures)
    }

    @Test
    fun `INSTANT_WIPE breach records a persistent pending alarm`() {
        threatEngine.processBreach(
            breach(ViolationType.AIRPLANE_MODE_OFF, ResponseTier.INSTANT_WIPE)
        )

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        val pending = repository.getPendingAlarm()
        assertNotNull(pending)
        assertEquals(ThreatEngine.WIPE_ALARM_CATEGORY, pending?.category)
    }

    @Test
    fun `LOG_ONLY event records no pending alarm`() {
        threatEngine.processBreach(
            breach(ViolationType.WIFI_TRANSCEIVER_ENABLED, ResponseTier.LOG_ONLY)
        )

        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
        assertNull(repository.getPendingAlarm())
    }

    @Test
    fun `self-defense breach bypasses the device-protection alarm toggle`() {
        repository.saveConfig(
            AppConfig(
                isEnabled = false,
                dryRunMode = true,
                graceWindowSeconds = 0,
                deviceProtectionAlarmEnabled = false
            )
        )
        threatEngine.processSelfDefenseBreach("restriction missing")

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertNotNull(repository.getPendingAlarm())
    }

    @Test
    fun `self-defense ALARM_STREAK bypasses both gates and preserves configured tier`() {
        repository.saveConfig(
            AppConfig(
                isEnabled = false,
                dryRunMode = true,
                graceWindowSeconds = 0,
                deviceProtectionAlarmEnabled = false,
                wipeThreshold = 3,
                selfTamperTier = ResponseTier.ALARM_STREAK
            )
        )

        threatEngine.processSelfDefenseBreach("signature tamper")

        assertEquals(1, repository.getStreak())
        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
        assertNotNull(repository.getPendingAlarm())
    }

    @Test
    fun `ordinary device-protection breach remains suppressed when alarm is disabled`() {
        repository.saveConfig(
            AppConfig(isEnabled = true, dryRunMode = true, deviceProtectionAlarmEnabled = false)
        )
        threatEngine.processBreach(
            breach(ViolationType.DO_RESTRICTION_MISSING, ResponseTier.ALARM_STREAK)
        )

        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
        assertEquals(0, repository.getStreak())
        assertNotNull(repository.getVtReason(ViolationType.DO_RESTRICTION_MISSING))
        assertNull(repository.getPendingAlarm())
    }

    @Test
    fun `a later benign event does not clear an existing pending alarm`() {
        // An ALARM_STREAK below the wipe threshold lands in ALARM_ACTIVE and raises
        // the in-app alarm marker.
        threatEngine.processBreach(
            breach(ViolationType.AIRPLANE_MODE_OFF, ResponseTier.ALARM_STREAK)
        )
        assertNotNull(repository.getPendingAlarm())
        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())

        // A benign record-only event must never silently clear the owner's
        // unacknowledged alarm — only a PIN-gated acknowledgment can.
        threatEngine.processBreach(
            breach(ViolationType.WIFI_TRANSCEIVER_ENABLED, ResponseTier.LOG_ONLY)
        )

        assertNotNull(repository.getPendingAlarm())
        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
    }

    @Test
    fun `pending alarm is set even when the alert is rate-limited`() {
        // The in-app marker is independent of the alert rate limiter: a breach that
        // is not allowed to launch a real-time alert (notificationsPerBreach=1 and
        // the episode already fired) must still leave the persistent alarm state for
        // the dashboard to surface.
        repository.saveConfig(
            AppConfig(
                isEnabled = true,
                dryRunMode = true,
                notificationsPerBreach = 1,
                notificationTailMinutes = 720
            )
        )
        val engine = dayWindowEngine()

        engine.processBreach(breach(ViolationType.WIFI_TRANSCEIVER_ENABLED, ResponseTier.ALARM))
        assertEquals(1, notifier.alarmLaunches)
        assertNotNull(repository.getPendingAlarm())

        // Second alert of the same breach episode is rate-limited (no launch)...
        engine.processBreach(breach(ViolationType.WIFI_TRANSCEIVER_ENABLED, ResponseTier.ALARM))
        assertEquals(1, notifier.alarmLaunches)
        // ...but the persistent marker must still be present.
        assertNotNull(repository.getPendingAlarm())
    }

    // --- Protected-state tamper bypass ---

    @Test
    fun `state tamper is processed even when the watchdog is disabled`() {
        // A tampered protected value can flip config.isEnabled to its decrypt
        // default (false); the tamper response must not be silenced by that gate.
        repository.saveConfig(
            AppConfig(isEnabled = false, dryRunMode = true, graceWindowSeconds = 0, deviceProtectionAlarmEnabled = true)
        )

        threatEngine.processStateTamperBreach("Protected state failed to decrypt (tamper)")

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertEquals(1, repository.getVtCount(ViolationType.DO_RESTRICTION_MISSING))
        assertNotNull(repository.getPendingAlarm())
    }

    @Test
    fun `state tamper records its reason`() {
        threatEngine.processStateTamperBreach("corrupt security state detected")

        val reason = repository.getVtReason(ViolationType.DO_RESTRICTION_MISSING)
        assertTrue(reason != null && reason.contains("corrupt security state detected"))
    }

    @Test
    fun `state tamper bypasses the device-protection alarm toggle`() {
        repository.saveConfig(
            AppConfig(
                isEnabled = false,
                dryRunMode = true,
                graceWindowSeconds = 0,
                deviceProtectionAlarmEnabled = false
            )
        )

        threatEngine.processStateTamperBreach("Protected state failed to decrypt (tamper)")

        val reason = repository.getVtReason(ViolationType.DO_RESTRICTION_MISSING)
        assertTrue(reason != null && reason.contains("tamper"))
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertNotNull(repository.getPendingAlarm())
    }

    @Test
    fun `regular breaches are still dropped while the watchdog is disabled`() {
        // The tamper bypass is deliberately narrow: ordinary monitoring events
        // remain inert on a disabled watchdog.
        repository.saveConfig(
            AppConfig(isEnabled = false, dryRunMode = true, deviceProtectionAlarmEnabled = true)
        )

        threatEngine.processBreach(breach(ViolationType.AIRPLANE_MODE_OFF, ResponseTier.ALARM_STREAK))

        assertEquals(0, repository.getStreak())
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
        assertNull(repository.getPendingAlarm())
    }

    @Test
    fun `state tamper routes through the configured self-tamper tier`() {
        repository.saveConfig(
            AppConfig(
                isEnabled = true,
                dryRunMode = true,
                graceWindowSeconds = 0,
                deviceProtectionAlarmEnabled = false,
                wipeThreshold = 3,
                selfTamperTier = ResponseTier.ALARM_STREAK
            )
        )

        threatEngine.processStateTamperBreach("Protected state failed to decrypt (tamper)")

        // ALARM_STREAK tier scores a point and lands in ALARM_ACTIVE — the same
        // escalation any self-tamper at that tier produces, never a silent no-op.
        assertEquals(1, repository.getStreak())
        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
    }

    // --- Persisted wipe deadline & countdown reconciliation ---

    @Test
    fun `executeWipeState with a grace window persists the wipe deadline`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 60))
        val scheduler = RecordingGraceWipeScheduler()
        val engine = ThreatEngine(context, repository, dhizukuManager, customWindowMs = 0L, alarmNotifier = notifier, graceWipeScheduler = scheduler)

        engine.executeWipeState()

        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
        assertTrue("the countdown must persist a wipe deadline", repository.getWipeDeadline() > 0L)
        assertEquals(60_000L, repository.getWipeRemainingMs())
        assertEquals(1, scheduler.scheduleCalls)
    }

    // --- Countdown latch: an active wipe countdown is never re-armed ---

    @Test
    fun `re-invoking executeWipeState while in the countdown does not re-arm the wipe`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 60))
        val scheduler = RecordingGraceWipeScheduler()
        val engine = ThreatEngine(context, repository, dhizukuManager, customWindowMs = 0L, alarmNotifier = notifier, graceWipeScheduler = scheduler)

        engine.executeWipeState()
        val firstDeadline = repository.getWipeDeadline()
        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
        assertEquals(1, scheduler.scheduleCalls)

        // A second wipe escalation must be a no-op: the running countdown is
        // latched, so the absolute deadline and the scheduled alarm are untouched.
        engine.executeWipeState()

        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
        assertEquals("the absolute wipe deadline must not move", firstDeadline, repository.getWipeDeadline())
        assertEquals("the wipe must not be re-armed", 1, scheduler.scheduleCalls)
        assertEquals("the countdown must not be re-launched", 1, notifier.countdownLaunches)
    }

    @Test
    fun `a second breach reaching the threshold while in the countdown does not re-arm`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, wipeThreshold = 1, graceWindowSeconds = 60))
        val scheduler = RecordingGraceWipeScheduler()
        val engine = ThreatEngine(context, repository, dhizukuManager, customWindowMs = 0L, alarmNotifier = notifier, graceWipeScheduler = scheduler)

        engine.processBreach(breach(ViolationType.AIRPLANE_MODE_OFF, ResponseTier.ALARM_STREAK))
        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
        val firstDeadline = repository.getWipeDeadline()
        assertEquals(1, scheduler.scheduleCalls)

        // A later breach that again reaches the threshold must not reset the clock.
        engine.processBreach(breach(ViolationType.VALIDATED_NETWORK, ResponseTier.ALARM_STREAK))

        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
        assertEquals("the absolute wipe deadline must not move", firstDeadline, repository.getWipeDeadline())
        assertEquals("the wipe must not be re-armed", 1, scheduler.scheduleCalls)
    }

    @Test
    fun `an INSTANT_WIPE breach while in the countdown does not re-arm`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, wipeThreshold = 1, graceWindowSeconds = 60))
        val scheduler = RecordingGraceWipeScheduler()
        val engine = ThreatEngine(context, repository, dhizukuManager, customWindowMs = 0L, alarmNotifier = notifier, graceWipeScheduler = scheduler)

        engine.processBreach(breach(ViolationType.AIRPLANE_MODE_OFF, ResponseTier.ALARM_STREAK))
        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
        val firstDeadline = repository.getWipeDeadline()

        engine.processBreach(breach(ViolationType.BLUETOOTH_ACTIVITY, ResponseTier.INSTANT_WIPE))

        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
        assertEquals("the absolute wipe deadline must not move", firstDeadline, repository.getWipeDeadline())
        assertEquals("the wipe must not be re-armed", 1, scheduler.scheduleCalls)
    }

    @Test
    fun `the countdown latch does not block the wipe once the grace has elapsed`() {
        // The receiver drives the wipe with graceElapsed=true; the latch must only
        // prevent re-arming an active countdown, never stop the wipe that fires on
        // its deadline. The wipe must still execute and leave the countdown.
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 60))
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        repository.setWipeDeadline(repository.getMonotonicNow() + 60_000L)
        val scheduler = RecordingGraceWipeScheduler()
        val engine = ThreatEngine(context, repository, dhizukuManager, customWindowMs = 0L, alarmNotifier = notifier, graceWipeScheduler = scheduler)

        engine.executeWipeState(graceElapsed = true)

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertEquals(0L, repository.getWipeDeadline())
    }

    @Test
    fun `the countdown latch does not block an immediate wipe when the grace window is zero`() {
        // When graceWindowSeconds is zero there is no countdown to latch: an
        // escalation executes the wipe immediately even if a stale countdown state
        // was recorded.
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 0))
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        repository.setWipeDeadline(repository.getMonotonicNow() + 60_000L)

        threatEngine.executeWipeState()

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun `executing the wipe clears the persisted deadline`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 0))
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        repository.setWipeDeadline(repository.getMonotonicNow() + 60_000L)
        assertTrue(repository.getWipeDeadline() > 0L)

        threatEngine.executeWipeState(graceElapsed = true)

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertEquals(0L, repository.getWipeDeadline())
    }

    @Test
    fun `a refused wipe also clears the persisted deadline`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = false, graceWindowSeconds = 0))
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        repository.setWipeDeadline(repository.getMonotonicNow() + 60_000L)
        val refusedBinder = MockDhizukuBinder().apply { wipeAccepted = false }
        val engine = ThreatEngine(context, repository, DhizukuManager(context, refusedBinder), customWindowMs = 0L, alarmNotifier = notifier)

        engine.executeWipeState(graceElapsed = true)

        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
        assertEquals(0L, repository.getWipeDeadline())
    }

    @Test
    fun `cancelPendingWipe clears the persisted deadline and cancels the alarm`() {
        repository.setWipeDeadline(repository.getMonotonicNow() + 60_000L)
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        val scheduler = RecordingGraceWipeScheduler()
        val engine = ThreatEngine(context, repository, dhizukuManager, customWindowMs = 0L, alarmNotifier = notifier, graceWipeScheduler = scheduler)

        engine.cancelPendingWipe()

        assertEquals(1, scheduler.cancelCalls)
        assertEquals(0L, repository.getWipeDeadline())
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
    }

    @Test
    fun `a stale grace completion after disarm does not execute the wipe`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 60))
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        repository.setWipeDeadline(repository.getMonotonicNow() + 60_000L)

        threatEngine.cancelPendingWipe()
        threatEngine.executeWipeState(graceElapsed = true)

        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
        assertEquals(0L, repository.getWipeDeadline())
    }

    @Test
    fun `reconciliation after disarm does not re-arm the cancelled wipe`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 60))
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        repository.setWipeDeadline(repository.getMonotonicNow() + 60_000L)
        val scheduler = RecordingGraceWipeScheduler()
        val engine = ThreatEngine(
            context,
            repository,
            dhizukuManager,
            customWindowMs = 0L,
            alarmNotifier = notifier,
            graceWipeScheduler = scheduler
        )

        engine.cancelPendingWipe()
        engine.reconcilePendingWipe()

        assertTrue(scheduler.scheduleDelays.isEmpty())
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
        assertEquals(0L, repository.getWipeDeadline())
    }

    @Test
    fun `concurrent countdown starts across engine instances schedule only once`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 60))
        val scheduler = RecordingGraceWipeScheduler()
        val engines = List(16) {
            ThreatEngine(
                context,
                repository,
                dhizukuManager,
                customWindowMs = 0L,
                alarmNotifier = notifier,
                graceWipeScheduler = scheduler
            )
        }
        val ready = CountDownLatch(engines.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(engines.size)

        try {
            val futures = engines.map { engine ->
                executor.submit {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    engine.executeWipeState()
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }

            assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
            assertEquals(1, scheduler.scheduleCalls)
            assertEquals(1, notifier.countdownLaunches)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `reconcilePendingWipe re-arms the remaining delay while the countdown is active`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 60))
        val scheduler = RecordingGraceWipeScheduler()
        val engine = ThreatEngine(context, repository, dhizukuManager, customWindowMs = 0L, alarmNotifier = notifier, graceWipeScheduler = scheduler)
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        fakeElapsed = 10_000L
        repository.setWipeDeadline(repository.getMonotonicNow() + 30_000L)

        engine.reconcilePendingWipe()

        // Only the remaining delay is re-armed — the absolute deadline never moves.
        assertEquals(listOf(30_000L), scheduler.scheduleDelays)
        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
    }

    @Test
    fun `reconcilePendingWipe executes the wipe once the deadline has elapsed`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 60))
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        fakeElapsed = 0L
        repository.setWipeDeadline(repository.getMonotonicNow() + 5_000L)
        fakeElapsed = 50_000L

        threatEngine.reconcilePendingWipe()

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertEquals(0L, repository.getWipeDeadline())
    }

    @Test
    fun `reconcilePendingWipe is a no-op when the watchdog is disabled`() {
        repository.saveConfig(AppConfig(isEnabled = false, dryRunMode = true, graceWindowSeconds = 60))
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        repository.setWipeDeadline(repository.getMonotonicNow() + 5_000L)
        val scheduler = RecordingGraceWipeScheduler()
        val engine = ThreatEngine(context, repository, dhizukuManager, customWindowMs = 0L, alarmNotifier = notifier, graceWipeScheduler = scheduler)

        engine.reconcilePendingWipe()

        assertTrue(scheduler.scheduleDelays.isEmpty())
        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
    }

    @Test
    fun `reconcilePendingWipe is a no-op outside the countdown state`() {
        repository.setSecurityState(SecurityState.ALARM_ACTIVE)
        repository.setWipeDeadline(repository.getMonotonicNow() + 5_000L)
        val scheduler = RecordingGraceWipeScheduler()
        val engine = ThreatEngine(context, repository, dhizukuManager, customWindowMs = 0L, alarmNotifier = notifier, graceWipeScheduler = scheduler)

        engine.reconcilePendingWipe()

        assertTrue(scheduler.scheduleDelays.isEmpty())
        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
    }

    @Test
    fun `reconcilePendingWipe is a no-op when no wipe deadline was recorded`() {
        // A legacy countdown (pre-persistence) has no recorded deadline; its own
        // pending alarm governs, and reconciling must not invent a new one.
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 60))
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        assertEquals(0L, repository.getWipeDeadline())
        val scheduler = RecordingGraceWipeScheduler()
        val engine = ThreatEngine(context, repository, dhizukuManager, customWindowMs = 0L, alarmNotifier = notifier, graceWipeScheduler = scheduler)

        engine.reconcilePendingWipe()

        assertTrue(scheduler.scheduleDelays.isEmpty())
        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
    }

    // --- Exact-alarm prerequisite: a precise countdown can never be armed without it ---

    @Test
    fun `executeWipeState with a grace window but no exact-alarm capability fails closed to the wipe`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 60))
        val scheduler = RecordingGraceWipeScheduler().apply { exactAlarmCapability = false }
        val engine = ThreatEngine(context, repository, dhizukuManager, customWindowMs = 0L, alarmNotifier = notifier, graceWipeScheduler = scheduler)

        engine.executeWipeState()

        // No countdown may be entered when its precise alarm cannot be armed: the
        // escalation fails closed to the wipe instead of lying about a deadline.
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertEquals(0L, repository.getWipeDeadline())
        assertEquals(0, scheduler.scheduleCalls)
        assertEquals(0, notifier.countdownLaunches)
        assertEquals(
            "the audit alarm must record the exact-alarm-loss origin",
            ThreatEngine.WIPE_EXECUTED_EXACT_LOST_CATEGORY,
            repository.getPendingAlarm()?.category
        )
    }

    @Test
    fun `executeWipeState fails closed to the wipe when the exact schedule is rejected`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 60))
        val scheduler = RecordingGraceWipeScheduler().apply {
            scheduleResult = GraceWipeScheduler.WipeScheduleResult.SCHEDULING_FAILED
        }
        val engine = ThreatEngine(context, repository, dhizukuManager, customWindowMs = 0L, alarmNotifier = notifier, graceWipeScheduler = scheduler)

        engine.executeWipeState()

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertEquals(0L, repository.getWipeDeadline())
        assertEquals(1, scheduler.scheduleCalls)
        assertEquals(0, notifier.countdownLaunches)
        assertEquals(
            "the audit alarm must record the exact-alarm-loss origin",
            ThreatEngine.WIPE_EXECUTED_EXACT_LOST_CATEGORY,
            repository.getPendingAlarm()?.category
        )
    }

    @Test
    fun `a fail-closed wipe that is refused records the exact-alarm-loss failure category`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = false, graceWindowSeconds = 60))
        val scheduler = RecordingGraceWipeScheduler().apply { exactAlarmCapability = false }
        val refusedBinder = MockDhizukuBinder().apply { wipeAccepted = false }
        val engine = ThreatEngine(context, repository, DhizukuManager(context, refusedBinder), customWindowMs = 0L, alarmNotifier = notifier, graceWipeScheduler = scheduler)

        engine.executeWipeState()

        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
        assertEquals(1, notifier.wipeFailures)
        assertEquals(
            "a refused fail-closed wipe must be distinguishable in the audit trail",
            ThreatEngine.WIPE_FAILED_EXACT_LOST_CATEGORY,
            repository.getPendingAlarm()?.category
        )
    }

    @Test
    fun `a normal accepted wipe records the ordinary wipe category not the exact-loss one`() {
        // Contrast guard: only a fail-closed (exact-alarm-loss) wipe may use the
        // distinct audit category; a normal escalation must keep the ordinary one.
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = false, graceWindowSeconds = 0))
        val mockBinder = MockDhizukuBinder()
        val engine = ThreatEngine(context, repository, DhizukuManager(context, mockBinder), customWindowMs = 0L, alarmNotifier = notifier)

        engine.executeWipeState()

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertEquals(
            ThreatEngine.WIPE_ALARM_CATEGORY,
            repository.getPendingAlarm()?.category
        )
        assertTrue(
            "a normal wipe must not be flagged as exact-alarm-loss",
            repository.getPendingAlarm()?.category != ThreatEngine.WIPE_EXECUTED_EXACT_LOST_CATEGORY
        )
    }

    @Test
    fun `an INSTANT_WIPE breach with no exact-alarm capability still wipes immediately`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, wipeThreshold = 1, graceWindowSeconds = 60))
        val scheduler = RecordingGraceWipeScheduler().apply { exactAlarmCapability = false }
        val engine = ThreatEngine(context, repository, dhizukuManager, customWindowMs = 0L, alarmNotifier = notifier, graceWipeScheduler = scheduler)

        engine.processBreach(breach(ViolationType.AIRPLANE_MODE_OFF, ResponseTier.INSTANT_WIPE))

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertEquals(0, notifier.countdownLaunches)
    }

    // --- Exact-alarm loss mid-countdown: reconciliation fails closed to the wipe ---

    @Test
    fun `reconcilePendingWipe fails closed to the wipe when exact-alarm capability is lost`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 60))
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        fakeElapsed = 10_000L
        repository.setWipeDeadline(repository.getMonotonicNow() + 30_000L)
        val scheduler = RecordingGraceWipeScheduler().apply { exactAlarmCapability = false }
        val engine = ThreatEngine(context, repository, dhizukuManager, customWindowMs = 0L, alarmNotifier = notifier, graceWipeScheduler = scheduler)

        engine.reconcilePendingWipe()

        // The deadline could not be re-armed precisely (permission revoked while
        // the app was down), so the countdown fails closed to an immediate wipe.
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertEquals(0L, repository.getWipeDeadline())
        assertTrue(scheduler.scheduleDelays.isEmpty())
        assertEquals(
            "the reconciliation fail-closed wipe must record the exact-alarm-loss origin",
            ThreatEngine.WIPE_EXECUTED_EXACT_LOST_CATEGORY,
            repository.getPendingAlarm()?.category
        )
    }

    @Test
    fun `reconcilePendingWipe fails closed to the wipe when the re-arm cannot be scheduled exactly`() {
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 60))
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        fakeElapsed = 10_000L
        repository.setWipeDeadline(repository.getMonotonicNow() + 30_000L)
        val scheduler = RecordingGraceWipeScheduler().apply {
            scheduleDelayResult = GraceWipeScheduler.WipeScheduleResult.SCHEDULING_FAILED
        }
        val engine = ThreatEngine(context, repository, dhizukuManager, customWindowMs = 0L, alarmNotifier = notifier, graceWipeScheduler = scheduler)

        engine.reconcilePendingWipe()

        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertEquals(0L, repository.getWipeDeadline())
        assertEquals(listOf(30_000L), scheduler.scheduleDelays)
        assertEquals(
            "the failed re-arm must be recorded as exact-alarm-loss",
            ThreatEngine.WIPE_EXECUTED_EXACT_LOST_CATEGORY,
            repository.getPendingAlarm()?.category
        )
    }
}
