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

package com.airgate.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.airgate.BuildConfig
import com.airgate.MainActivity
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.detector.NetworkDetector
import com.airgate.detector.RadioStateDetector
import com.airgate.detector.SignalListener
import com.airgate.detector.SystemSettingsDetector
import com.airgate.detector.UsbDetector
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.BreachEvent
import com.airgate.engine.ThreatEngine
import com.airgate.policy.DevicePolicyEnforcer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class WatchdogService : Service(), SignalListener {

    companion object {
        private const val NOTIF_CHANNEL_ID = "airgate_watchdog_channel"
        private const val NOTIF_ID = 1001
        private const val POLL_INTERVAL_MS = 10_000L

        fun startService(context: Context) {
            // minSdk is 26 (O), so startForegroundService is always supported.
            context.startForegroundService(Intent(context, WatchdogService::class.java))
        }
    }

    private lateinit var repository: SecurityStateRepository
    private lateinit var threatEngine: ThreatEngine
    private lateinit var postureAudit: PostureAudit

    private lateinit var networkDetector: NetworkDetector
    private lateinit var radioStateDetector: RadioStateDetector
    private lateinit var usbDetector: UsbDetector
    private lateinit var systemSettingsDetector: SystemSettingsDetector

    // The 10s settings poll must never run on the main thread; it performs
    // binder/file/query work. Run it on a dedicated HandlerThread instead. The
    // tamper check rides the same loop: tamper circuits stay awake whether or
    // not the watchdog is enabled, so a paused monitor still escalates any
    // tampering with its own persisted state.
    private var auditThread: HandlerThread? = null
    private var auditHandler: Handler? = null

    // Enforcement (breach processing, wipe reconciliation, the boot self-defense
    // audit) must never run on the main thread: it reaches the Dhizuku binder,
    // which may block when the Dhizuku server is slow or wedged. Every entry
    // point below enqueues to this single worker and returns immediately, so a
    // detector broadcast or service callback can never stall the UI thread.
    private val breachExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "watchdog-enforcement").apply { isDaemon = true }
        }
    private val auditRunnable = object : Runnable {
        override fun run() {
            AuditLoop.tick(
                ensureNetworkRegistration = { networkDetector.ensureRegistered() },
                checkWifiRadioState = { networkDetector.checkWifiRadioState() },
                checkRadioState = { radioStateDetector.checkRadioState() },
                checkSettingsState = { systemSettingsDetector.checkSettingsState() },
                checkTamperOnly = { postureAudit.checkTamperOnly() }
            )
            auditHandler?.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = SecurityStateRepository(applicationContext)
        val dhizukuManager = DhizukuManager(applicationContext)
        threatEngine = ThreatEngine(applicationContext, repository, dhizukuManager)
        postureAudit = PostureAudit(
            applicationContext, repository, dhizukuManager, threatEngine,
            DevicePolicyEnforcer(applicationContext, dhizukuManager)
        )

        // The service always runs and registers its detectors; whether detected
        // events are ENFORCED (alarm / streak / wipe / hardening) is decided solely
        // by config.isEnabled in ThreatEngine / PostureAudit / SafetyNetScheduler.
        networkDetector = NetworkDetector(applicationContext, this)
        radioStateDetector = RadioStateDetector(applicationContext, this)
        usbDetector = UsbDetector(applicationContext, this)
        systemSettingsDetector = SystemSettingsDetector(applicationContext, this, repository)

        createNotificationChannel()
        startForegroundServiceWithNotif()

        // Register detectors for system broadcasts. These are system broadcasts
        // (airplane mode, USB attach, time/SIM changes), so RECEIVER_EXPORTED lets
        // the system deliver them while ContextCompat keeps registration safe on
        // all supported API levels.
        networkDetector.startMonitoring()
        ContextCompat.registerReceiver(this, radioStateDetector, radioStateDetector.getIntentFilter(), ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(this, usbDetector, usbDetector.getIntentFilter(), ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(this, systemSettingsDetector, systemSettingsDetector.getIntentFilter(), ContextCompat.RECEIVER_EXPORTED)

        // Perform self-defense check. The pinned signature
        // hash is baked into BuildConfig from the signing certificate at build time.
        val selfDefenseManager = com.airgate.defense.SelfDefenseManager(
            applicationContext, dhizukuManager, threatEngine, BuildConfig.EXPECTED_SIGNATURE_HASH, repository
        )
        // The audit performs a Dhizuku binder availability check; never run it on
        // the main thread (a slow Dhizuku server at boot must not block service start).
        enqueueEnforcement { selfDefenseManager.performSelfDefenseAudit() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start the periodic background posture audit (AlarmManager-driven, survives
        // process death). The in-process 10s settings poll runs on a background thread.
        // Note: SafetyNetScheduler.schedule() and the poll both no-op when
        // config.isEnabled is false, so a disabled install stays passive without
        // the service needing to be torn down.
        SafetyNetScheduler.schedule(applicationContext)

        // Reconcile a wipe countdown that may have survived a restart/reboot: a
        // reboot clears AlarmManager alarms, so the persisted deadline is used to
        // re-arm the wipe for the remaining grace or execute it if it elapsed.
        // The reconciliation may fire the wipe itself, so it runs off the main thread.
        enqueueEnforcement { threatEngine.reconcilePendingWipe() }

        startAuditLoop()
        return START_STICKY
    }

    private fun startAuditLoop() {
        if (auditThread != null) return
        val thread = HandlerThread("watchdog-audit").also { it.start() }
        auditThread = thread
        auditHandler = Handler(thread.looper).apply {
            post(auditRunnable)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SafetyNetScheduler.cancel(applicationContext)
        auditHandler?.removeCallbacks(auditRunnable)
        auditHandler = null
        auditThread?.quitSafely()
        auditThread = null
        breachExecutor.shutdown()
        try {
            networkDetector.stopMonitoring()
            unregisterReceiver(radioStateDetector)
            unregisterReceiver(usbDetector)
            unregisterReceiver(systemSettingsDetector)
        } catch (e: Exception) {
            // Ignore unregister errors
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onBreachDetected(event: BreachEvent) {
        // Enforcement reaches the Dhizuku binder, so it must never run inline on
        // the detector's (main) broadcast thread. Enqueue and return immediately.
        enqueueEnforcement { threatEngine.processBreach(event) }
    }

    /**
     * Enqueues [task] on the enforcement worker. A task submitted while the
     * service is tearing down (the executor is shut down in onDestroy, and a
     * broadcast already in flight can still arrive in that window) is dropped
     * instead of crashing the caller with a rejected-submission exception.
     */
    internal fun enqueueEnforcement(task: Runnable) {
        try {
            breachExecutor.execute(task)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // The watchdog is stopping; a breach landing in the teardown window is dropped.
        }
    }

    private fun createNotificationChannel() {
        // minSdk is 26 (O), so notification channels are always supported.
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "Airgate Watchdog Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitors device security posture and airgap state"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundServiceWithNotif() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Airgate")
            .setContentText("Monitoring active — device offline")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }
}
