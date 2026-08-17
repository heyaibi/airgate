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

package com.airgate.dhizuku

import android.content.ComponentName
import android.content.Context
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.WipeResult
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Facade over the Dhizuku device-owner integration. The public surface and the
 * constructor signature are the stable API used across the app and the test
 * suite; the actual work is delegated to single-purpose collaborators:
 *
 *  - [DhizukuConnection] — binder init, availability, permission granting
 *  - [DhizukuDpmBridge] — hidden-API binder rewrap and admin-component resolution
 *  - [DhizukuPolicyWriter] — non-destructive policy writes (never dry-run gated)
 *  - [DhizukuDestructiveOps] — wipe / user removal (dry-run gated)
 *
 * Every transaction — the binder rewrap and every privileged policy/wipe call —
 * executes on this manager's own single-thread [transactionExecutor]. A single
 * serialized worker means no Dhizuku/DPM call ever runs on the caller's thread
 * (so the main thread can never be blocked by a slow or wedged Dhizuku server)
 * and each manager's transactions are ordered, so the binder rewrap and the
 * Dhizuku library's process-global binder access are never concurrent within an
 * instance. (There is no shared device-policy object to corrupt across calls:
 * [DhizukuDpmBridge.wrappedDpm] resolves a fresh `DevicePolicyManager` per
 * transaction from the per-context system-service cache.) Each transaction is
 * bounded by [transactionTimeoutMs] and fails closed on expiry or any failure: a
 * policy write reports `false`, a wipe reports [WipeResult.REJECTED], availability
 * reports `false`. A call that cannot complete in time is never a hang, never a
 * fabricated success, and never blocks the calling thread for longer than the
 * bound.
 */
class DhizukuManager(
    private val context: Context,
    binderWrapper: DhizukuBinderWrapper? = null,
    private val transactionExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(DhizukuManager.dhizukuThreadFactory()),
    private val transactionTimeoutMs: Long = DEFAULT_TRANSACTION_TIMEOUT_MS
) : AutoCloseable {
    private val connection = DhizukuConnection(context, binderWrapper)
    private val bridge = DhizukuDpmBridge(
        context = context,
        connection = connection,
        wrapper = binderWrapper,
        injectedAdminComponent = binderWrapper?.let {
            ComponentName(context.packageName, "${context.packageName}.DeviceAdminReceiver")
        }
    )
    private val policyWriter = DhizukuPolicyWriter(bridge)
    private val destructiveOps = DhizukuDestructiveOps(bridge)

    /**
     * Requests the Dhizuku binder. Must be called before any other Dhizuku-API
     * call. Returns true when Dhizuku is activated and the binder was received.
     */
    fun init(): Boolean = runTransaction(defaultOnFailure = false) { connection.init() }

    fun getDhizukuAvailability(): DhizukuAvailability =
        runTransaction(defaultOnFailure = DhizukuAvailability.UNAVAILABLE) { bridge.availability() }

    fun isDhizukuAvailable(): Boolean = getDhizukuAvailability() == DhizukuAvailability.AUTHORIZED

    /**
     * Requests Dhizuku permission. When Dhizuku is active and running this opens
     * the official Dhizuku grant dialog; if Dhizuku cannot be reached it falls
     * back to launching the Dhizuku app.
     *
     * Returns true only when permission is already granted synchronously. The grant
     * dialog is asynchronous, so a request that launches the dialog returns false;
     * the caller is notified via [onResult] once the listener reports back.
     */
    fun requestPermission(context: Context = this.context, onResult: ((Boolean) -> Unit)? = null): Boolean =
        runTransaction(defaultOnFailure = false) { connection.requestPermission(context, onResult) }

    /**
     * Requests a full factory reset via the Dhizuku-wrapped device-owner authority.
     * Dry-run mode returns [com.airgate.domain.model.WipeResult.SIMULATED]
     * without calling the destructive API. A transaction that cannot complete
     * within the bound fails closed to [WipeResult.REJECTED] — a slow or dead
     * Dhizuku server must never look like an accepted wipe.
     */
    fun wipeDevice(flags: Int, config: AppConfig): WipeResult =
        runTransaction(defaultOnFailure = WipeResult.REJECTED) { destructiveOps.wipeDevice(flags, config) }

    fun getAdminComponent(): ComponentName? =
        runTransaction(defaultOnFailure = null) { bridge.getAdminComponent() }

    fun setGlobalSetting(key: String, value: String, config: AppConfig): Boolean =
        runTransaction(defaultOnFailure = false) { policyWriter.setGlobalSetting(key, value, config) }

    fun addUserRestriction(restrictionKey: String, config: AppConfig): Boolean =
        runTransaction(defaultOnFailure = false) { policyWriter.addUserRestriction(restrictionKey, config) }

    fun clearUserRestriction(restrictionKey: String, config: AppConfig): Boolean =
        runTransaction(defaultOnFailure = false) { policyWriter.clearUserRestriction(restrictionKey, config) }

    /**
     * Executes [block] on the single serialized transaction thread and waits at
     * most [transactionTimeoutMs] for the result. Every failure mode — a timeout,
     * a rejected submission (executor shut down), an exception thrown by the
     * transaction, or an interrupt — resolves to [defaultOnFailure], so the
     * Dhizuku layer can never throw at its callers and never blocks past the
     * bound.
     */
    private fun <T> runTransaction(defaultOnFailure: T, block: () -> T): T {
        return try {
            transactionExecutor.submit(Callable { block() }).get(transactionTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            defaultOnFailure
        } catch (e: RejectedExecutionException) {
            defaultOnFailure
        } catch (e: ExecutionException) {
            defaultOnFailure
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            defaultOnFailure
        }
    }

    override fun close() {
        transactionExecutor.shutdown()
    }

    companion object {
        const val DEFAULT_TRANSACTION_TIMEOUT_MS = 3_000L

        /**
         * Dhizuku worker threads are daemons so a manager that is never shut down
         * (most instances are long-lived components) cannot keep a process alive
         * or pin the JVM in a test run after its owning component is gone.
         */
        private fun dhizukuThreadFactory(): ThreadFactory = ThreadFactory { runnable ->
            Thread(runnable, "dhizuku-transaction").apply { isDaemon = true }
        }
    }
}
