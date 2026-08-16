# Roadmap

- [ ] `WIPE_PLATFORM_CALL` — Robolectric-based test closing the real-DPM gap: exercise `DhizukuDestructiveOps`'s literal `DevicePolicyManager.wipeDevice(flags)` (API 34+) and `wipeData(flags)` (pre-34) invocations against a Robolectric shadow, so the ACCEPTED-after-success and REJECTED-after-throw branches of the dpm path are asserted directly instead of only via the test-wrapper seam. Add Robolectric to `testImplementation`, inject the shadowed DPM through the `wrappedDpm()` resolution, and assert `WipeResult` for both the success and throwing platform calls.
- [ ] `ROAMING_ENTERED` — cellular roaming detection
- [ ] `SAFE_MODE_OR_NEW_ADMIN` — safe-mode / new-device-admin detection
- [ ] `GEOFENCE_BREACH` — geofence breach detection (config field also removed)
- [ ] `UNEXPECTED_MOTION` — motion detection
