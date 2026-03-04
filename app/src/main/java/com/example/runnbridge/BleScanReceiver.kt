package com.example.runnbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class BleScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SCAN_RESULT) return

        val targetMac = prefs(context).getString(PREF_MAC, null) ?: return
        val errorCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, ScanCallbackType.NO_ERROR)
        if (errorCode != ScanCallbackType.NO_ERROR) {
            Log.w(TAG, "Pending scan callback error=$errorCode")
            return
        }

        val results = readScanResults(intent)
        val hasMatch = results.any { it.device.address.equals(targetMac, ignoreCase = true) }
        if (!hasMatch) return

        Log.i(TAG, "Pending scan matched target $targetMac")
        SafeServiceStarter.startBleForegroundService(context, "pending-scan-match")
    }

    companion object {
        private const val TAG = "BleScanReceiver"
        private const val ACTION_SCAN_RESULT = "com.example.runnbridge.ACTION_SCAN_RESULT"
        private const val PREFS_NAME = "runn"
        private const val PREF_MAC = "mac"

        @SuppressLint("MissingPermission")
        fun startBackgroundScan(context: Context, reason: String) {
            if (!hasScanPermission(context)) {
                Log.w(TAG, "Skip pending scan (no permission), reason=$reason")
                return
            }

            val targetMac = prefs(context).getString(PREF_MAC, null)
            if (targetMac.isNullOrBlank()) {
                Log.w(TAG, "Skip pending scan (no target MAC), reason=$reason")
                return
            }

            val scanner = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter?.bluetoothLeScanner ?: return

            val filters = listOf(ScanFilter.Builder().setDeviceAddress(targetMac).build())
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()

            try {
                scanner.startScan(filters, settings, pendingIntent(context))
                Log.i(TAG, "Pending scan started for $targetMac ($reason)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start pending scan ($reason)", e)
            }
        }

        @SuppressLint("MissingPermission")
        fun stopBackgroundScan(context: Context, reason: String) {
            val scanner = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter?.bluetoothLeScanner ?: return

            try {
                scanner.stopScan(pendingIntent(context))
                Log.i(TAG, "Pending scan stopped ($reason)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop pending scan ($reason): ${e.javaClass.simpleName}")
            }
        }

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, BleScanReceiver::class.java).setAction(ACTION_SCAN_RESULT)
            return PendingIntent.getBroadcast(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun readScanResults(intent: Intent): List<ScanResult> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(
                    BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                    ScanResult::class.java
                ).orEmpty()
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<ScanResult>(
                    BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT
                ).orEmpty()
            }
        }

        private fun hasScanPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            }
        }

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private object ScanCallbackType {
        const val NO_ERROR = 0
    }
}
