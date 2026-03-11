package com.example.runnbridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

object HealthConnectWriter {
    private const val TAG = "HCWriter"
    private const val RUNNING_MIN_MPS = 2.24
    private const val SERIES_RECORD_WINDOW_MS = 60_000L
    private const val MAX_SAMPLES_PER_SERIES_RECORD = 120
    private const val MAX_RECORDS_PER_INSERT = 900
    private const val DEFAULT_STEP_LENGTH_METERS = 0.78
    private const val METERS_PER_FLOOR = 3.048
    private const val METERS_PER_DEGREE_LAT = 111_320.0
    private const val VIRTUAL_ROUTE_BASE_SAMPLE_MS = 10_000L
    private const val VIRTUAL_ROUTE_MAX_POINTS = 600
    private const val VIRTUAL_ROUTE_LOOP_RADIUS_METERS = 20.0
    private const val VIRTUAL_ROUTE_HORIZONTAL_ACCURACY_M = 5.0
    private const val VIRTUAL_ROUTE_VERTICAL_ACCURACY_M = 2.0
    private const val VIRTUAL_ROUTE_FALLBACK_LAT = 0.0
    private const val VIRTUAL_ROUTE_FALLBACK_LON = 0.0
    private const val VIRTUAL_ROUTE_FALLBACK_ALT_M = 20.0
    private const val WRITE_EXERCISE_ROUTE_PERMISSION = "android.permission.health.WRITE_EXERCISE_ROUTE"
    private const val PREF_STEP_LENGTH_M = "step_length_m"
    private const val PREF_ROUTE_ANCHOR_LAT = "route_anchor_lat"
    private const val PREF_ROUTE_ANCHOR_LON = "route_anchor_lon"
    private const val PREF_ROUTE_ANCHOR_ALT = "route_anchor_alt"

    private data class RouteAnchor(
        val latitude: Double,
        val longitude: Double,
        val altitudeMeters: Double
    )

