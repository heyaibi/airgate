/*
 * Copyright (C) 2026 The Airgate project contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 */

package com.airgate.detector

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.testutil.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemSettingsDetectorTest {

    @Test
    fun `settings poll does not read redacted settings or host-only USB state`() {
        val listener = RecordingListener()
        val detector = SystemSettingsDetector(
            context = ContextWrapper(null),
            listener = listener,
            repository = SecurityStateRepository(InMemorySharedPreferences())
        )

        detector.checkSettingsState()

        assertTrue(listener.events.isEmpty())
    }

    @Test
    fun `broadcast filter retains clock and sim monitoring`() {
        val detector = SystemSettingsDetector(
            context = ContextWrapper(null),
            listener = RecordingListener(),
            repository = SecurityStateRepository(InMemorySharedPreferences())
        )
        val filter = detector.getIntentFilter()

        assertTrue(filter.hasAction(android.content.Intent.ACTION_TIME_CHANGED))
        assertTrue(filter.hasAction(android.content.Intent.ACTION_TIMEZONE_CHANGED))
        assertTrue(filter.hasAction("android.intent.action.SIM_STATE_CHANGED"))
        assertEquals(3, filter.countActions())
    }

    private class RecordingListener : SignalListener {
        val events = mutableListOf<com.airgate.domain.model.BreachEvent>()

        override fun onBreachDetected(event: com.airgate.domain.model.BreachEvent) {
            events += event
        }
    }
}
