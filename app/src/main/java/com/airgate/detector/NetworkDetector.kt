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

package com.airgate.detector

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import com.airgate.domain.model.BreachEvent
import com.airgate.domain.model.ViolationType
import java.util.UUID

/**
 * Retry and escalation bookkeeping for the connectivity-listener registration.
 *
 * The network callback is the fast detection path; when it cannot be registered
 * (a boot-time glitch, a permission problem, or the system's per-app callback
 * cap), the detector must keep trying instead of staying dead for the service
 * lifetime. This state drives that self-healing and the fail-loud escalation.
 * Kept free of Android framework calls so every transition is JVM-testable.
 */
internal data class RegistrationState(
    val registered: Boolean = false,
    val consecutiveFailures: Int = 0,
    val firstFailureAtMs: Long = -1L,
    val lastEscalationAtMs: Long = -1L,
    val nextAttemptAtMs: Long = 0L
)

/** One tick's decision: whether to attempt registration now and whether to escalate now. */
internal data class RegistrationDecision(
    val attemptRegistration: Boolean,
    val escalate: Boolean
)

class NetworkDetector(
    private val context: Context,
    private val listener: SignalListener,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val logWarning: (String, Throwable) -> Unit =
        { message, throwable -> Log.w(TAG, message, throwable) },
    private val registrationAction: (() -> Unit)? = null
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            val breaches = resolveBreaches(networkCapabilities)
            val hasWifiTransport =
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            var transceiverToEmit: BreachEvent? = null
            synchronized(radioLatchLock) {
                val result = resolveRadioStateCallback(wifiRadioReported, hasWifiTransport)
                wifiRadioReported = result.nextReported
                if (result.shouldEmit) {
                    transceiverToEmit = breaches.firstOrNull {
                        it.violationType == ViolationType.WIFI_TRANSCEIVER_ENABLED
                    }
                }
            }
            breaches.asSequence()
                .filter { it.violationType != ViolationType.WIFI_TRANSCEIVER_ENABLED }
                .forEach(listener::onBreachDetected)
            transceiverToEmit?.let(listener::onBreachDetected)
        }

        override fun onLost(network: Network) {
            // A monitored network dropping is GOOD for an air-gapped device —
            // disconnection is the enforced state. No breach is raised here; the callback
            // is overridden so future state tracking (e.g. "last validated network") has
            // a clean reset point.
            super.onLost(network)
        }
    }

    // Shared "radio-on episode" latch: WIFI_TRANSCEIVER_ENABLED is reported at most
    // once per radio-on episode, whichever mechanism observes it first. Both the
    // network callback (Wi-Fi transport present on a connected network) and the
    // periodic poll (the radio switch itself) set it, and only the poll's
    // definitive DISABLED observation resets it — so a radio that stays on while
    // connected then drops back to unconnected is one episode, and a transient
    // failed read never forgets an on radio. Guarded because the callback runs on
    // the main thread while the poll runs on the audit thread.
    private val radioLatchLock = Any()
    private var wifiRadioReported = false

    // Registration state is mutated from both the main thread (startMonitoring /
    // stopMonitoring) and the audit thread (ensureRegistered), so every mutation
    // is guarded. The binder calls themselves happen outside the lock.
    private val registrationLock = Any()
    private var registrationState = RegistrationState()
    @Volatile
    private var tornDown = false

    internal fun isRegistered(): Boolean = synchronized(registrationLock) {
        registrationState.registered
    }

    /**
     * Registers the connectivity callback. This is the initial attempt only;
     * the periodic audit tick keeps the registration healthy via [ensureRegistered].
     * A double-start on an already-registered instance is a no-op.
     */
    fun startMonitoring() {
        val shouldAttempt = synchronized(registrationLock) {
            !tornDown && !registrationState.registered
        }
        if (shouldAttempt) attemptRegistration()
    }

    fun stopMonitoring() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Ignore unregister errors
        }
        synchronized(registrationLock) {
            registrationState = RegistrationState()
            tornDown = true
        }
    }

    /**
     * Keeps the registration healthy on the audit tick. While the callback is
     * registered this is a no-op. While it is not, this re-attempts registration
     * once the backoff window has elapsed and, once the failure has persisted for
     * a full minute, raises a [ViolationType.MONITOR_REGISTRATION_FAILED] breach
     * so a dead fast path is never silent — the same fail-closed posture the
     * tamper circuits use. Escalation re-fires at most once per re-escalation
     * interval while the failure persists.
     */
    fun ensureRegistered() {
        var escalate = false
        var attempt = false
        var failures = 0
        val now = nowMs()
        synchronized(registrationLock) {
            if (tornDown) return
            val decision = resolveRegistrationTick(registrationState, now)
            if (decision.escalate) {
                registrationState = markEscalated(registrationState, now)
                escalate = true
            }
            attempt = decision.attemptRegistration
            failures = registrationState.consecutiveFailures
        }
        if (escalate) {
            listener.onBreachDetected(registrationFailureBreach(failures))
        }
        if (attempt) attemptRegistration()
    }

    private fun attemptRegistration() {
        // Best-effort cleanup: a previous attempt may have half-registered the
        // callback before failing, and the retry cycle must never leak
        // registrations (or trip the double-register IllegalArgumentException).
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Nothing was registered yet — the expected case on a first attempt.
        }
        try {
            performRegistration()
            var releaseAfterTeardown = false
            synchronized(registrationLock) {
                if (tornDown) {
                    // The service is tearing down while this attempt was in
                    // flight; the callback just registered must be released, not
                    // leaked until process death.
                    releaseAfterTeardown = true
                } else {
                    registrationState = onRegistrationSucceeded(registrationState)
                }
            }
            if (releaseAfterTeardown) {
                try {
                    connectivityManager.unregisterNetworkCallback(networkCallback)
                } catch (e: Exception) {
                    // Ignore unregister errors
                }
            }
        } catch (e: Exception) {
            logWarning("Network callback registration failed; a retry is scheduled", e)
            synchronized(registrationLock) {
                registrationState = onRegistrationFailed(registrationState, nowMs())
            }
        }
    }

    private fun performRegistration() {
        val action = registrationAction
        if (action != null) {
            action()
            return
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    /**
     * Periodic poll of the Wi-Fi radio state. The network callback above only
     * fires for connected networks, so a radio that is on but unassociated
     * (scanning, never connected) produces zero callbacks and would otherwise be
     * invisible. Reading [WifiManager.getWifiState] sees the radio switch itself,
     * so every on state is detected regardless of whether any network exists.
     * Runs on the audit loop's background thread.
     */
    fun checkWifiRadioState() {
        val wifiState = readWifiState()
        var emit = false
        synchronized(radioLatchLock) {
            val result = resolveRadioStatePoll(wifiRadioReported, wifiState)
            wifiRadioReported = result.nextReported
            emit = result.shouldEmit
        }
        if (emit) {
            listener.onBreachDetected(radioPollBreach(wifiState))
        }
    }

    private fun readWifiState(): Int {
        return try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.wifiState
        } catch (e: Exception) {
            // A failed read is treated as UNKNOWN: no breach fires for it, the
            // reported-episode state is left untouched, and the next poll
            // re-attempts the read instead of staying poisoned.
            WifiManager.WIFI_STATE_UNKNOWN
        }
    }

    companion object {
        private const val TAG = "NetworkDetector"

        /** Backoff after the first failed attempt (one audit tick). */
        internal const val REGISTRATION_RETRY_FAST_MS = 10_000L
        /** Backoff after the second failed attempt. */
        internal const val REGISTRATION_RETRY_SLOW_MS = 60_000L
        /** Backoff cap for every attempt after the second (5 minutes). */
        internal const val REGISTRATION_RETRY_MAX_MS = 300_000L
        /** A registration failure that persists this long is escalated. */
        internal const val REGISTRATION_ESCALATION_AFTER_MS = 60_000L
        /** Escalation re-fires at most this often while the failure persists. */
        internal const val REGISTRATION_REESCALATION_INTERVAL_MS = 300_000L

        /**
         * Backoff delay after [consecutiveFailures] consecutive failed attempts:
         * fast (one tick) for the first, slow (a minute) for the second, capped
         * at five minutes thereafter so a long outage re-checks every five
         * minutes without hammering ConnectivityService.
         */
        internal fun registrationBackoffDelayMs(consecutiveFailures: Int): Long = when {
            consecutiveFailures <= 1 -> REGISTRATION_RETRY_FAST_MS
            consecutiveFailures == 2 -> REGISTRATION_RETRY_SLOW_MS
            else -> REGISTRATION_RETRY_MAX_MS
        }

        /**
         * Pure decision logic for one audit tick while the callback is
         * unregistered. A registered (or torn-down) state never attempts and
         * never escalates. Escalation fires only after the failure span has
         * reached [REGISTRATION_ESCALATION_AFTER_MS] and at most once per
         * [REGISTRATION_REESCALATION_INTERVAL_MS] afterwards, so a persistent
         * failure is loud but never floods. Free of Android framework calls so
         * every branch is unit-testable.
         */
        internal fun resolveRegistrationTick(
            state: RegistrationState,
            nowMs: Long
        ): RegistrationDecision {
            if (state.registered) return RegistrationDecision(attemptRegistration = false, escalate = false)
            val failureSpanMs = if (state.firstFailureAtMs < 0L) 0L else nowMs - state.firstFailureAtMs
            val dueToEscalate = state.firstFailureAtMs >= 0L &&
                failureSpanMs >= REGISTRATION_ESCALATION_AFTER_MS &&
                (state.lastEscalationAtMs < 0L ||
                    nowMs - state.lastEscalationAtMs >= REGISTRATION_REESCALATION_INTERVAL_MS)
            return RegistrationDecision(
                attemptRegistration = nowMs >= state.nextAttemptAtMs,
                escalate = dueToEscalate
            )
        }

        /**
         * Records a successful registration: the failure streak is fully reset
         * and the detector is healthy again. Idempotent on an already-registered
         * state.
         */
        internal fun onRegistrationSucceeded(state: RegistrationState): RegistrationState =
            if (state.registered) state else RegistrationState(registered = true)

        /**
         * Records a failed attempt: increments the consecutive-failure count,
         * latches the first-failure timestamp (so the escalation span counts
         * from the start of the outage, not from each retry), and schedules the
         * next attempt on the backoff schedule.
         */
        internal fun onRegistrationFailed(state: RegistrationState, nowMs: Long): RegistrationState {
            val failures = state.consecutiveFailures + 1
            return RegistrationState(
                registered = false,
                consecutiveFailures = failures,
                firstFailureAtMs = if (state.firstFailureAtMs >= 0L) state.firstFailureAtMs else nowMs,
                lastEscalationAtMs = state.lastEscalationAtMs,
                nextAttemptAtMs = nowMs + registrationBackoffDelayMs(failures)
            )
        }

        /** Records that an escalation breach was raised at [nowMs]. */
        internal fun markEscalated(state: RegistrationState, nowMs: Long): RegistrationState =
            state.copy(lastEscalationAtMs = nowMs)

        /**
         * Builds the escalation breach so its metadata is testable on the JVM.
         * Rides the standard detector-to-engine breach path (alarm + SYSTEM_TAMPER
         * scoring point) so a dead fast path is visible in the audit log and
         * Security Activity, exactly like any other violation.
         */
        internal fun registrationFailureBreach(consecutiveFailures: Int): BreachEvent = breachOf(
            ViolationType.MONITOR_REGISTRATION_FAILED,
            rawMetadata = mapOf(
                "source" to "NETWORK_MONITOR",
                "consecutiveFailures" to consecutiveFailures.toString()
            )
        )

        /**
         * Outcome of one radio-state observation for either mechanism: whether a
         * WIFI_TRANSCEIVER_ENABLED breach must be emitted now, and the reported
         * value the caller must remember for the shared episode latch.
         */
        internal data class RadioStateResult(
            val shouldEmit: Boolean,
            val nextReported: Boolean
        )

        /**
         * Pure decision logic for the periodic poll, keyed on the radio switch
         * state alone. A breach fires only when the radio is fully enabled and the
         * episode has not yet been reported, so a sustained radio-on state never
         * re-fires on every tick. Only definitive states move the latch: ENABLED
         * reports, DISABLED clears, and the transitional or unknown states
         * (ENABLING / DISABLING / UNKNOWN — including a failed read) leave it
         * untouched, so a transient read failure can never forget an on radio.
         * The first observation (previous == false) treats an already-enabled
         * radio as a violation: the radio being live is the air-gap breach
         * whether or not it is connected to anything. Free of Android framework
         * calls so every branch is unit-testable.
         */
        internal fun resolveRadioStatePoll(
            previousReported: Boolean,
            wifiState: Int
        ): RadioStateResult {
            val shouldEmit = wifiState == WifiManager.WIFI_STATE_ENABLED && !previousReported
            val nextReported = when (wifiState) {
                WifiManager.WIFI_STATE_ENABLED -> true
                WifiManager.WIFI_STATE_DISABLED -> false
                else -> previousReported
            }
            return RadioStateResult(
                shouldEmit = shouldEmit,
                nextReported = nextReported
            )
        }

        /**
         * Pure decision logic for the network-callback path, keyed on Wi-Fi
         * transport presence. Emits only when a Wi-Fi transport is present and the
         * episode has not already been reported (by this callback or by the poll),
         * which keeps the two mechanisms from recording the same radio-on episode
         * twice. Absence of a transport never clears the latch — only the poll's
         * definitive DISABLED observation ends an episode. Free of Android
         * framework calls so every branch is unit-testable.
         */
        internal fun resolveRadioStateCallback(
            previousReported: Boolean,
            wifiTransportPresent: Boolean
        ): RadioStateResult {
            return RadioStateResult(
                shouldEmit = wifiTransportPresent && !previousReported,
                nextReported = if (wifiTransportPresent) true else previousReported
            )
        }

        /**
         * Builds the poll-sourced WIFI_TRANSCEIVER_ENABLED breach so the metadata
         * is testable on the JVM. Distinct from the callback-sourced breach (which
         * rides the existing [resolveBreaches] path) via the WIFI_POLL source.
         */
        internal fun radioPollBreach(wifiState: Int): BreachEvent = breachOf(
            ViolationType.WIFI_TRANSCEIVER_ENABLED,
            rawMetadata = mapOf(
                "source" to "WIFI_POLL",
                "state" to wifiState.toString()
            )
        )

        /**
         * Framework-coupled entry point: extracts the capability/transport state
         * from a [NetworkCapabilities] snapshot and resolves the breaches to raise.
         * Kept in the companion so the decision logic below stays free of Android
         * framework calls and every branch is JVM-testable.
         */
        internal fun resolveBreaches(caps: NetworkCapabilities): List<BreachEvent> {
            return resolveBreaches(
                hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                hasValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                hasWifiTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                hasCellularTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                hasEthernetTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                hasBluetoothTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
            )
        }

        /**
         * Pure decision logic for one capabilities snapshot: maps the
         * framework-observed capability/transport state to the breaches to raise.
         * Free of Android framework calls so every branch is unit-testable.
         *
         * The Wi-Fi transceiver violation fires on Wi-Fi transport presence alone,
         * independent of NET_CAPABILITY_VALIDATED: the air-gap breach is the radio
         * being live, whether or not the network has passed internet validation
         * (captive portals and LAN-only APs never validate).
         */
        internal fun resolveBreaches(
            hasInternet: Boolean,
            hasValidated: Boolean,
            hasWifiTransport: Boolean,
            hasCellularTransport: Boolean,
            hasEthernetTransport: Boolean,
            hasBluetoothTransport: Boolean
        ): List<BreachEvent> {
            val hasValidatedInternet = hasInternet && hasValidated

            val transportStr = when {
                hasWifiTransport -> "WIFI"
                hasCellularTransport -> "CELLULAR"
                hasEthernetTransport -> "ETHERNET"
                hasBluetoothTransport -> "BLUETOOTH"
                else -> "OTHER"
            }

            // VALIDATED_NETWORK must be transport-agnostic — it must also fire
            // on Wi-Fi, not only when the Wi-Fi branch below happens not to match.
            val source = if (hasWifiTransport) "WIFI_MONITOR" else "NETWORK_MONITOR"

            val breaches = mutableListOf<BreachEvent>()

            if (hasValidatedInternet) {
                breaches += breachOf(
                    ViolationType.VALIDATED_NETWORK,
                    rawMetadata = mapOf("transport" to transportStr, "source" to source)
                )
            }

            // Wi-Fi transport present means the transceiver is live and the air gap
            // is broken — the state itself is the violation, independent of whether
            // the network has validated internet connectivity.
            if (hasWifiTransport) {
                breaches += breachOf(
                    ViolationType.WIFI_TRANSCEIVER_ENABLED,
                    rawMetadata = mapOf("transport" to "WIFI", "source" to source)
                )
            }

            if (hasEthernetTransport && (hasInternet || hasValidated)) {
                breaches += breachOf(
                    ViolationType.OTG_ETHERNET_ATTACHED,
                    rawMetadata = mapOf("transport" to transportStr, "source" to source)
                )
            }

            return breaches
        }

        private fun breachOf(
            violationType: ViolationType,
            rawMetadata: Map<String, String>
        ): BreachEvent {
            return BreachEvent(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                violationType = violationType,
                tier = violationType.defaultTier,
                weight = violationType.defaultWeight,
                rawMetadata = rawMetadata
            )
        }
    }
}
