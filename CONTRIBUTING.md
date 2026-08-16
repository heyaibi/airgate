# Contributing to Airgate

Thanks for your interest. Airgate is a personal, security-critical tool: it is an offline watchdog for dedicated crypto cold-storage Android devices and never touches the network. Please read this before opening a PR, and follow the same discipline the app itself does.

## The ground rules

- **No secrets in the repo, ever.** No real PINs, keystore material, signing keys, or device identifiers. Tests must use synthetic data or publicly published vectors.
- **No network.** The app must stay fully offline. Never add a permission that touches the network (there is deliberately no `INTERNET` permission in the manifest), and never persist secret material to disk or saved state.
- **Respect the threat model.** Anything that stores, copies, or displays secret material must preserve the existing guards: `FLAG_SECURE` on the app's windows, the PBKDF2-HMAC-SHA256 PIN derivation with per-install salt and lockout backoff, and `allowBackup="false"`.
- **Markdown rule.** In docs, newlines are structural only — one paragraph per line, one bullet per line. Never hard-wrap prose.

## Prerequisites

- JDK 21+ (the Makefile defaults to Android Studio's bundled JBR on macOS).
- Android SDK with platform 37 (set `ANDROID_HOME` or `local.properties`).

## Setup and build

```bash
./gradlew :app:assembleDebug
```

## Running the checks

`make help` lists every target. The gate that must be green before a PR merges:

| Command | What it checks |
|---|---|
| `make unit` | 45 JVM unit tests (PIN manager, threat engine, detectors, policy/Dhizuku, repositories, integration) |
| `make lint` | Android lint, clean |
| `make build` | debug APK assembles |
| `make android-test` | instrumented tests on a device or the `s4_dev` emulator |

`make verify` runs `unit`, `lint`, and `build` in sequence. CI mirrors them: the `checks` job (unit + lint + build) and the `instrumented` job (emulator) must both pass.

## Release build

The release APK is signed with a keystore whose credentials live in `~/.gradle/gradle.properties` (`AG_RELEASE_STORE_FILE`, `AG_RELEASE_STORE_PASSWORD`, `AG_RELEASE_KEY_ALIAS`, `AG_RELEASE_KEY_PASSWORD`) — never in the repo. Build it with `make release` (or `./gradlew :app:assembleRelease`), which produces an unsigned or signed, unminified APK at `app/build/outputs/apk/release/app-release.apk` and prints its SHA-256. `make verify-release` additionally audits that the release APK has no `INTERNET` permission and checks the signing certificate hash baked in for self-defense. Back the keystore up offline: Android apps are permanently bound to their signing key, so losing it makes future updates impossible.

## Versioning

Airgate follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Both values live in `gradle.properties` (`AG_VERSION_NAME`, `AG_VERSION_CODE`) and are read by `app/build.gradle.kts` — bump them there, never in the build file.

- **`AG_VERSION_NAME`** (what users see) is `MAJOR.MINOR.PATCH`. Before 1.0.0, breaking changes bump the minor (the ecosystem treats `0.x` minors as potentially breaking). After 1.0.0: breaking changes bump the major, new backward-compatible behavior the minor, bug fixes the patch.
- **`AG_VERSION_CODE`** (what Android uses to order updates) is a positive integer that starts at 1 and increases by exactly 1 on every release, whatever the `versionName` delta. Never reuse or skip it — Android refuses to install an APK whose `versionCode` is lower than the installed one, so a reused code silently blocks upgrades.

Every release:

1. Bump `AG_VERSION_CODE` (always) and `AG_VERSION_NAME` (when something user-visible changed) in `gradle.properties`.
2. Build with `make verify-release`, record the printed SHA-256, and smoke-test the signed APK on the target phone.
3. Tag the release commit `v<versionName>` and push the tag.
4. Write the `CHANGELOG.md` entry for the version (Keep a Changelog format, `Unreleased` section at the top) just before the release, and create a GitHub Release for the tag with the APK, its SHA-256, and that entry.

## Code style

- Kotlin, following the existing conventions: Jetpack Compose + Material 3, screens under `ui/screens/`, shared components in `ui/components/` (`SectionLabel`, `SettingsCard`, `PrimaryActionButton`, ...), and theme in `ui/theme/Theme.kt`.
- Keep the detectors and the policy/Dhizuku layer thin: detector classes report discrete signals, `ThreatEngine` does the weighted tiered accounting, and `DevicePolicyEnforcer`/Dhizuku is the only place that talks to the device-policy machinery.
- Name things as they are named in the domain (watchdog, breach, threat score, wipe, hardening). No unrelated renames in the same PR.

## Testing

- Add or update unit tests for any core logic change; the suite must stay green and ideally grow.
- Policy and detection changes must keep the offline, zero-network posture tests green (`Detector`/`Policy`/`ThreatEngine` suites), and wipe paths should be covered by `DhizukuAndPolicyTest` or the integration suite.
- UI changes should extend the existing screen or component tests, and the error paths (wrong PIN, lockout, breached posture) must be covered.
- Never disable or relax a test to make CI green.

## Submitting a PR

1. Work on a topic branch off `master` with a descriptive name.
2. Keep the change focused; one logical change per PR.
3. Run `make verify` locally (or the CI `checks` + `instrumented` jobs on your branch) and make sure everything passes.
4. Write a concise, imperative commit message that matches the repo style (e.g. `feat: ...`, `fix: ...`, `refactor: ...`).
5. Open the PR against `master` and summarize what changed and why, including how you verified it.
