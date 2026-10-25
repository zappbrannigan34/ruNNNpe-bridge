package com.example.runnbridge

import java.util.UUID

/**
 * Decides whether a scanned BLE device is a treadmill source the bridge can use.
 *
 * Besides NPE RUNN, any treadmill that exposes the standard Fitness Machine Service
 * (FTMS, 0x1826) works, e.g. Merach W50 / W50 Lite (advertised as "MRK-T25-xxxx").
 */
object TreadmillDeviceMatcher {

    val FTMS_SERVICE_UUID: UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")

    // Name hints for devices that do not advertise the FTMS service UUID
    // (e.g. NPE RUNN running in RSC mode).
    private val NAME_HINTS = listOf("RUNN", "treadmill")

    // FTMS advertisement service data: flags (1 byte) + Fitness Machine Type (uint16), bit 0 = treadmill.
    private const val FTMS_TYPE_TREADMILL = 0x0001

    /**
     * @param ftmsServiceData FTMS service data from the advertisement, if any. When present it
     * declares the machine type, which lets us skip FTMS bikes/rowers advertising nearby.
     */
    fun matches(
        name: String?,
        advertisedServiceUuids: Collection<UUID?>,
        ftmsServiceData: ByteArray? = null
    ): Boolean {
        if (ftmsServiceData != null && ftmsServiceData.size >= 3) {
            val machineType = (ftmsServiceData[1].toInt() and 0xFF) or
                ((ftmsServiceData[2].toInt() and 0xFF) shl 8)
            return machineType and FTMS_TYPE_TREADMILL != 0
        }
        if (advertisedServiceUuids.any { it == FTMS_SERVICE_UUID }) return true
        if (name.isNullOrBlank()) return false
        return NAME_HINTS.any { name.contains(it, ignoreCase = true) }
    }
}
