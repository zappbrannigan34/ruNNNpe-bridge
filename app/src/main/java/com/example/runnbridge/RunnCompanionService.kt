package com.example.runnbridge

import android.companion.CompanionDeviceService
import android.util.Log

class RunnCompanionService : CompanionDeviceService() {
    override fun onDeviceAppeared(deviceAddress: String) {
        val targetMac = getSharedPreferences("runn", MODE_PRIVATE).getString("mac", null)
        if (targetMac == null || !targetMac.equals(deviceAddress, ignoreCase = true)) return

        Log.i(TAG, "Companion device appeared: $deviceAddress")
        BleScanReceiver.startBackgroundScan(this, "companion-appeared")
        SafeServiceStarter.startBleForegroundService(this, "companion-appeared")
    }

    override fun onDeviceDisappeared(deviceAddress: String) {
        Log.i(TAG, "Companion device disappeared: $deviceAddress")
    }

    companion object {
        private const val TAG = "RunnCompanionSvc"
    }
}
