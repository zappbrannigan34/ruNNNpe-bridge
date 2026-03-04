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
- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/bundle/release/app-release.aab`

If `gradlew` is not executable on Linux/macOS:

```bash
chmod +x gradlew
```

## Run on device

1. Enable Bluetooth.
2. Install the debug APK.
3. Open the app.
4. Grant all required permissions (BLE, notifications, Health Connect).
5. Tap `Find RUNN & Start`.

## First-time configuration

- `Save Profile Fallback` stores manual profile values if Health Connect profile data is missing.
- HR sensor is discovered automatically in background when workout starts.
- After setup, the foreground service keeps monitoring in background.

## Xiaomi recommended settings

- App -> Battery -> No restrictions.
- Autostart -> Enable for ruNNNpe bridge.
- Keep notifications enabled and pin app in recent apps.
