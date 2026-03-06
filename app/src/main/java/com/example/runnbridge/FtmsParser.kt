package com.example.runnbridge

object FtmsParser {

    data class TreadmillSnapshot(
        val speedMps: Float,
        val totalDistanceM: Float,
        val inclinePercent: Float,
        val positiveElevationGainM: Float?,
        val negativeElevationGainM: Float?,
        val elapsedTimeSec: Int,
        val heartRate: Int,
        val valid: Boolean
    )

    fun parse(data: ByteArray): TreadmillSnapshot {
        if (data.size < 4) return emptyTreadmill()

        var off = 0
        val flags = u16(data, off); off += 2

        var speed = 0f; var dist = 0f
        var incline = 0f; var elapsed = 0; var hr = 0
        var positiveElevationGain: Float? = null
        var negativeElevationGain: Float? = null

        // Bit 0 = 0 → speed present (INVERTED flag!)
        if (flags and 0x0001 == 0 && off + 2 <= data.size) {
            speed = u16(data, off) / 100f / 3.6f  // 0.01 km/h → m/s
            off += 2
        }

        // Bit 1 -> optional speed field (ignored)
        if (flags and 0x0002 != 0 && off + 2 <= data.size) {
            off += 2
        }

        // Bit 2 → total distance (uint24, metres)
        if (flags and 0x0004 != 0 && off + 3 <= data.size) {
            dist = u24(data, off).toFloat(); off += 3
        }

        // Bit 3 → inclination + ramp angle
        if (flags and 0x0008 != 0 && off + 4 <= data.size) {
            incline = s16(data, off) / 10f; off += 4
        }

        if (flags and 0x0010 != 0 && off + 4 <= data.size) {
            positiveElevationGain = u16(data, off) / 10f
            off += 2
            negativeElevationGain = u16(data, off) / 10f
            off += 2
        }
        if (flags and 0x0020 != 0) off += 1  // pace
        if (flags and 0x0040 != 0) off += 1  // optional pace
        if (flags and 0x0080 != 0) off += 5  // energy

        // Bit 8 → heart rate
        if (flags and 0x0100 != 0 && off + 1 <= data.size) {
            hr = data[off].toInt() and 0xFF; off += 1
        }

        if (flags and 0x0200 != 0) off += 1  // metabolic eq

        // Bit 10 → elapsed time
        if (flags and 0x0400 != 0 && off + 2 <= data.size) {
            elapsed = u16(data, off); off += 2
        }

        return TreadmillSnapshot(
            speedMps = speed,
            totalDistanceM = dist,
            inclinePercent = incline,
            positiveElevationGainM = positiveElevationGain,
            negativeElevationGainM = negativeElevationGain,
            elapsedTimeSec = elapsed,
            heartRate = hr,
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
        totalDistanceM = 0f,
        inclinePercent = 0f,
        positiveElevationGainM = null,
        negativeElevationGainM = null,
        elapsedTimeSec = 0,
        heartRate = 0,
        valid = false
    )
}
