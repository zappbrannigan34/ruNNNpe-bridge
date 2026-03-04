package com.example.runnbridge

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
    }

    enum class State { IDLE, RUNNING, COOLDOWN }

    data class WorkoutData(
        val startMs: Long, val endMs: Long,
        val speeds: List<Pair<Long, Float>>,
        val inclines: List<Pair<Long, Float>>,
        val distanceM: Double, val steps: Int,
        val lastSpeedMps: Float,
        val lastInclinePercent: Float,
        val heartRateSamples: List<Pair<Long, Long>>,
        val lastHeartRateBpm: Long?
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
    private var distanceM = 0.0
    private var prevSensorDist: Float? = null
    private var stepAccum = 0.0
    private var steps = 0
    private var lastSpeedMps = 0f
    private var lastInclinePercent = 0f
    private val heartRateSamples = mutableListOf<Pair<Long, Long>>()
    private var lastHeartRateBpm: Long? = null
    private var resumeEvidenceStreak = 0

    fun onFtms(s: FtmsParser.TreadmillSnapshot) {
        feed(s.speedMps, 0, s.totalDistanceM, s.inclinePercent, s.heartRate.takeIf { it > 0 })
    }

    fun onRsc(s: FtmsParser.RscSnapshot) {
        feed(s.speedMps, s.cadenceSpm, s.totalDistanceM, 0f, null)
    }

    fun onHeartRate(heartRateBpm: Int) {
        if (heartRateBpm <= 0) return
        val now = System.currentTimeMillis()
        lastHeartRateBpm = heartRateBpm.toLong()
        if (state != State.IDLE) {
            heartRateSamples += now to heartRateBpm.toLong()
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
        heartRateBpm: Int?
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
                    prevSensorDist = if (sensorDist > 0) sensorDist else null
                    stepAccum = 0.0; steps = 0
                    lastSpeedMps = 0f; lastInclinePercent = 0f
                    heartRateSamples.clear(); lastHeartRateBpm = null
                    resumeEvidenceStreak = 0
                    onStart()
                    accumulate(now, speedMps, cadence, sensorDist, incline, heartRateBpm)
                }
            }
            State.RUNNING -> {
                accumulate(now, speedMps, cadence, sensorDist, incline, heartRateBpm)
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
                        accumulate(now, speedMps, cadence, sensorDist, incline, heartRateBpm)
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
        heartRateBpm: Int?
    ) {
        speeds.add(now to speed)
        inclines.add(now to incline)
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
        }
        if (heartRateBpm != null && heartRateBpm > 0) {
            val bpm = heartRateBpm.toLong()
            heartRateSamples += now to bpm
            lastHeartRateBpm = bpm
        }
        lastReadingMs = now
    }

    private fun finish() {
        val endMs = maxOf(lastMoveMs, lastReadingMs, startMs)
        val dur = (endMs - startMs).coerceAtLeast(0L)
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
        onFinish(
            WorkoutData(
                startMs = startMs,
                endMs = endMs,
                speeds = speeds.toList(),
                inclines = inclines.toList(),
                distanceM = distanceM,
                steps = steps,
                lastSpeedMps = lastSpeedMps,
                lastInclinePercent = lastInclinePercent,
                heartRateSamples = heartRateSamples.toList(),
                lastHeartRateBpm = lastHeartRateBpm
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
}
