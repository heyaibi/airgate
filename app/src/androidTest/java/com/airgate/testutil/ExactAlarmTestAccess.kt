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

package com.airgate.testutil

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * Instrumented-test helper for the SCHEDULE_EXACT_ALARM special access. Arming
 * the watchdog requires it (the precise wipe countdown is an exact alarm), and
 * on Android 13+ fresh installs are denied by default, so tests that arm must
 * grant it up front.
 *
 * Only the *grant* direction is used here: the platform kills the app process
 * the moment the access is revoked, so a live revoke cannot be observed inside
 * a running test process — the revoked-state paths are covered on the JVM.
 */
object ExactAlarmTestAccess {
    fun grant(context: Context) {
        runShell(context, "appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
    }

    private fun runShell(context: Context, command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            .use { pipe ->
                InputStreamReader(FileInputStream(pipe.fileDescriptor)).readText()
            }
    }
}
