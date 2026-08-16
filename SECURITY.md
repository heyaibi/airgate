# Security Policy

Airgate is an offline watchdog for air-gapped Android devices. Report vulnerabilities privately — never in a public issue.

## Reporting a vulnerability

Do **not** open a public issue for a security problem, and never include real PINs, keystore material, signing keys, or any secret material anywhere public. Report privately via the repository's **Security tab → "Report a vulnerability"** button (GitHub's private vulnerability reporting) and include:

- Affected component (`app/`, the Dhizuku/Device Owner interop, or the wipe/hardening paths) and version.
- Android version and device model.
- A minimal reproduction, with any test data clearly marked as synthetic.
- Impact, and a suggested fix if you have one.

This is a personal, single-maintainer project: expect an acknowledgment within 5 working days, and a fix plus advisory for confirmed issues.

## Scope

- `app/` — the Android app (Kotlin/Compose): detectors, threat engine, PIN manager, and self-defense.
- `app/src/main/java/.../dhizuku/` — the Dhizuku Device Owner interop and privileged policy enforcement.
- `tools/` — the mockup/screenshot and emulator harness.

Out of scope are third-party dependencies and the operating system; report those to their owners.

## Reporting a non-security bug

Use the issue templates in `.github/ISSUE_TEMPLATE/`, and keep in mind the app's ground rules from `CONTRIBUTING.md`: no real secret material, no network, no persistence.
