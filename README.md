# RUNN Bridge

Android bridge between NPE RUNN and Google Health Connect.

## What it does

- Reads treadmill telemetry from RUNN over BLE in a foreground service.
- Detects workout start/finish automatically.
- Calculates calories (net/gross) with profile-aware formulas.
- Connects BLE heart-rate sensor automatically and writes HR samples.
- Writes workout records to Health Connect (session, speed, distance, steps, calories, HR, elevation gain).

## Current app flow

- Button 1: `Save Profile Fallback`.
- Button 2: `Find RUNN & Start`.
- HR sensor selection is automatic in background (no manual HR button).

## Quick start

1. Install Android Studio (JDK 17, SDK 34).
2. Open this project and sync Gradle.
3. Build debug APK:

```bat
gradlew.bat assembleDebug
```

4. Run on phone, grant BLE/notifications/Health Connect permissions.
5. Press `Find RUNN & Start` once for setup.

## Documentation

- Setup: `docs/SETUP.md`
- Architecture: `docs/ARCHITECTURE.md`
- Dependencies: `docs/DEPENDENCIES.md`
- Troubleshooting: `docs/TROUBLESHOOTING.md`
- CI/CD and release: `docs/CI_CD.md`

## GitHub Actions

- `android-build.yml`:
  - Trigger: push, pull_request, manual run.
  - Builds debug APK and uploads artifact.
- `publish.yml`:
  - Trigger: tag push (`v*`) or manual run.
  - Builds debug APK and publishes GitHub Release with APK attachment.
