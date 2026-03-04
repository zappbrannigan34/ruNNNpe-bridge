package com.example.runnbridge

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Velocity
import java.time.Instant
import java.time.ZoneId

object HealthConnectWriter {
    private const val TAG = "HCWriter"
    private const val RUNNING_MIN_MPS = 2.24

    suspend fun writeWorkout(ctx: Context, w: WorkoutStateMachine.WorkoutData) {
        val hc = HealthConnectClient.getOrCreate(ctx)
        val zone = ZoneId.systemDefault()
        val si = Instant.ofEpochMilli(w.startMs)
        val ei = Instant.ofEpochMilli(w.endMs)
        val so = zone.rules.getOffset(si)
        val eo = zone.rules.getOffset(ei)

        val profile = readProfileFromPrefs(ctx)
        val peakSpeedMps = w.speeds.maxOfOrNull { it.second.toDouble() } ?: w.lastSpeedMps.toDouble()
        val exerciseType = if (peakSpeedMps >= RUNNING_MIN_MPS) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
        } else {
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        }
        val segmentType = if (exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL) {
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL
        } else {
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_WALKING
        }

        val calories = CalorieEngine.estimateFromSamples(
            speedSamples = w.speeds,
            inclineSamples = w.inclines,
            startMs = w.startMs,
            endMs = w.endMs,
            profile = profile
        )
        val activelyRecordedMetadata = Metadata(
            device = Device(type = Device.TYPE_PHONE, manufacturer = "NPE", model = "RUNN Bridge"),
            recordingMethod = Metadata.RECORDING_METHOD_ACTIVELY_RECORDED
        )
        val elevationGainMeters = estimateElevationGainMeters(w)
        val sessionNotes = "RUNN Bridge auto-record; ${calories.equation}; elevGain=${"%.1f".format(elevationGainMeters)}m"

        val records = mutableListOf<Record>()

        records += ExerciseSessionRecord(
            startTime = si,
            startZoneOffset = so,
            endTime = ei,
            endZoneOffset = eo,
            exerciseType = exerciseType,
            title = "Treadmill (%.2f km)".format(w.distanceM / 1000),
            notes = sessionNotes,
            metadata = activelyRecordedMetadata,
            segments = listOf(
                ExerciseSegment(
                    startTime = si,
                    endTime = ei,
                    segmentType = segmentType
                )
            )
        )

        val speedSamples = w.speeds
            .filter { (ts, speed) -> speed > 0f && ts in w.startMs..w.endMs }
            .map { (ts, speed) ->
                SpeedRecord.Sample(
                    time = Instant.ofEpochMilli(ts),
                    speed = Velocity.metersPerSecond(speed.toDouble())
                )
            }
        if (speedSamples.size >= 2) {
            records += SpeedRecord(
                startTime = si,
                startZoneOffset = so,
                endTime = ei,
                endZoneOffset = eo,
                metadata = activelyRecordedMetadata,
                samples = speedSamples
            )
        }

        if (w.distanceM > 0) {
            records += DistanceRecord(
                startTime = si,
                startZoneOffset = so,
                endTime = ei,
                endZoneOffset = eo,
                metadata = activelyRecordedMetadata,
                distance = Length.meters(w.distanceM)
            )
        }

        if (elevationGainMeters > 0.0) {
            records += ElevationGainedRecord(
                startTime = si,
                startZoneOffset = so,
                endTime = ei,
                endZoneOffset = eo,
                metadata = activelyRecordedMetadata,
                elevation = Length.meters(elevationGainMeters)
            )
        }

        if (w.steps > 0) {
            records += StepsRecord(
                startTime = si,
                startZoneOffset = so,
                endTime = ei,
                endZoneOffset = eo,
                metadata = activelyRecordedMetadata,
                count = w.steps.toLong()
            )
        }

        if (calories.netCalories > 0.0) {
            records += ActiveCaloriesBurnedRecord(
                startTime = si,
                startZoneOffset = so,
                endTime = ei,
                endZoneOffset = eo,
                metadata = activelyRecordedMetadata,
                energy = Energy.kilocalories(calories.netCalories)
            )
        }

        if (calories.grossCalories > 0.0) {
            records += TotalCaloriesBurnedRecord(
                startTime = si,
                startZoneOffset = so,
                endTime = ei,
                endZoneOffset = eo,
                metadata = activelyRecordedMetadata,
                energy = Energy.kilocalories(calories.grossCalories)
            )
        }

        val heartRateSamples = w.heartRateSamples
            .filter { (ts, bpm) -> ts in w.startMs..w.endMs && bpm > 0L }
            .map { (ts, bpm) -> HeartRateRecord.Sample(time = Instant.ofEpochMilli(ts), beatsPerMinute = bpm) }
        if (heartRateSamples.isNotEmpty()) {
            records += HeartRateRecord(
                startTime = si,
                startZoneOffset = so,
                endTime = ei,
                endZoneOffset = eo,
                metadata = activelyRecordedMetadata,
                samples = heartRateSamples
            )
        }

