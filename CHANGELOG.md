# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-08-18

### Added

- **Always-on watchdog** — a foreground service (`WatchdogService`) driving four detectors (network, radio state, USB, system settings). System broadcasts catch most events instantly; a 10-second poll audits the rest and reads live Bluetooth/airplane-mode state so radios already on when monitoring starts are still detected. The connectivity listener self-heals and raises a `MONITOR_REGISTRATION_FAILED` violation instead of failing silently.
- **14 violation signals** across three scoring groups — Wireless, USB, and System Tamper — with per-signal trigger, response tier, and alarm/point behavior.
- **Weighted tiered accounting** — at most one threat point per scoring group per 24-hour window; persistent streak measured against a configurable wipe threshold (default 3).
- **Four response tiers** — `LOG_ONLY`, `ALARM`, `ALARM_STREAK`, and `INSTANT_WIPE`; self-defense failures route through `INSTANT_WIPE` by default.
- **Grace countdown** — optional delay before a wipe, running on a reboot-surviving monotonic clock and armed as an exact alarm; if exact-alarm access is lost the app fails closed to an immediate wipe.
- **Full wipe scope** — factory reset, optionally including FRP data, with honest result tracking (`ACCEPTED` / `SIMULATED` / `REJECTED`).
- **Dry-Run mode (default ON)** — every destructive action is simulated until deliberately going live.
- **Armed PIN lock** — PBKDF2-HMAC-SHA256 (120,000 rounds, per-install salt), 5-attempt lockout with exponential backoff on a monotonic clock; PIN and settings encrypted at rest in the Android Keystore.
- **Arming gate** — the watchdog can only be enabled while the PIN, notifications, Bluetooth-read access, and exact-alarm access are all usable; disabling is always allowed.
- **Reactive hardening** — on breach, re-asserts airplane mode and Device Owner restrictions.
- **Self-defense audit** — periodically verifies the Dhizuku Device Owner package, admin component, and signing certificate, pinning the app's own signature against compromise.
- **Block Debugging Features** — a single toggle governs ADB/developer-options enforcement; USB data transfer is always enforced.
- **Dashboard, Security Activity, Violations guide, and Settings** screens with a live threat-score hero meter, fail-closed Shield Status, and required-permission checker.
- **Paranoid Mode preset** — one tap enforces the strictest posture and arms the watchdog.
- **100% offline** — no `INTERNET` permission; nothing ever leaves the device.

[Unreleased]: https://github.com/heyaibi/airgate/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/heyaibi/airgate/releases/tag/v0.1.0