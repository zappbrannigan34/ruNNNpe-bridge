# Health Connect Coverage Matrix

This document tracks which Health Connect data types are currently written by `ruNNNpe bridge`, which inputs they depend on, and what is still intentionally out of scope.

## Current write coverage

| Health Connect type | Status | Source inputs | Notes |
|---|---|---|---|
| `ExerciseSessionRecord` | Written | workout start/end, inferred exercise type | Uses `RUNNING_TREADMILL` or `WALKING`. |
| `ExerciseSegment` | Written | inferred exercise type | Single segment covering full session. |
| `ExerciseRoute` | Written when route permission is granted | virtual loop route near cached/current anchor point + computed altitude profile | Synthetic route is used for treadmill interoperability with consumer elevation charts. |
| `SpeedRecord` | Written | speed samples | Chunked series write for long workouts. |
| `DistanceRecord` | Written | distance total | Sensor distance delta or speed integration fallback. |
| `StepsRecord` | Written | cadence-integrated steps, or distance fallback | Fallback uses personal step length when available. |
| `StepsCadenceRecord` | Written when available | cadence samples (RSC profile) | Not emitted if cadence data is absent. |
| `HeartRateRecord` | Written | HR samples (FTMS and/or HRM) | Chunked series write for long workouts. |
| `ElevationGainedRecord` | Written | speed + incline, optionally FTMS elevation-gain scaling | Written as interval chunks. |
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
- Elevation profile charts in consumer apps generally rely on route points with altitude (`ExerciseRoute.Location.altitude`).
- The app now writes a virtual treadmill route (when `WRITE_EXERCISE_ROUTE` permission is granted) so consumer apps can render an elevation profile even without physical movement.
- If route permission is missing, summary metrics still sync (`ElevationGainedRecord`, `FloorsClimbedRecord`), while chart rendering remains app-dependent.

## References

- Health Connect data types and permissions: <https://developer.android.com/health-and-fitness/health-connect/data-types>
- `StepsCadenceRecord` API: <https://developer.android.com/reference/androidx/health/connect/client/records/StepsCadenceRecord>
- `ElevationGainedRecord` API: <https://developer.android.com/reference/androidx/health/connect/client/records/ElevationGainedRecord>
- Exercise routes guide: <https://developer.android.com/health-and-fitness/guides/health-connect/develop/exercise-routes>
- `ExerciseRoute.Location` API: <https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/ExerciseRoute.Location>
- Google Fit elevation note: <https://support.google.com/fit/answer/6075066?hl=en>
