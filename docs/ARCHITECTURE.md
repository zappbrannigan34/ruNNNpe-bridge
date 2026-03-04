# RUNN Bridge - Architecture

## Purpose

RUNN Bridge receives treadmill telemetry from NPE RUNN and writes structured workout data to Health Connect.

## Main components

- `MainActivity.kt`
  - One-time setup UI.
  - Permissions flow (BLE + notifications + Health Connect).
  - Profile fallback save and live telemetry view.
- `BleForegroundService.kt`
  - BLE scanning, connection, and reconnection logic.
  - RUNN stream ingestion (FTMS/RSC).
  - Automatic HR sensor discovery/reconnect in background.
  - Foreground notifications + telemetry broadcasts.
- `FtmsParser.kt`
  - Decodes FTMS/RSC payloads into typed snapshots.
- `WorkoutStateMachine.kt`
  - Detects workout start/end.
  - Keeps speed/incline/HR time-series and summary fields.
- `CalorieEngine.kt`
  - Segment-based calorie estimation (net/gross) with profile inputs.
- `HealthConnectWriter.kt`
  - Writes workout to Health Connect records.
  - Writes profile fallback values supported by HC.
- `BootReceiver.kt` + `BleScanReceiver.kt` + `SafeServiceStarter.kt` + `RunnCompanionService.kt`
  - Background resilience and restart paths.

## Data flow

1. RUNN sends BLE packets.
2. Service parses packets and updates state machine.
3. State machine emits live telemetry.
4. On workout finish, writer inserts Health Connect records.
5. Connected apps (including Fit) consume synced HC data.

## Health Connect records written

- `ExerciseSessionRecord`
- `ExerciseSegment` (walking/running treadmill)
- `SpeedRecord`
- `DistanceRecord`
- `StepsRecord`
- `ElevationGainedRecord`
- `ActiveCaloriesBurnedRecord`
- `TotalCaloriesBurnedRecord`
- `HeartRateRecord`
