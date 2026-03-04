package com.example.runnbridge

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

object SafeServiceStarter {
    private const val TAG = "SvcStarter"

    fun startBleForegroundService(context: Context, reason: String) {
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BleForegroundService::class.java)
            )
            Log.i(TAG, "Foreground service start requested: $reason")
        } catch (e: Exception) {
            Log.w(TAG, "Foreground service start blocked ($reason): ${e.javaClass.simpleName}")
            BleScanReceiver.startBackgroundScan(context, "fallback:$reason")
        }
    }
}
