# ruNNNpe bridge - Architecture

## Purpose

ruNNNpe bridge receives treadmill telemetry from NPE RUNN and writes structured workout data to Health Connect.

## Main components

- `MainActivity.kt`
  - One-time setup UI.
  - First-launch setup orchestration (BLE + Health Connect + notifications + battery optimization exemption request).
  - Profile fallback save and live telemetry view.
  - Top metrics block shows RUNN/HR sensor selection and connected/disconnected state.
  - Profile backfill from Health Connect (weight, height, BMR).
  - Personal step-length inference from Health Connect distance+steps history.
- `BleForegroundService.kt`
  - BLE scanning, connection, and reconnection logic.
  - Uses low-latency scan mode with short retry interval for faster RUNN/HR discovery.
  - Persists RUNN/HR connection state and RUNN profile for UI status indicators.
  - RUNN stream ingestion (FTMS/RSC).
  - Automatic HR sensor discovery/reconnect in background.
  - Foreground notifications + telemetry broadcasts.
- `FtmsParser.kt`
  - Decodes FTMS/RSC payloads into typed snapshots.
  - Parses FTMS positive/negative elevation gain fields when available.
- `WorkoutStateMachine.kt`
  - Detects workout start/end.
  - Keeps speed/incline/HR time-series and summary fields.
  - Applies bounded telemetry sampling (time gate + delta thresholds).
  - Tracks FTMS positive elevation gain delta for end-of-workout export.
- `CalorieEngine.kt`
  - Segment-based calorie estimation (net/gross) with profile inputs.
- `HealthConnectWriter.kt`
  - Writes workout to Health Connect records.
  - Writes series records in chunks to avoid Health Connect record size limits.
  - Writes a virtual treadmill `ExerciseRoute` with altitude profile when route permission is granted.
  - Uses fallback step estimation when cadence-based steps are missing.
  - Uses HC-derived personal step length when available.
  - Writes floors climbed from elevation gain when permission is granted.
  - Writes profile fallback values supported by HC.
- `BootReceiver.kt` + `BleScanReceiver.kt` + `SafeServiceStarter.kt` + `RunnCompanionService.kt`
  - Background resilience and restart paths.

## Data flow

1. RUNN sends BLE packets.
2. Service parses packets and updates state machine.
3. State machine emits live telemetry.
4. On workout finish, writer inserts Health Connect records.
5. Connected apps (including Fit) consume synced HC data.

## Profile backfill flow

1. App reads profile records from Health Connect on startup.
2. If steps+distance read permissions are granted, app reads recent non-self-origin records.
3. App infers personal step length and stores it in local prefs.
4. Workout writer uses this value for step fallback estimation when direct steps are unavailable.

## Health Connect records written

- `ExerciseSessionRecord`
- `ExerciseSegment` (walking/running treadmill)
- `ExerciseRoute` (virtual treadmill loop with altitude profile, permission-gated)
- `SpeedRecord`
- `DistanceRecord`
- `StepsRecord`
- `ElevationGainedRecord`
- `FloorsClimbedRecord` (permission-gated)
- `ActiveCaloriesBurnedRecord`
- `TotalCaloriesBurnedRecord`
- `HeartRateRecord`
- `StepsCadenceRecord` (when cadence samples are available, typically RSC profile)

## Known consumer limitations

- Health Connect has no dedicated incline data type; incline is represented indirectly (calories/elevation/floors).
- Elevation profile charts in consumer apps usually require route altitude points (`ExerciseRoute`).
- For treadmill workouts, the app writes a synthetic local route to provide route altitude points; if route permission is denied, only elevation summary records (`ElevationGainedRecord`, `FloorsClimbedRecord`) are available.
