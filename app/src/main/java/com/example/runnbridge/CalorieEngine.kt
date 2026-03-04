package com.example.runnbridge

import kotlin.math.max

enum class BiologicalSex {
    UNKNOWN,
    MALE,
    FEMALE
}

data class UserProfile(
    val weightKg: Double,
    val heightCm: Double?,
    val ageYears: Int?,
    val sex: BiologicalSex,
    val bmrWatts: Double?
)

data class CalorieEstimate(
    val netCalories: Double,
    val grossCalories: Double,
    val equation: String,
    val restingKcalPerMin: Double
)

object CalorieEngine {
    private const val WALK_HORIZONTAL_COST = 0.1
    private const val WALK_VERTICAL_COST = 1.8
    private const val RUN_HORIZONTAL_COST = 0.2
    private const val RUN_VERTICAL_COST = 0.9
    private const val RESTING_VO2_ML_KG_MIN = 3.5
    private const val RUNNING_MIN_MPS = 2.24

    fun estimate(
        speedMps: Double,
        inclinePercent: Double,
        durationMs: Long,
        profile: UserProfile
    ): CalorieEstimate {
        if (durationMs <= 0L || profile.weightKg <= 0.0) {
            return CalorieEstimate(0.0, 0.0, "invalid", 0.0)
        }

        val speedMpm = speedMps * 60.0
        val grade = inclinePercent / 100.0

        val (horizontalCost, verticalCost, equationName) = if (speedMps >= RUNNING_MIN_MPS) {
            Triple(RUN_HORIZONTAL_COST, RUN_VERTICAL_COST, "acsm_running")
        } else {
            Triple(WALK_HORIZONTAL_COST, WALK_VERTICAL_COST, "acsm_walking")
        }

        val vo2Gross = (horizontalCost * speedMpm) + (verticalCost * speedMpm * grade) + RESTING_VO2_ML_KG_MIN
        val grossKcalPerMin = vo2Gross * profile.weightKg / 200.0
        val minutes = durationMs / 60_000.0
        val grossCalories = grossKcalPerMin * minutes

        val restingKcalPerMin = restingKcalPerMin(profile)
        val netCalories = max(grossCalories - (restingKcalPerMin * minutes), 0.0)

        return CalorieEstimate(
            netCalories = netCalories,
            grossCalories = grossCalories,
            equation = equationName,
            restingKcalPerMin = restingKcalPerMin
        )
    }

    fun estimateFromSamples(
        speedSamples: List<Pair<Long, Float>>,
        inclineSamples: List<Pair<Long, Float>>,
        startMs: Long,
        endMs: Long,
        profile: UserProfile
    ): CalorieEstimate {
        if (endMs <= startMs || profile.weightKg <= 0.0) {
            return CalorieEstimate(0.0, 0.0, "invalid", 0.0)
        }

        val orderedSpeeds = speedSamples
            .filter { (ts, _) -> ts in startMs..endMs }
            .sortedBy { it.first }
        if (orderedSpeeds.isEmpty()) {
            return estimate(
                speedMps = 0.0,
                inclinePercent = 0.0,
                durationMs = endMs - startMs,
                profile = profile
            ).copy(equation = "acsm_segmented")
        }

        val orderedInclines = inclineSamples
            .filter { (ts, _) -> ts in startMs..endMs }
            .sortedBy { it.first }

        var inclineIdx = 0
        var currentIncline = 0.0
        var netTotal = 0.0
        var grossTotal = 0.0

        for (i in orderedSpeeds.indices) {
            val (ts, speed) = orderedSpeeds[i]
            while (inclineIdx < orderedInclines.size && orderedInclines[inclineIdx].first <= ts) {
                currentIncline = orderedInclines[inclineIdx].second.toDouble()
                inclineIdx++
            }

            val segmentEnd = when {
                i + 1 < orderedSpeeds.size -> minOf(orderedSpeeds[i + 1].first, endMs)
                else -> endMs
            }
            val segmentDurationMs = (segmentEnd - ts).coerceAtLeast(0L)
            if (segmentDurationMs <= 0L) continue

            val segment = estimate(
                speedMps = speed.toDouble().coerceAtLeast(0.0),
                inclinePercent = currentIncline,
                durationMs = segmentDurationMs,
                profile = profile
            )
            netTotal += segment.netCalories
            grossTotal += segment.grossCalories
        }

        return CalorieEstimate(
            netCalories = netTotal,
            grossCalories = grossTotal,
            equation = "acsm_segmented",
            restingKcalPerMin = restingKcalPerMin(profile)
        )
    }

    fun bmi(weightKg: Double, heightCm: Double): Double {
        val heightM = heightCm / 100.0
        if (weightKg <= 0.0 || heightM <= 0.0) return 0.0
        return weightKg / (heightM * heightM)
    }

    fun restingKcalPerMin(profile: UserProfile): Double {
        val bmrWatts = profile.bmrWatts
        if (bmrWatts != null && bmrWatts > 0.0) {
            return bmrWatts * 0.0143307538
        }

        val age = profile.ageYears
        val heightCm = profile.heightCm
        if (age != null && age > 0 && heightCm != null && heightCm > 0.0 && profile.sex != BiologicalSex.UNKNOWN) {
            val reeKcalDay = when (profile.sex) {
                BiologicalSex.MALE -> (10.0 * profile.weightKg) + (6.25 * heightCm) - (5.0 * age) + 5.0
                BiologicalSex.FEMALE -> (10.0 * profile.weightKg) + (6.25 * heightCm) - (5.0 * age) - 161.0
                BiologicalSex.UNKNOWN -> 0.0
            }
            if (reeKcalDay > 0.0) return reeKcalDay / 1440.0
        }

        return RESTING_VO2_ML_KG_MIN * profile.weightKg / 200.0
    }
}
