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

// SIM states that mean a card is physically present on a slot. ABSENT and NOT_READY
// are excluded: they fire on boot, airplane-mode toggles and radio restarts, which are
// normal for an air-gapped device. The `ss` extra key and values are the legacy
// ACTION_SIM_STATE_CHANGED strings (see AOSP IccCardProxy.getIccStateIntentString);
// PIN/PUK/NETWORK-locked all map to "LOCKED".
private val SIM_PRESENT_STATES = setOf(
    "READY", "LOADED", "IMSI", "LOCKED",
    "CARD_IO_ERROR", "CARD_RESTRICTED"
)

internal fun isSimPresentState(simState: String?): Boolean =
    simState != null && simState in SIM_PRESENT_STATES
