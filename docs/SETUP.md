# ruNNNpe bridge - Setup

## Requirements

- Android Studio (Hedgehog or newer)
- JDK 17
- Android SDK 35
- Android device with BLE (Android 10+)
- Health Connect installed and available

## Local build

```bat
gradlew.bat assembleDebug
```

```bash
./gradlew assembleDebug
```

## Release build

```bash
./gradlew assembleRelease bundleRelease
```

APK output:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release-unsigned.apk` (default local release without signing secrets)
- `app/build/outputs/apk/release/app-release.apk` (when release signing env vars are provided)
- `app/build/outputs/bundle/release/app-release.aab`

If `gradlew` is not executable on Linux/macOS:

```bash
chmod +x gradlew
```

## Run on device

1. Enable Bluetooth.
2. Install the debug APK.
3. Open the app.
4. On first launch, grant all requested permissions (BLE, Health Connect core permissions, notifications; optional route write and optional location for virtual route anchor) and allow battery optimization exemption.
5. Tap `Find RUNN & Start`.

The app also runs automatic maintenance checks on updates and periodically while in use:

- Re-checks required BLE + Health Connect core + notification permissions.
- Re-requests missing permissions.
- Requests Bluetooth enable when Bluetooth is off.

## First-time configuration

- `Save Profile Fallback` stores manual profile values if Health Connect profile data is missing.
- HR sensor is discovered automatically in background when workout starts.
- After setup, the foreground service keeps monitoring in background.

## Xiaomi recommended settings

- App -> Battery -> No restrictions.
- Autostart -> Enable for ruNNNpe bridge.
- Keep notifications enabled and pin app in recent apps.