    suspend fun writeWorkout(ctx: Context, w: WorkoutStateMachine.WorkoutData) {
        val hc = HealthConnectClient.getOrCreate(ctx)
        val grantedPermissions = hc.permissionController.getGrantedPermissions()
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
            device = Device(type = Device.TYPE_PHONE, manufacturer = "NPE", model = "ruNNNpe bridge"),
            recordingMethod = Metadata.RECORDING_METHOD_ACTIVELY_RECORDED
        )
        val elevationRecords = buildElevationRecords(
            workout = w,
            zoneId = zone,
            metadata = activelyRecordedMetadata,
            ftmsPositiveElevationGainM = w.ftmsPositiveElevationGainM
        )
        val elevationGainMeters = elevationRecords.sumOf { it.elevation.inMeters }
        val sessionNotes = "ruNNNpe bridge auto-record; ${calories.equation}; elevGain=${"%.1f".format(elevationGainMeters)}m"
        val preferredStepLengthMeters = readStepLengthFromPrefs(ctx)
        val (stepsToWrite, stepsEstimated) = resolveStepCount(w, profile, preferredStepLengthMeters)
        val floorsRecord = buildFloorsRecord(
            startTime = si,
            startZoneOffset = so,
            endTime = ei,
            endZoneOffset = eo,
            metadata = activelyRecordedMetadata,
            elevationGainMeters = elevationGainMeters,
            hasPermission = grantedPermissions.contains(HealthPermission.getWritePermission(FloorsClimbedRecord::class))
        )
        val exerciseRoute = buildVirtualExerciseRoute(
            context = ctx,
            workout = w,
            hasPermission = grantedPermissions.contains(WRITE_EXERCISE_ROUTE_PERMISSION)
        )

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
            exerciseRoute = exerciseRoute,
            segments = listOf(
                ExerciseSegment(
                    startTime = si,
                    endTime = ei,
                    segmentType = segmentType
                )
            )
        )

        val speedRecords = buildSpeedRecords(
            samples = w.speeds,
            startMs = w.startMs,
            endMs = w.endMs,
            zoneId = zone,
            metadata = activelyRecordedMetadata
        )
        records += speedRecords

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

        records += elevationRecords

        if (stepsToWrite > 0) {
            records += StepsRecord(
                startTime = si,
                startZoneOffset = so,
                endTime = ei,
                endZoneOffset = eo,
                metadata = activelyRecordedMetadata,
                count = stepsToWrite
            )
        }

        floorsRecord?.let { records += it }

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

        val heartRateRecords = buildHeartRateRecords(
            samples = w.heartRateSamples,
            startMs = w.startMs,
            endMs = w.endMs,
            zoneId = zone,
            metadata = activelyRecordedMetadata
        )
        records += heartRateRecords

        val cadenceRecords = buildStepsCadenceRecords(
            samples = w.cadenceSamples,
            startMs = w.startMs,
            endMs = w.endMs,
            zoneId = zone,
            metadata = activelyRecordedMetadata
        )
        records += cadenceRecords

        Log.i(
            TAG,
            "Prepared records total=${records.size} speedChunks=${speedRecords.size} hrChunks=${heartRateRecords.size} cadenceChunks=${cadenceRecords.size} elevChunks=${elevationRecords.size} steps=${stepsToWrite}${if (stepsEstimated) "(estimated)" else ""} floors=${floorsRecord?.floors ?: 0.0} route=${exerciseRoute?.route?.size ?: 0}"
        )

        insertInBatchesWithRetry(hc, records)
        Log.i(
            TAG,
            "✅ ${records.size} records written, peakSpeed=${"%.2f".format(peakSpeedMps)}m/s elevGain=${"%.1f".format(elevationGainMeters)}m net=${"%.1f".format(calories.netCalories)} gross=${"%.1f".format(calories.grossCalories)} route=${exerciseRoute?.route?.size ?: 0}"
        )
    }

    private fun buildVirtualExerciseRoute(
        context: Context,
        workout: WorkoutStateMachine.WorkoutData,
        hasPermission: Boolean
    ): ExerciseRoute? {
        if (!hasPermission) return null
        if (workout.endMs <= workout.startMs) return null

        val anchor = resolveRouteAnchor(context)
        val durationMs = (workout.endMs - workout.startMs).coerceAtLeast(1L)
        val dynamicSampleIntervalMs = maxOf(
            VIRTUAL_ROUTE_BASE_SAMPLE_MS,
            (durationMs + VIRTUAL_ROUTE_MAX_POINTS - 2L) / (VIRTUAL_ROUTE_MAX_POINTS - 1L)
        )
        val routeLoopCircumferenceMeters = 2.0 * PI * VIRTUAL_ROUTE_LOOP_RADIUS_METERS

        val speedSamples = workout.speeds
            .filter { (ts, _) -> ts in workout.startMs..workout.endMs }
            .sortedBy { it.first }
            .ifEmpty { listOf(workout.startMs to workout.lastSpeedMps) }

        val inclineSamples = workout.inclines
            .filter { (ts, _) -> ts in workout.startMs..workout.endMs }
            .sortedBy { it.first }
            .ifEmpty { listOf(workout.startMs to workout.lastInclinePercent) }

        var speedIdx = speedSamples.indexOfLast { it.first <= workout.startMs }.coerceAtLeast(0)
        var inclineIdx = inclineSamples.indexOfLast { it.first <= workout.startMs }.coerceAtLeast(0)

        val route = mutableListOf<ExerciseRoute.Location>()
        route +=
            buildVirtualRouteLocation(
                timeMs = workout.startMs,
                anchor = anchor,
                loopDistanceMeters = 0.0,
                relativeAltitudeMeters = 0.0,
                loopCircumferenceMeters = routeLoopCircumferenceMeters
            )

        var loopDistanceMeters = 0.0
        var relativeAltitudeMeters = 0.0
        var cursor = workout.startMs

        while (cursor < workout.endMs && route.size < VIRTUAL_ROUTE_MAX_POINTS) {
            while (speedIdx + 1 < speedSamples.size && speedSamples[speedIdx + 1].first <= cursor) {
                speedIdx++
            }
            while (inclineIdx + 1 < inclineSamples.size && inclineSamples[inclineIdx + 1].first <= cursor) {
                inclineIdx++
            }

            val nextMs = minOf(cursor + dynamicSampleIntervalMs, workout.endMs)
            val deltaSeconds = (nextMs - cursor) / 1000.0
            val speedMps = speedSamples[speedIdx].second.toDouble().coerceAtLeast(0.0)
            val grade = (inclineSamples[inclineIdx].second.toDouble() / 100.0).coerceIn(-0.25, 0.25)
            val horizontalMeters = speedMps * deltaSeconds
            loopDistanceMeters += horizontalMeters
            relativeAltitudeMeters += horizontalMeters * grade

            route +=
                buildVirtualRouteLocation(
                    timeMs = nextMs,
                    anchor = anchor,
                    loopDistanceMeters = loopDistanceMeters,
                    relativeAltitudeMeters = relativeAltitudeMeters,
                    loopCircumferenceMeters = routeLoopCircumferenceMeters
                )

            cursor = nextMs
        }

        if (route.last().time != Instant.ofEpochMilli(workout.endMs)) {
            route +=
                buildVirtualRouteLocation(
                    timeMs = workout.endMs,
                    anchor = anchor,
                    loopDistanceMeters = loopDistanceMeters,
                    relativeAltitudeMeters = relativeAltitudeMeters,
                    loopCircumferenceMeters = routeLoopCircumferenceMeters
                )
        }

        return route.takeIf { it.size >= 2 }?.let { ExerciseRoute(it) }
    }

    private fun buildVirtualRouteLocation(
        timeMs: Long,
        anchor: RouteAnchor,
        loopDistanceMeters: Double,
        relativeAltitudeMeters: Double,
        loopCircumferenceMeters: Double
    ): ExerciseRoute.Location {
        val angleRad = (loopDistanceMeters / loopCircumferenceMeters) * 2.0 * PI
        val northMeters = sin(angleRad) * VIRTUAL_ROUTE_LOOP_RADIUS_METERS
        val eastMeters = cos(angleRad) * VIRTUAL_ROUTE_LOOP_RADIUS_METERS

        val latitude = (anchor.latitude + northMeters / METERS_PER_DEGREE_LAT).coerceIn(-89.999999, 89.999999)
        val metersPerDegreeLon = (METERS_PER_DEGREE_LAT * cos(Math.toRadians(anchor.latitude))).coerceAtLeast(1.0)
        val longitude = normalizeLongitude(anchor.longitude + eastMeters / metersPerDegreeLon)
        val altitudeMeters = (anchor.altitudeMeters + relativeAltitudeMeters).coerceIn(-500.0, 9000.0)

        return ExerciseRoute.Location(
            time = Instant.ofEpochMilli(timeMs),
            latitude = latitude,
            longitude = longitude,
            horizontalAccuracy = Length.meters(VIRTUAL_ROUTE_HORIZONTAL_ACCURACY_M),
            verticalAccuracy = Length.meters(VIRTUAL_ROUTE_VERTICAL_ACCURACY_M),
            altitude = Length.meters(altitudeMeters)
        )
    }

    private fun normalizeLongitude(longitude: Double): Double {
        var value = longitude
        while (value > 180.0) value -= 360.0
        while (value < -180.0) value += 360.0
        return value
    }

    private fun resolveRouteAnchor(context: Context): RouteAnchor {
        readLastKnownRouteAnchor(context)?.let {
            saveRouteAnchor(context, it)
            return it
        }

        readSavedRouteAnchor(context)?.let { return it }
        return RouteAnchor(
            latitude = VIRTUAL_ROUTE_FALLBACK_LAT,
            longitude = VIRTUAL_ROUTE_FALLBACK_LON,
            altitudeMeters = VIRTUAL_ROUTE_FALLBACK_ALT_M
        )
    }

    private fun readLastKnownRouteAnchor(context: Context): RouteAnchor? {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return null

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)

        val latest = providers
            .asSequence()
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }
                    .getOrNull()
            }
            .maxByOrNull { it.time }
            ?: return null

        return RouteAnchor(
            latitude = latest.latitude,
            longitude = latest.longitude,
            altitudeMeters = if (latest.hasAltitude()) latest.altitude else VIRTUAL_ROUTE_FALLBACK_ALT_M
        )
    }

    private fun readSavedRouteAnchor(context: Context): RouteAnchor? {
        val prefs = context.getSharedPreferences("runn", Context.MODE_PRIVATE)
        val lat = prefs.getString(PREF_ROUTE_ANCHOR_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString(PREF_ROUTE_ANCHOR_LON, null)?.toDoubleOrNull() ?: return null
        val alt = prefs.getString(PREF_ROUTE_ANCHOR_ALT, null)?.toDoubleOrNull() ?: VIRTUAL_ROUTE_FALLBACK_ALT_M

        if (lat !in -89.999999..89.999999) return null
        if (lon !in -180.0..180.0) return null
        return RouteAnchor(latitude = lat, longitude = lon, altitudeMeters = alt)
    }

    private fun saveRouteAnchor(context: Context, anchor: RouteAnchor) {
        context
            .getSharedPreferences("runn", Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_ROUTE_ANCHOR_LAT, anchor.latitude.toString())
            .putString(PREF_ROUTE_ANCHOR_LON, anchor.longitude.toString())
            .putString(PREF_ROUTE_ANCHOR_ALT, anchor.altitudeMeters.toString())
            .apply()
    }

    private fun buildSpeedRecords(
        samples: List<Pair<Long, Float>>,
        startMs: Long,
        endMs: Long,
        zoneId: ZoneId,
        metadata: Metadata
    ): List<SpeedRecord> {
        val chunked = chunkSeriesSamples(
            samples = samples
                .asSequence()
                .filter { (ts, speed) -> speed > 0f && ts in startMs..endMs }
                .toList(),
            startMs = startMs
        )
        return chunked.map { chunk ->
            val startTime = Instant.ofEpochMilli(chunk.first().first)
            val endTime = intervalEnd(chunk.first().first, chunk.last().first)
            SpeedRecord(
                startTime = startTime,
                startZoneOffset = zoneId.rules.getOffset(startTime),
                endTime = endTime,
                endZoneOffset = zoneId.rules.getOffset(endTime),
                metadata = metadata,
                samples = chunk.map { (ts, speed) ->
                    SpeedRecord.Sample(
                        time = Instant.ofEpochMilli(ts),
                        speed = Velocity.metersPerSecond(speed.toDouble())
                    )
                }
            )
        }
    }

    private fun buildHeartRateRecords(
        samples: List<Pair<Long, Long>>,
        startMs: Long,
        endMs: Long,
        zoneId: ZoneId,
        metadata: Metadata
    ): List<HeartRateRecord> {
        val chunked = chunkSeriesSamples(
            samples = samples
                .asSequence()
                .filter { (ts, bpm) -> ts in startMs..endMs && bpm > 0L }
                .toList(),
            startMs = startMs
        )
        return chunked.map { chunk ->
            val startTime = Instant.ofEpochMilli(chunk.first().first)
            val endTime = intervalEnd(chunk.first().first, chunk.last().first)
            HeartRateRecord(
                startTime = startTime,
                startZoneOffset = zoneId.rules.getOffset(startTime),
                endTime = endTime,
                endZoneOffset = zoneId.rules.getOffset(endTime),
                metadata = metadata,
                samples = chunk.map { (ts, bpm) ->
                    HeartRateRecord.Sample(
                        time = Instant.ofEpochMilli(ts),
                        beatsPerMinute = bpm
                    )
                }
            )
        }
    }

    private fun buildStepsCadenceRecords(
        samples: List<Pair<Long, Int>>,
        startMs: Long,
        endMs: Long,
        zoneId: ZoneId,
        metadata: Metadata
    ): List<StepsCadenceRecord> {
        val chunked = chunkSeriesSamples(
            samples = samples
                .asSequence()
                .filter { (ts, cadenceSpm) -> ts in startMs..endMs && cadenceSpm > 0 }
                .toList(),
            startMs = startMs
        )
        return chunked.map { chunk ->
            val startTime = Instant.ofEpochMilli(chunk.first().first)
            val endTime = intervalEnd(chunk.first().first, chunk.last().first)
            StepsCadenceRecord(
                startTime = startTime,
                startZoneOffset = zoneId.rules.getOffset(startTime),
                endTime = endTime,
                endZoneOffset = zoneId.rules.getOffset(endTime),
                metadata = metadata,
                samples = chunk.map { (ts, cadenceSpm) ->
                    StepsCadenceRecord.Sample(
                        time = Instant.ofEpochMilli(ts),
                        rate = cadenceSpm.toDouble()
                    )
                }
            )
        }
    }

    private fun <T> chunkSeriesSamples(
        samples: List<Pair<Long, T>>,
        startMs: Long
    ): List<List<Pair<Long, T>>> {
        if (samples.isEmpty()) return emptyList()

        val ordered = samples.sortedBy { it.first }
        val byWindow = linkedMapOf<Long, MutableList<Pair<Long, T>>>()
        for (sample in ordered) {
            val bucket = ((sample.first - startMs).coerceAtLeast(0L) / SERIES_RECORD_WINDOW_MS)
            byWindow.getOrPut(bucket) { mutableListOf() }.add(sample)
        }

        val chunks = mutableListOf<List<Pair<Long, T>>>()
        for (windowSamples in byWindow.values) {
            var from = 0
            while (from < windowSamples.size) {
                val to = (from + MAX_SAMPLES_PER_SERIES_RECORD).coerceAtMost(windowSamples.size)
                chunks += windowSamples.subList(from, to)
                from = to
            }
        }
        return chunks
    }

    private fun intervalEnd(startMs: Long, lastSampleMs: Long): Instant {
        val endMs = (lastSampleMs + 1L).coerceAtLeast(startMs + 1L)
        return Instant.ofEpochMilli(endMs)
    }

    private suspend fun insertInBatchesWithRetry(hc: HealthConnectClient, records: List<Record>) {
        if (records.isEmpty()) return

        var cursor = 0
        while (cursor < records.size) {
            val next = (cursor + MAX_RECORDS_PER_INSERT).coerceAtMost(records.size)
            val batch = records.subList(cursor, next).toList()
            insertBatchAdaptive(hc, batch)
            cursor = next
        }
    }

    private suspend fun insertBatchAdaptive(hc: HealthConnectClient, batch: List<Record>) {
        try {
            hc.insertRecords(batch)
        } catch (e: Exception) {
            if (batch.size == 1 || !isSizeLimitException(e)) {
                throw e
            }
            Log.w(TAG, "Batch insert failed for size=${batch.size}, splitting", e)
            val mid = batch.size / 2
            insertBatchAdaptive(hc, batch.subList(0, mid))
            insertBatchAdaptive(hc, batch.subList(mid, batch.size))
        }
    }

    private fun isSizeLimitException(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: return false
        return message.contains("record size") ||
            message.contains("single record size") ||
            message.contains("chunk size") ||
            message.contains("memory limit")
    }

    private fun buildElevationRecords(
        workout: WorkoutStateMachine.WorkoutData,
        zoneId: ZoneId,
        metadata: Metadata,
        ftmsPositiveElevationGainM: Double?
    ): List<ElevationGainedRecord> {
        val gainByWindow = estimateElevationGainByWindow(workout).toMutableMap()
        val estimatedTotal = gainByWindow.values.sum()
        val ftmsTotal = ftmsPositiveElevationGainM?.takeIf { it > 0.0 }

        if (ftmsTotal != null) {
            if (estimatedTotal > 0.0) {
                val scale = ftmsTotal / estimatedTotal
                for ((key, gainMeters) in gainByWindow) {
                    gainByWindow[key] = gainMeters * scale
                }
            } else {
                gainByWindow[workout.startMs] = ftmsTotal
            }
        }

        if (gainByWindow.isEmpty()) return emptyList()

        val records = mutableListOf<ElevationGainedRecord>()
        for ((windowStartMs, gainMeters) in gainByWindow) {
            if (gainMeters <= 0.0) continue
            val windowEndMs = minOf(windowStartMs + SERIES_RECORD_WINDOW_MS, workout.endMs)
            if (windowEndMs <= windowStartMs) continue
            val startTime = Instant.ofEpochMilli(windowStartMs)
            val endTime = Instant.ofEpochMilli(windowEndMs)
            records += ElevationGainedRecord(
                startTime = startTime,
                startZoneOffset = zoneId.rules.getOffset(startTime),
                endTime = endTime,
                endZoneOffset = zoneId.rules.getOffset(endTime),
                metadata = metadata,
                elevation = Length.meters(gainMeters)
            )
        }
        return records
    }

    private fun resolveStepCount(
        workout: WorkoutStateMachine.WorkoutData,
        profile: UserProfile,
        preferredStepLengthMeters: Double?
    ): Pair<Long, Boolean> {
        if (workout.steps > 0) return workout.steps.toLong() to false
        if (workout.distanceM <= 0.0) return 0L to false

        val estimatedStepLengthMeters = preferredStepLengthMeters
            ?.takeIf { it in 0.45..1.2 }
            ?: estimateStepLengthMeters(profile)
        if (estimatedStepLengthMeters <= 0.0) return 0L to false

        val estimatedSteps = (workout.distanceM / estimatedStepLengthMeters)
            .roundToLong()
            .coerceAtLeast(1L)
        return estimatedSteps to true
    }

    private fun estimateStepLengthMeters(profile: UserProfile): Double {
        val heightMeters = profile.heightCm?.takeIf { it > 0.0 }?.div(100.0)
        if (heightMeters == null) return DEFAULT_STEP_LENGTH_METERS

        val ratio = when (profile.sex) {
            BiologicalSex.MALE -> 0.415
            BiologicalSex.FEMALE -> 0.413
            BiologicalSex.UNKNOWN -> 0.414
        }
        return (heightMeters * ratio).coerceIn(0.45, 1.2)
    }

    private fun readStepLengthFromPrefs(context: Context): Double? {
        return context
            .getSharedPreferences("runn", Context.MODE_PRIVATE)
            .getFloat(PREF_STEP_LENGTH_M, 0f)
            .toDouble()
            .takeIf { it > 0.0 }
    }

    private fun buildFloorsRecord(
        startTime: Instant,
        startZoneOffset: java.time.ZoneOffset,
        endTime: Instant,
        endZoneOffset: java.time.ZoneOffset,
        metadata: Metadata,
        elevationGainMeters: Double,
        hasPermission: Boolean
    ): FloorsClimbedRecord? {
        if (!hasPermission) return null
        if (elevationGainMeters <= 0.0) return null

        val floors = (elevationGainMeters / METERS_PER_FLOOR)
        if (floors <= 0.0) return null

        return FloorsClimbedRecord(
            startTime = startTime,
            startZoneOffset = startZoneOffset,
            endTime = endTime,
            endZoneOffset = endZoneOffset,
            floors = floors,
            metadata = metadata
        )
    }

    private fun estimateElevationGainByWindow(w: WorkoutStateMachine.WorkoutData): Map<Long, Double> {
        val speeds = w.speeds
            .filter { (ts, speed) -> ts in w.startMs..w.endMs && speed > 0f }
            .sortedBy { it.first }
        if (speeds.isEmpty()) return emptyMap()

        val inclines = w.inclines
            .filter { (ts, _) -> ts in w.startMs..w.endMs }
            .sortedBy { it.first }

        var inclineIdx = 0
        var currentInclinePercent = 0.0
        val gainByWindow = linkedMapOf<Long, Double>()

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

            val grade = (currentInclinePercent / 100.0).coerceAtLeast(0.0)
            if (grade <= 0.0) continue

            var cursor = ts
            while (cursor < segmentEnd) {
                val bucketStart = w.startMs + ((cursor - w.startMs).coerceAtLeast(0L) / SERIES_RECORD_WINDOW_MS) * SERIES_RECORD_WINDOW_MS
                val bucketEnd = minOf(bucketStart + SERIES_RECORD_WINDOW_MS, segmentEnd)
                val overlapMs = (bucketEnd - cursor).coerceAtLeast(0L)
                if (overlapMs > 0L) {
                    val distanceMeters = speed.toDouble() * (overlapMs / 1000.0)
                    val gainMeters = distanceMeters * grade
                    if (gainMeters > 0.0) {
                        gainByWindow[bucketStart] = (gainByWindow[bucketStart] ?: 0.0) + gainMeters
                    }
                }
                cursor = bucketEnd
            }
        }

        return gainByWindow
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
