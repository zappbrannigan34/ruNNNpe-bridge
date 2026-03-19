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
- On app updates, missing required Health Connect permissions are re-requested automatically on app start.
- If Android suppresses the permission prompt, the app now opens Health Connect settings as recovery fallback.
- Treadmill export now intentionally omits `ExerciseRoute` to avoid consumer terrain/map side effects; route permission is not required for core sync.

## Elevation graph missing or map looks wrong

- For treadmill sessions, the bridge does not export a map route; this is expected.
- Elevation sync is written via dense positive-only `ElevationGainedRecord` intervals plus derived `FloorsClimbedRecord`.
- If Fit still shows no ascent data, verify Health Connect write permissions for `ElevationGainedRecord` and `FloorsClimbedRecord`, then rerun a short workout.
- If a map appears in Fit for that workout, the route likely comes from another data origin, not this bridge export.

## App asks for permissions or Bluetooth again

- The app re-checks required permissions automatically after updates and periodically during normal use.
- If any required permission is missing, it is requested again.
- If `Pause app activity if unused` is enabled, Android can revoke granted permissions later; the app now prompts to disable this inactive-app restriction on startup/update.
- Route-anchor location permission is no longer requested for treadmill export.
- If Bluetooth is off, Android Bluetooth enable dialog is requested automatically.

## Notification numbers differ from app screen metrics

- Live telemetry and final workout summary are broadcast to the activity and saved to telemetry prefs.
- If mismatch appears after an update, install the latest pre-release APK and retry one short workout.
- If issue persists, open app once while workout is active to ensure telemetry receiver refreshes the on-screen metrics.
- Heart-rate value is intentionally cleared when HR BLE disconnects; `HR state: disconnected` should no longer keep stale current BPM on screen.

## Speed is stuck at 0.0 km/h

- Install the latest pre-release APK; FTMS speed parsing was hardened for treadmill variants that expose different flag layouts.
- If RUNN is connected but speed remains `0.0`, share a fresh app log section around `Profile FTMS` and `Workout started`.

## GitHub Actions build failed

- Check workflow logs in Actions tab.
- Common causes: Gradle cache corruption, transient dependency fetch errors.
- Re-run failed workflow once before deeper debugging.

## "Package invalid" when installing pre-release APK

- Install `ruNNNpe bridge-release.apk` from pre-release for normal testing.
- Do not install `.aab` on device (`.aab` is Play upload format).
- If installation still fails after updates, uninstall older build with conflicting signature and install again.
