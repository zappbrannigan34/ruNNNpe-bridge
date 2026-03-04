package com.example.runnbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val mac = ctx.getSharedPreferences("runn", Context.MODE_PRIVATE)
                .getString("mac", null)
            if (mac != null) {
                Log.i("BootReceiver", "Starting service, mac=$mac")
                BleScanReceiver.startBackgroundScan(ctx, "boot")
                SafeServiceStarter.startBleForegroundService(ctx, "boot")
            }
        }
    }
}
