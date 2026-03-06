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

## GitHub Actions build failed

- Check workflow logs in Actions tab.
- Common causes: Gradle cache corruption, transient dependency fetch errors.
- Re-run failed workflow once before deeper debugging.
