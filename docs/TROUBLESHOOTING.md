# Troubleshooting

## App is killed in background

- Set battery mode to `Unrestricted` / `No limits`.
- Enable autostart for RUNN Bridge.
- Keep notifications enabled for foreground service.

### Xiaomi

- Settings -> Apps -> RUNN Bridge -> Battery -> No restrictions.
- Security -> Autostart -> RUNN Bridge -> ON.

### Samsung

- Settings -> Battery -> Background usage limits.
- Exclude RUNN Bridge from sleeping apps.

### Pixel / Stock Android

- Settings -> Apps -> RUNN Bridge -> Battery -> Unrestricted.

## RUNN is not found

- Make sure treadmill and RUNN are powered on.
- Check Bluetooth is enabled.
- Ensure RUNN is not connected to another phone.
- Reopen app and press `Find RUNN & Start`.

## HR sensor is not connected

- HR sensor connects automatically in background.
- Wear the sensor before starting workout.
- If disconnected, service retries automatically.
- If needed, stop/start workout once to trigger fresh scan.

## Health Connect write errors

- Open app and re-grant Health Connect permissions.
- Confirm Health Connect app is installed and updated.
- Verify `WRITE_*` permissions are present in granted set.

## GitHub Actions build failed

- Check workflow logs in Actions tab.
- Common causes: Gradle cache corruption, transient dependency fetch errors.
- Re-run failed workflow once before deeper debugging.
