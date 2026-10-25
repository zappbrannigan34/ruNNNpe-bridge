package com.example.runnbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Packets captured with nRF Connect from a Merach W50 Lite ("MRK-T25-FAD2"). */
class FtmsParserTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun merachStandardPacket_decodesDistanceInclineAndTime() {
        // flags 0x058C: distance, inclination, energy, heart rate, elapsed time
        val s = FtmsParser.parse(hex("8c05c8006100001e001b000700000000001101"))
        assertEquals(2.0f, s.speedMps * 3.6f, 0.01f)
        assertEquals(97f, s.totalDistanceM)
        assertEquals(3.0f, s.inclinePercent!!, 0.01f)
        assertEquals(0, s.heartRate)
        assertEquals(273, s.elapsedTimeSec)
        assertNull(s.positiveElevationGainM)
        assertNull(s.stepCount)
    }

    @Test
    fun merachVendorPacket_decodesElevationAndStepCount() {
        // flags 0x2010: elevation gain + reserved bit 13 (Merach uint24 step count)
        val s = FtmsParser.parse(hex("1020c8001d000000e30000"))
        assertEquals(2.0f, s.speedMps * 3.6f, 0.01f)
        assertEquals(2.9f, s.positiveElevationGainM!!, 0.01f)
        assertEquals(227, s.stepCount)
        // Fields absent from this packet must stay null, not 0.
        assertNull(s.totalDistanceM)
        assertNull(s.inclinePercent)
        assertNull(s.elapsedTimeSec)
    }

    @Test
    fun shortPacket_isInvalid() {
        assertFalse(FtmsParser.parse(hex("8c05")).valid)
    }

    @Test
    fun alternatingPackets_doNotResetInclineOrDoubleCountDistance() {
        var now = 1_000_000L
        var result: WorkoutStateMachine.WorkoutData? = null
        val machine = WorkoutStateMachine(onStart = {}, onFinish = { result = it }, clock = { now })
        // 60 s at 2 km/h and 3 % incline: the device reports 1 m every ~1.8 s and
        // alternates a standard packet with a vendor packet a few ms later.
        for (sec in 0..60) {
            val dist = String.format("%02x", (sec / 1.8).toInt())
            val steps = String.format("%02x", (sec * 1.3).toInt())
            val gain = String.format("%02x", (sec / 1.8 * 3).toInt())  // 0.1 m units
            machine.onFtms(FtmsParser.parse(hex("8c05c800${dist}00001e001b000000000000${String.format("%02x", sec)}00")))
            now += 15
            // Elevation gain intermittently drops to 0 on the device; it must not reset the total.
            val reportedGain = if (sec % 7 == 0) "00" else gain
            machine.onFtms(FtmsParser.parse(hex("1020c800${reportedGain}000000${steps}0000")))
            now += 985
        }
        // Belt stopped: idle packets until the workout times out.
        repeat(20) {
            machine.onFtms(FtmsParser.parse(hex("8c0500002100001e001b000000000000003c00")))
            now += 1000
        }
        val w = result!!
        assertEquals(33.0, w.distanceM, 0.5)              // sensor distance only, no integration on top
        assertEquals(78, w.steps)                          // device step counter delta
        assertEquals(3.0f, w.lastInclinePercent, 0.01f)
        assertTrue(w.inclines.all { it.second == 3.0f })   // no flapping to 0 % on vendor packets
        assertEquals(9.9, w.ftmsPositiveElevationGainM!!, 0.11)
    }

    @Test
    fun matcher_acceptsFtmsTreadmillsAndRunnNames() {
        val ftms = TreadmillDeviceMatcher.FTMS_SERVICE_UUID
        // Merach W50 advertises the FTMS service UUID: matched without any name rule.
        assertTrue(TreadmillDeviceMatcher.matches("MRK-T25-FAD2", listOf(ftms)))
        assertFalse(TreadmillDeviceMatcher.matches("MRK-T25-FAD2", emptyList()))
        assertTrue(TreadmillDeviceMatcher.matches("RUNN 1234", emptyList()))
        assertTrue(TreadmillDeviceMatcher.matches(null, listOf(ftms)))
        // FTMS service data declaring an indoor bike (type bit 5) is rejected.
        assertFalse(TreadmillDeviceMatcher.matches("Some Bike", listOf(ftms), hex("012000")))
        // FTMS service data declaring a treadmill (type bit 0) is accepted.
        assertTrue(TreadmillDeviceMatcher.matches(null, listOf(ftms), hex("010100")))
        assertFalse(TreadmillDeviceMatcher.matches("Headphones", listOf(UUID.randomUUID())))
    }
}