        hc.insertRecords(records)
        Log.i(
            TAG,
            "✅ ${records.size} records written, peakSpeed=${"%.2f".format(peakSpeedMps)}m/s elevGain=${"%.1f".format(elevationGainMeters)}m net=${"%.1f".format(calories.netCalories)} gross=${"%.1f".format(calories.grossCalories)}"
        )
    }

    private fun estimateElevationGainMeters(w: WorkoutStateMachine.WorkoutData): Double {
        val speeds = w.speeds
            .filter { (ts, speed) -> ts in w.startMs..w.endMs && speed > 0f }
            .sortedBy { it.first }
        if (speeds.isEmpty()) return 0.0

        val inclines = w.inclines
            .filter { (ts, _) -> ts in w.startMs..w.endMs }
            .sortedBy { it.first }

        var inclineIdx = 0
        var currentInclinePercent = 0.0
        var gain = 0.0

        for (i in speeds.indices) {
            val (ts, speed) = speeds[i]
            while (inclineIdx < inclines.size && inclines[inclineIdx].first <= ts) {
                currentInclinePercent = inclines[inclineIdx].second.toDouble()
                inclineIdx++
            }

            val segmentEnd = if (i + 1 < speeds.size) {
                minOf(speeds[i + 1].first, w.endMs)
            } else {
                w.endMs
            }
            val dtMs = (segmentEnd - ts).coerceAtLeast(0L)
            if (dtMs <= 0L) continue

            val distanceMeters = speed.toDouble() * (dtMs / 1000.0)
            val grade = (currentInclinePercent / 100.0).coerceAtLeast(0.0)
            gain += distanceMeters * grade
        }

        return gain
    }

    suspend fun syncManualProfile(
        context: Context,
        ageYears: Int?,
        heightCm: Double?,
        sex: BiologicalSex,
        weightKg: Double
    ) {
        val records = mutableListOf<Record>()
        val now = Instant.now()
        val zoneOffset = ZoneId.systemDefault().rules.getOffset(now)

        if (heightCm != null && heightCm > 0.0) {
            records += HeightRecord(
                time = now,
                zoneOffset = zoneOffset,
                height = Length.meters(heightCm / 100.0)
            )
        }

        val bmrWatts = calculateBmrWatts(ageYears, heightCm, sex, weightKg)
        if (bmrWatts != null && bmrWatts > 0.0) {
            records += BasalMetabolicRateRecord(
                time = now,
                zoneOffset = zoneOffset,
                basalMetabolicRate = Power.watts(bmrWatts)
            )
        }

        if (records.isNotEmpty()) {
            HealthConnectClient.getOrCreate(context).insertRecords(records)
            Log.i(TAG, "Profile records synced: ${records.size}")
        }
    }

    private fun calculateBmrWatts(
        ageYears: Int?,
        heightCm: Double?,
        sex: BiologicalSex,
        weightKg: Double
    ): Double? {
        if (ageYears == null || ageYears <= 0) return null
        if (heightCm == null || heightCm <= 0.0) return null
        if (weightKg <= 0.0) return null
        if (sex == BiologicalSex.UNKNOWN) return null

        val reeKcalDay = when (sex) {
            BiologicalSex.MALE -> (10.0 * weightKg) + (6.25 * heightCm) - (5.0 * ageYears) + 5.0
            BiologicalSex.FEMALE -> (10.0 * weightKg) + (6.25 * heightCm) - (5.0 * ageYears) - 161.0
            BiologicalSex.UNKNOWN -> return null
        }
        return reeKcalDay * 4184.0 / 86400.0
    }

    private fun readProfileFromPrefs(context: Context): UserProfile {
        val prefs = context.getSharedPreferences("runn", Context.MODE_PRIVATE)
        val weight = prefs.getFloat("weight_kg", 0f).toDouble().takeIf { it > 0.0 } ?: 70.0
        val height = prefs.getFloat("height_cm", 0f).toDouble().takeIf { it > 0.0 }
        val age = prefs.getInt("age_years", 0).takeIf { it > 0 }
        val sex = when (prefs.getString("sex", BiologicalSex.UNKNOWN.name)) {
            BiologicalSex.MALE.name -> BiologicalSex.MALE
            BiologicalSex.FEMALE.name -> BiologicalSex.FEMALE
            else -> BiologicalSex.UNKNOWN
        }
        val bmrWatts = prefs.getFloat("bmr_watts", 0f).toDouble().takeIf { it > 0.0 }

        return UserProfile(
            weightKg = weight,
            heightCm = height,
            ageYears = age,
            sex = sex,
            bmrWatts = bmrWatts
        )
    }
}
