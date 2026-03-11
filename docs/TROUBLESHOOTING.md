# Troubleshooting

## App is killed in background

- Set battery mode to `Unrestricted` / `No limits`.
- Enable autostart for ruNNNpe bridge.
- Keep notifications enabled for foreground service.

### Xiaomi

- Settings -> Apps -> ruNNNpe bridge -> Battery -> No restrictions.
- Security -> Autostart -> ruNNNpe bridge -> ON.

### Samsung

- Settings -> Battery -> Background usage limits.
- Exclude ruNNNpe bridge from sleeping apps.

### Pixel / Stock Android

- Settings -> Apps -> ruNNNpe bridge -> Battery -> Unrestricted.

## RUNN is not found

- Make sure treadmill and RUNN are powered on.
- Check Bluetooth is enabled.
- Ensure RUNN is not connected to another phone.
- Reopen app and press `Find RUNN & Start`.
- In the app metrics block, verify `RUNN state` switches to `connected` after pairing.

## HR sensor is not connected

- HR sensor connects automatically in background.
- Wear the sensor before starting workout.
- If disconnected, service retries automatically.
- Discovery retries run frequently (short interval, low-latency scan mode), but initial pairing can still take a few scan windows depending on sensor advertising.
- If needed, stop/start workout once to trigger fresh scan.
- If app keeps picking the wrong HR sensor, clear selected sensor and reselect the correct one.
- In the app metrics block, verify `HR state` switches to `connected` when notifications start.

## Health Connect write errors

- Open app and re-grant Health Connect permissions.
- Confirm Health Connect app is installed and updated.
- Verify required `WRITE_*` permissions are granted for records you expect to sync.
- Verify required `READ_*` permissions are granted for profile/backfill paths (for example distance+steps for personal step-length inference).
- After permission model changes between app versions, revoke + grant permissions once to refresh the granted set.
- `WRITE_EXERCISE_ROUTE` is optional for core sync; missing it should not block workout export, but can reduce elevation chart interoperability.

## Elevation graph missing or map looks wrong

- For treadmill sessions, elevation profile in consumer apps depends on `ExerciseRoute` availability, not only `ElevationGainedRecord`.
- Grant Health Connect route permission (`WRITE_EXERCISE_ROUTE`) and rerun one workout.
- Optional location permission (`ACCESS_COARSE_LOCATION`) helps anchor virtual route near your training place.
- If location permission is denied, the app still exports a virtual route using cached/default anchor, so charts can render but map placement may be generic.

## App asks for permissions or Bluetooth again

- The app re-checks required permissions automatically after updates and periodically during normal use.
- If any required permission is missing, it is requested again.
- If Bluetooth is off, Android Bluetooth enable dialog is requested automatically.

## Notification numbers differ from app screen metrics

- Live telemetry and final workout summary are broadcast to the activity and saved to telemetry prefs.
- If mismatch appears after an update, install the latest pre-release APK and retry one short workout.
- If issue persists, open app once while workout is active to ensure telemetry receiver refreshes the on-screen metrics.

## GitHub Actions build failed

- Check workflow logs in Actions tab.
- Common causes: Gradle cache corruption, transient dependency fetch errors.
- Re-run failed workflow once before deeper debugging.

## "Package invalid" when installing pre-release APK

- Install `ruNNNpe bridge-release.apk` from pre-release for normal testing.
- Do not install `.aab` on device (`.aab` is Play upload format).
- If installation still fails after updates, uninstall older build with conflicting signature and install again.
