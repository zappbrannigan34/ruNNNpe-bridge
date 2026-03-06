package com.example.runnbridge

import kotlin.math.abs

class WorkoutStateMachine(
    private val onStart: () -> Unit,
    private val onFinish: (WorkoutData) -> Unit,
    private val onUpdate: (LiveStats) -> Unit = {}
) {
    companion object {
        private const val START_SPEED_THRESH = 0.2f   // m/s (0.72 km/h)
        private const val MOVE_SPEED_THRESH = 0.2f    // m/s
        private const val CADENCE_MOVE_THRESH = 20    // steps per minute
        private const val RESUME_STREAK_REQUIRED = 2
        private const val IDLE_TIMEOUT = 15_000L   // debug: 15 sec no movement -> end
        private const val MIN_DURATION = 10_000L   // debug: minimum 10 sec
        private const val SERIES_SAMPLE_INTERVAL_MS = 5_000L
        private const val HEART_RATE_SAMPLE_INTERVAL_MS = 10_000L
        private const val SPEED_CHANGE_THRESHOLD_MPS = 0.05f
        private const val INCLINE_CHANGE_THRESHOLD_PERCENT = 0.2f
        private const val HEART_RATE_CHANGE_THRESHOLD_BPM = 2L
        private const val CADENCE_SAMPLE_INTERVAL_MS = 5_000L
        private const val CADENCE_CHANGE_THRESHOLD_SPM = 2
    }

    enum class State { IDLE, RUNNING, COOLDOWN }

    data class WorkoutData(
        val startMs: Long, val endMs: Long,
        val speeds: List<Pair<Long, Float>>,
        val inclines: List<Pair<Long, Float>>,
        val cadenceSamples: List<Pair<Long, Int>>,
        val distanceM: Double, val steps: Int,
        val lastSpeedMps: Float,
        val lastInclinePercent: Float,
        val heartRateSamples: List<Pair<Long, Long>>,
        val lastHeartRateBpm: Long?,
        val ftmsPositiveElevationGainM: Double?
    )

    data class LiveStats(
        val state: State,
        val durationMs: Long,
        val currentSpeedMps: Float,
        val currentInclinePercent: Float,
        val distanceM: Double,
        val steps: Int,
        val heartRateBpm: Long?
    )

    var state = State.IDLE; private set
    private var startMs = 0L
    private var lastMoveMs = 0L
    private var lastReadingMs = 0L
    private val speeds = mutableListOf<Pair<Long, Float>>()
    private val inclines = mutableListOf<Pair<Long, Float>>()
    private val cadenceSamples = mutableListOf<Pair<Long, Int>>()
    private var distanceM = 0.0
    private var prevSensorDist: Float? = null
    private var stepAccum = 0.0
    private var steps = 0
    private var lastCadenceSpm = 0
    private var lastSpeedMps = 0f
    private var lastInclinePercent = 0f
    private val heartRateSamples = mutableListOf<Pair<Long, Long>>()
    private var lastHeartRateBpm: Long? = null
    private var resumeEvidenceStreak = 0
    private var lastSeriesSampleMs = 0L
    private var lastSeriesSpeedMps = 0f
    private var lastSeriesInclinePercent = 0f
    private var hasSeriesSample = false
    private var lastHeartRateSampleMs = 0L
    private var lastHeartRateSampleBpm = 0L
    private var hasHeartRateSample = false
    private var lastCadenceSampleMs = 0L
    private var lastCadenceSampleSpm = 0
    private var hasCadenceSample = false
    private var ftmsElevationGainStartM: Float? = null
    private var ftmsElevationGainLastM: Float? = null

    fun onFtms(s: FtmsParser.TreadmillSnapshot) {
        feed(
            speedMps = s.speedMps,
            cadence = 0,
            sensorDist = s.totalDistanceM,
            incline = s.inclinePercent,
            heartRateBpm = s.heartRate.takeIf { it > 0 },
            positiveElevationGainM = s.positiveElevationGainM
        )
    }

    fun onRsc(s: FtmsParser.RscSnapshot) {
        feed(
            speedMps = s.speedMps,
            cadence = s.cadenceSpm,
            sensorDist = s.totalDistanceM,
            incline = 0f,
            heartRateBpm = null,
            positiveElevationGainM = null
        )
    }

    fun onHeartRate(heartRateBpm: Int) {
        if (heartRateBpm <= 0) return
        val now = System.currentTimeMillis()
        lastHeartRateBpm = heartRateBpm.toLong()
        if (state != State.IDLE) {
            appendHeartRateSampleIfNeeded(now, heartRateBpm.toLong())
            onUpdate(currentStats(now))
        }
    }

    fun checkIdle() {
        if (state == State.IDLE) return
        val now = System.currentTimeMillis()
        if (now - lastMoveMs >= IDLE_TIMEOUT) finish()
    }

    fun forceFinish() { if (state != State.IDLE) finish() }

    private fun feed(
        speedMps: Float,
        cadence: Int,
        sensorDist: Float,
        incline: Float,
        heartRateBpm: Int?,
        positiveElevationGainM: Float?
    ) {
        val now = System.currentTimeMillis()
        val distanceDelta = if (sensorDist > 0f && prevSensorDist != null) {
            sensorDist - prevSensorDist!!
        } else {
            0f
        }
        val hasDistanceAdvance = distanceDelta >= 0.02f
        val hasCadenceEvidence = cadence >= CADENCE_MOVE_THRESH
        val hasSpeedEvidence = speedMps >= MOVE_SPEED_THRESH
        val hasMovementEvidence = hasDistanceAdvance || hasCadenceEvidence || hasSpeedEvidence

        var finished = false
        when (state) {
            State.IDLE -> {
                if (speedMps >= START_SPEED_THRESH || hasDistanceAdvance || hasCadenceEvidence) {
                    state = State.RUNNING
                    startMs = now; lastMoveMs = now; lastReadingMs = now
                    speeds.clear(); distanceM = 0.0
                    inclines.clear()
                    cadenceSamples.clear()
                    prevSensorDist = if (sensorDist > 0) sensorDist else null
                    stepAccum = 0.0; steps = 0
                    lastCadenceSpm = 0
                    lastSpeedMps = 0f; lastInclinePercent = 0f
                    heartRateSamples.clear(); lastHeartRateBpm = null
                    resumeEvidenceStreak = 0
                    resetSamplingState()
                    ftmsElevationGainStartM = null
                    ftmsElevationGainLastM = null
                    onStart()
                    accumulate(now, speedMps, cadence, sensorDist, incline, heartRateBpm, positiveElevationGainM)
                }
            }
            State.RUNNING -> {
                accumulate(now, speedMps, cadence, sensorDist, incline, heartRateBpm, positiveElevationGainM)
                if (hasMovementEvidence) {
                    lastMoveMs = now
                    resumeEvidenceStreak = 0
                } else {
                    state = State.COOLDOWN
                    resumeEvidenceStreak = 0
                }
            }
            State.COOLDOWN -> {
                if (hasMovementEvidence) {
                    resumeEvidenceStreak++
                    if (resumeEvidenceStreak >= RESUME_STREAK_REQUIRED) {
                        state = State.RUNNING
                        lastMoveMs = now
                        resumeEvidenceStreak = 0
                        accumulate(now, speedMps, cadence, sensorDist, incline, heartRateBpm, positiveElevationGainM)
                    }
                } else if (now - lastMoveMs >= IDLE_TIMEOUT) {
                    finish()
                    finished = true
                } else {
                    resumeEvidenceStreak = 0
                }
            }
        }
        if (!finished && state != State.IDLE) onUpdate(currentStats(now))
    }

    private fun accumulate(
        now: Long,
        speed: Float,
        cadence: Int,
        sDist: Float,
        incline: Float,
        heartRateBpm: Int?,
        positiveElevationGainM: Float?
    ) {
        appendSeriesSampleIfNeeded(now, speed, incline)
        appendCadenceSampleIfNeeded(now, cadence)
        updateFtmsElevationGain(positiveElevationGainM)
        lastSpeedMps = speed
        lastInclinePercent = incline
        // Distance from sensor or integration
        if (sDist > 0 && prevSensorDist != null) {
            val delta = sDist - prevSensorDist!!
            if (delta in 0.01f..50f) distanceM += delta
        } else if (lastReadingMs > 0 && speed > 0) {
            val dtSec = (now - lastReadingMs) / 1000.0
            if (dtSec in 0.01..5.0) distanceM += speed * dtSec
        }
        if (sDist > 0) prevSensorDist = sDist
        if (cadence > 0 && lastReadingMs > 0) {
            val dtSec = (now - lastReadingMs) / 1000.0
            if (dtSec in 0.01..5.0) { stepAccum += cadence * dtSec / 60.0; steps = stepAccum.toInt() }
            lastCadenceSpm = cadence
        }
        if (heartRateBpm != null && heartRateBpm > 0) {
            val bpm = heartRateBpm.toLong()
            appendHeartRateSampleIfNeeded(now, bpm)
            lastHeartRateBpm = bpm
        }
        lastReadingMs = now
    }

    private fun finish() {
        val endMs = maxOf(lastMoveMs, lastReadingMs, startMs)
        val dur = (endMs - startMs).coerceAtLeast(0L)
        if (dur > 0L) {
            appendSeriesSampleIfNeeded(endMs, lastSpeedMps, lastInclinePercent, force = true)
            lastHeartRateBpm?.let { appendHeartRateSampleIfNeeded(endMs, it, force = true) }
            if (lastCadenceSpm > 0) {
                appendCadenceSampleIfNeeded(endMs, lastCadenceSpm, force = true)
            }
        }
        onUpdate(
            LiveStats(
                state = State.IDLE,
                durationMs = dur,
                currentSpeedMps = lastSpeedMps,
                currentInclinePercent = lastInclinePercent,
                distanceM = distanceM,
                steps = steps,
                heartRateBpm = lastHeartRateBpm
            )
        )
        state = State.IDLE
        if (dur < MIN_DURATION) return
        val ftmsPositiveElevationGain = computeFtmsPositiveElevationGainM()
        onFinish(
            WorkoutData(
                startMs = startMs,
                endMs = endMs,
                speeds = speeds.toList(),
                inclines = inclines.toList(),
                cadenceSamples = cadenceSamples.toList(),
                distanceM = distanceM,
                steps = steps,
                lastSpeedMps = lastSpeedMps,
                lastInclinePercent = lastInclinePercent,
                heartRateSamples = heartRateSamples.toList(),
                lastHeartRateBpm = lastHeartRateBpm,
                ftmsPositiveElevationGainM = ftmsPositiveElevationGain
            )
        )
    }

    private fun currentStats(now: Long): LiveStats {
        val durationMs = (now - startMs).coerceAtLeast(0L)
        return LiveStats(
            state = state,
            durationMs = durationMs,
            currentSpeedMps = lastSpeedMps,
            currentInclinePercent = lastInclinePercent,
            distanceM = distanceM,
            steps = steps,
            heartRateBpm = lastHeartRateBpm
        )
    }

    private fun resetSamplingState() {
        lastSeriesSampleMs = 0L
        lastSeriesSpeedMps = 0f
        lastSeriesInclinePercent = 0f
        hasSeriesSample = false
        lastHeartRateSampleMs = 0L
        lastHeartRateSampleBpm = 0L
        hasHeartRateSample = false
        lastCadenceSampleMs = 0L
        lastCadenceSampleSpm = 0
        hasCadenceSample = false
    }

    private fun appendSeriesSampleIfNeeded(now: Long, speed: Float, incline: Float, force: Boolean = false) {
        val shouldAppend = when {
            !hasSeriesSample -> true
            force -> true
            now - lastSeriesSampleMs >= SERIES_SAMPLE_INTERVAL_MS -> true
            abs(speed - lastSeriesSpeedMps) >= SPEED_CHANGE_THRESHOLD_MPS -> true
            abs(incline - lastSeriesInclinePercent) >= INCLINE_CHANGE_THRESHOLD_PERCENT -> true
            else -> false
        }
        if (!shouldAppend) return

        if (speeds.isNotEmpty() && speeds.last().first == now && inclines.isNotEmpty() && inclines.last().first == now) {
            speeds[speeds.lastIndex] = now to speed
            inclines[inclines.lastIndex] = now to incline
        } else {
            speeds += now to speed
            inclines += now to incline
        }

        hasSeriesSample = true
        lastSeriesSampleMs = now
        lastSeriesSpeedMps = speed
        lastSeriesInclinePercent = incline
    }

    private fun appendHeartRateSampleIfNeeded(now: Long, bpm: Long, force: Boolean = false) {
        val shouldAppend = when {
            !hasHeartRateSample -> true
            force -> true
            now - lastHeartRateSampleMs >= HEART_RATE_SAMPLE_INTERVAL_MS -> true
            abs(bpm - lastHeartRateSampleBpm) >= HEART_RATE_CHANGE_THRESHOLD_BPM -> true
            else -> false
        }
        if (!shouldAppend) return

        if (heartRateSamples.isNotEmpty() && heartRateSamples.last().first == now) {
            heartRateSamples[heartRateSamples.lastIndex] = now to bpm
        } else {
            heartRateSamples += now to bpm
        }

        hasHeartRateSample = true
        lastHeartRateSampleMs = now
        lastHeartRateSampleBpm = bpm
    }

    private fun appendCadenceSampleIfNeeded(now: Long, cadenceSpm: Int, force: Boolean = false) {
        if (cadenceSpm <= 0 && !force) return

        val shouldAppend = when {
            !hasCadenceSample -> cadenceSpm > 0 || force
            force -> true
            now - lastCadenceSampleMs >= CADENCE_SAMPLE_INTERVAL_MS -> true
            kotlin.math.abs(cadenceSpm - lastCadenceSampleSpm) >= CADENCE_CHANGE_THRESHOLD_SPM -> true
            else -> false
        }
        if (!shouldAppend) return

        if (cadenceSamples.isNotEmpty() && cadenceSamples.last().first == now) {
            cadenceSamples[cadenceSamples.lastIndex] = now to cadenceSpm
        } else {
            cadenceSamples += now to cadenceSpm
        }

        hasCadenceSample = true
        lastCadenceSampleMs = now
        lastCadenceSampleSpm = cadenceSpm
    }

    private fun updateFtmsElevationGain(gainM: Float?) {
        if (gainM == null || gainM < 0f) return
        if (ftmsElevationGainStartM == null) {
            ftmsElevationGainStartM = gainM
        }
        ftmsElevationGainLastM = gainM
    }

    private fun computeFtmsPositiveElevationGainM(): Double? {
        val start = ftmsElevationGainStartM ?: return null
        val end = ftmsElevationGainLastM ?: return null
        return (end - start).coerceAtLeast(0f).toDouble()
    }
}
