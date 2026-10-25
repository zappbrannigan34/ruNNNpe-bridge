package com.example.runnbridge

object FtmsParser {

    /**
     * Decoded FTMS Treadmill Data (0x2ACD) notification.
     *
     * Optional FTMS fields are nullable: `null` means "not present in this packet",
     * which is different from a reported value of 0. Several treadmills (e.g. Merach
     * MRK-T25 / W50) split their telemetry across alternating packets with different
     * flag sets, so consumers must not treat a missing field as a reset to zero.
     */
    data class TreadmillSnapshot(
        val speedMps: Float,
        val totalDistanceM: Float?,
        val inclinePercent: Float?,
        val positiveElevationGainM: Float?,
        val negativeElevationGainM: Float?,
        val elapsedTimeSec: Int?,
        val heartRate: Int?,
        /** Cumulative step count from a vendor extension, when the device provides one. */
        val stepCount: Int?,
        val valid: Boolean
    )

    // FTMS Treadmill Data flag bits (Bluetooth SIG FTMS v1.0, section 4.9.1.1)
    private const val FLAG_AVERAGE_SPEED = 0x0002
    private const val FLAG_TOTAL_DISTANCE = 0x0004
    private const val FLAG_INCLINATION = 0x0008
    private const val FLAG_ELEVATION_GAIN = 0x0010
    private const val FLAG_INSTANT_PACE = 0x0020
    private const val FLAG_AVERAGE_PACE = 0x0040
    private const val FLAG_EXPENDED_ENERGY = 0x0080
    private const val FLAG_HEART_RATE = 0x0100
    private const val FLAG_METABOLIC_EQ = 0x0200
    private const val FLAG_ELAPSED_TIME = 0x0400
    private const val FLAG_REMAINING_TIME = 0x0800
    private const val FLAG_FORCE_AND_POWER = 0x1000

    /**
     * Reserved bit 13. Merach treadmills (MRK-T25, sold as W50 / W50 Lite) set it and
     * append a uint24 cumulative step count after the standard fields. The value matches
     * the step counter in the vendor FitShow stream (service 0xFFF0) for the same moment.
     */
    private const val FLAG_VENDOR_STEP_COUNT = 0x2000

    fun parse(data: ByteArray): TreadmillSnapshot {
        if (data.size < 4) return emptyTreadmill()

        var off = 0
        val flags = u16(data, off); off += 2

        // Instantaneous speed is the first mandatory field in treadmill data.
        // Parsing it unconditionally prevents valid speed from being dropped to 0.0
        // when vendor flag layouts differ.
        if (off + 2 > data.size) return emptyTreadmill()
        val speed = u16(data, off) / 100f / 3.6f  // 0.01 km/h → m/s
        off += 2

        var dist: Float? = null
        var incline: Float? = null
        var positiveElevationGain: Float? = null
        var negativeElevationGain: Float? = null
        var hr: Int? = null
        var elapsed: Int? = null
        var steps: Int? = null

        if (flags and FLAG_AVERAGE_SPEED != 0) off += 2

        if (flags and FLAG_TOTAL_DISTANCE != 0) {
            if (off + 3 <= data.size) dist = u24(data, off).toFloat()
            off += 3
        }
        if (flags and FLAG_INCLINATION != 0) {
            if (off + 4 <= data.size) incline = s16(data, off) / 10f  // + ramp angle (ignored)
            off += 4
        }
        if (flags and FLAG_ELEVATION_GAIN != 0) {
            if (off + 4 <= data.size) {
                positiveElevationGain = u16(data, off) / 10f
                negativeElevationGain = u16(data, off + 2) / 10f
            }
            off += 4
        }
        if (flags and FLAG_INSTANT_PACE != 0) off += 1
        if (flags and FLAG_AVERAGE_PACE != 0) off += 1
        if (flags and FLAG_EXPENDED_ENERGY != 0) off += 5
        if (flags and FLAG_HEART_RATE != 0) {
            if (off + 1 <= data.size) hr = data[off].toInt() and 0xFF
            off += 1
        }
        if (flags and FLAG_METABOLIC_EQ != 0) off += 1
        if (flags and FLAG_ELAPSED_TIME != 0) {
            if (off + 2 <= data.size) elapsed = u16(data, off)
            off += 2
        }
        if (flags and FLAG_REMAINING_TIME != 0) off += 2
        if (flags and FLAG_FORCE_AND_POWER != 0) off += 4

        if (flags and FLAG_VENDOR_STEP_COUNT != 0 && off + 3 <= data.size) {
            steps = u24(data, off)
            off += 3
        }

        return TreadmillSnapshot(
            speedMps = speed,
            totalDistanceM = dist,
            inclinePercent = incline,
            positiveElevationGainM = positiveElevationGain,
            negativeElevationGainM = negativeElevationGain,
            elapsedTimeSec = elapsed,
            heartRate = hr,
            stepCount = steps,
            valid = true
        )
    }

    // RSC — Running Speed and Cadence (0x2A53)
    data class RscSnapshot(
        val speedMps: Float, val cadenceSpm: Int,
        val totalDistanceM: Float, val valid: Boolean
    )

    fun parseRsc(data: ByteArray): RscSnapshot {
        if (data.size < 4) return RscSnapshot(0f, 0, 0f, false)
        val flags = data[0].toInt() and 0xFF
        val speed = u16(data, 1) / 256f
        val cadence = (data[3].toInt() and 0xFF) * 2
        var off = 4
        if (flags and 0x01 != 0) off += 2
        var dist = 0f
        if (flags and 0x02 != 0 && off + 4 <= data.size) {
            dist = u32(data, off) / 10f
        }
        return RscSnapshot(speed, cadence, dist, true)
    }

    // Byte helpers
    private fun u16(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
    private fun s16(d: ByteArray, o: Int): Int {
        val v = u16(d, o); return if (v >= 0x8000) v - 0x10000 else v
    }
    private fun u24(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xFF) or ((d[o+1].toInt() and 0xFF) shl 8) or
        ((d[o+2].toInt() and 0xFF) shl 16)
    private fun u32(d: ByteArray, o: Int): Float {
        val v = (d[o].toLong() and 0xFF) or ((d[o+1].toLong() and 0xFF) shl 8) or
                ((d[o+2].toLong() and 0xFF) shl 16) or ((d[o+3].toLong() and 0xFF) shl 24)
        return v.toFloat()
    }
    private fun emptyTreadmill() = TreadmillSnapshot(
        speedMps = 0f,
        totalDistanceM = null,
        inclinePercent = null,
        positiveElevationGainM = null,
        negativeElevationGainM = null,
        elapsedTimeSec = null,
        heartRate = null,
        stepCount = null,
        valid = false
    )
}
