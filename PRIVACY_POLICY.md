# Privacy Policy for ruNNNpe bridge

Last updated: 2026-03-05

`ruNNNpe bridge` is an Android app that reads workout context from connected BLE devices and writes workout data to Health Connect on your device.

## Data the app processes

- **Workout telemetry from BLE devices**: speed, incline, distance, and related training values.
- **Heart rate from BLE heart-rate sensors**: live and historical workout heart-rate samples used for workout export.
- **Profile values used for calculations**: weight, height, and other profile-related fitness values when available through Health Connect permissions.
- **Derived values**: workout duration, calories, and session metadata generated on-device.

## Where data is processed and stored

- Data processing happens on-device.
- The app writes workout records to **Health Connect** only after you grant the required permissions.
- The app does not include a cloud backend and does not transmit workout data to a developer-managed server.

## Permissions and why they are requested

- **Bluetooth permissions**: discover and connect to treadmill and heart-rate devices.
- **Foreground service and notification permissions**: keep tracking active while the app is in background.
- **Boot and battery-related permissions**: restore tracking behavior after reboot and improve reliability when the OS restricts background execution.
- **Health Connect permissions**: read profile-related values and write workout records.

## Data sharing

- The app does not sell personal data.
- The app does not use advertising SDKs.
- The app does not use third-party analytics or crash-reporting SDKs in the repository build.
- Data written to Health Connect can be shared by Health Connect with other apps only according to your Health Connect settings and permissions.

## Data retention and deletion

- Workout records written to Health Connect are retained according to Health Connect behavior and your actions.
- You can delete Health Connect records through Health Connect settings or compatible apps.
- Removing the app does not automatically delete data already written to Health Connect.

## Security

- The app relies on Android platform security and Health Connect permission controls.
- Sensitive health access is limited to the permissions declared in the app and granted by you.

## Your choices

- You can deny health permissions and Bluetooth permissions; app functionality will be limited.
- You can revoke permissions at any time in Android and Health Connect settings.
- You can stop tracking at any time from the app and notification controls.

## Policy updates

This policy may be updated when app behavior, dependencies, or publication requirements change. Material updates should be reflected in this file before release.

## Contact

For privacy questions, open an issue in the project repository:

- https://github.com/zappbrannigan34/ruNNNpe-bridge/issues
