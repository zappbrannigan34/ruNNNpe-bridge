# Health Connect Coverage Matrix

This document tracks which Health Connect data types are currently written by `ruNNNpe bridge`, which inputs they depend on, and what is still intentionally out of scope.

## Current write coverage

| Health Connect type | Status | Source inputs | Notes |
|---|---|---|---|
| `ExerciseSessionRecord` | Written | workout start/end, inferred exercise type | Uses `RUNNING_TREADMILL` or `WALKING`. |
| `ExerciseSegment` | Written | inferred exercise type | Single segment covering full session. |
| `ExerciseRoute` | Written when route permission is granted | synthetic circular route + monotonic altitude profile derived from speed/incline | Used to improve Fit ascent profile rendering while avoiding real-world GPS tracking. |
| `SpeedRecord` | Written | speed samples | Chunked series write for long workouts. |
| `DistanceRecord` | Written | distance total | Sensor distance delta or speed integration fallback. |
| `StepsRecord` | Written | cadence-integrated steps, or distance fallback | Fallback uses personal step length when available. |
| `StepsCadenceRecord` | Written when available | cadence samples (RSC profile) | Not emitted if cadence data is absent. |
| `HeartRateRecord` | Written | HR samples (FTMS and/or HRM) | Chunked series write for long workouts. |
| `ElevationGainedRecord` | Written | speed + incline, optionally FTMS elevation-gain scaling | Written as dense interval chunks (positive gain only). |
| `FloorsClimbedRecord` | Written | elevation gain | Derived using meters-per-floor conversion. |
| `ActiveCaloriesBurnedRecord` | Written | calorie model (net) | From segmented ACSM calculation. |
| `TotalCaloriesBurnedRecord` | Written | calorie model (gross) | From segmented ACSM calculation. |
| `HeightRecord` | Written via profile sync | user profile | Manual profile sync path. |
| `BasalMetabolicRateRecord` | Written via profile sync | user profile | Manual profile sync path. |

## Health Connect backfill inputs used for computation

| Input read from HC | Purpose | Current use |
|---|---|---|
| `WeightRecord` | Calorie model profile | Read on startup/profile sync. |
| `HeightRecord` | Calorie model profile and BMI | Read on startup/profile sync. |
| `BasalMetabolicRateRecord` | Calorie model profile | Read on startup/profile sync. |
| `DistanceRecord` + `StepsRecord` | Personal step length inference | Read over lookback window, excluding this app's own origin, then cached for step fallback. |

## Explicitly not written right now

| Type | Reason |
|---|---|
| `Vo2MaxRecord` | No validated input/model in current app scope. |
| `PowerRecord` | No trustworthy power source/model for current treadmill pipeline. |

## Treadmill elevation chart note

- Exercise type (`WALKING` vs `RUNNING_TREADMILL`) does not provide altitude samples by itself.
- This bridge writes a synthetic circular `ExerciseRoute` with non-decreasing altitude to provide a profile for consumers that rely on route altitude.
- Route altitude and `ElevationGainedRecord` intervals are derived from the same cumulative gain timeline.
- If FTMS provides only total positive gain, the total is distributed across session windows before export.
- Elevation export also uses `FloorsClimbedRecord` totals.
- `WRITE_EXERCISE_ROUTE` permission should be granted for best Fit elevation chart interoperability.

## References

- Health Connect data types and permissions: <https://developer.android.com/health-and-fitness/health-connect/data-types>
- Exercise routes guide: <https://developer.android.com/health-and-fitness/health-connect/features/exercise-routes>
- `StepsCadenceRecord` API: <https://developer.android.com/reference/androidx/health/connect/client/records/StepsCadenceRecord>
- `ElevationGainedRecord` API: <https://developer.android.com/reference/androidx/health/connect/client/records/ElevationGainedRecord>
- `ExerciseRoute.Location` API: <https://developer.android.com/reference/android/health/connect/datatypes/ExerciseRoute.Location>
- Google Fit elevation note: <https://support.google.com/fit/answer/6075066?hl=en>
