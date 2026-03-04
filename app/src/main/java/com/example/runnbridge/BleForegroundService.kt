package com.example.runnbridge

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

class BleForegroundService : Service() {
    companion object {
        const val ACTION_TELEMETRY = "com.example.runnbridge.ACTION_TELEMETRY"
        const val EXTRA_LOG_LINE = "extra_log_line"
        const val EXTRA_DURATION_MS = "extra_duration_ms"
        const val EXTRA_CURRENT_SPEED_KMH = "extra_current_speed_kmh"
        const val EXTRA_CURRENT_INCLINE = "extra_current_incline"
        const val EXTRA_CAL_NET = "extra_cal_net"
        const val EXTRA_CAL_GROSS = "extra_cal_gross"
        const val EXTRA_WEIGHT_KG = "extra_weight_kg"
        const val EXTRA_HEART_RATE_BPM = "extra_heart_rate_bpm"

        private const val TAG = "BleService"
        private const val CH_ID = "runn_fg"
        private const val NOTIF_ID = 1
        private const val PREFS_NAME = "runn"
        private const val PREF_HR_MAC = "hr_mac"
        private const val PREF_WEIGHT_KG = "weight_kg"
        private const val PREF_HEIGHT_CM = "height_cm"
        private const val PREF_AGE_YEARS = "age_years"
        private const val PREF_SEX = "sex"
        private const val PREF_BMR_WATTS = "bmr_watts"
        private const val PREF_LOG_TEXT = "service_log_text"
        private const val PREF_DURATION_MS = "telemetry_duration_ms"
        private const val PREF_CURRENT_SPEED_KMH = "telemetry_current_speed_kmh"
        private const val PREF_CURRENT_INCLINE = "telemetry_current_incline"
        private const val PREF_CAL_NET = "telemetry_cal_net"
        private const val PREF_CAL_GROSS = "telemetry_cal_gross"
        private const val PREF_HEART_RATE_BPM = "telemetry_heart_rate_bpm"
        private val FTMS_SVC  = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
        private val FTMS_CHAR = UUID.fromString("00002acd-0000-1000-8000-00805f9b34fb")
        private val RSC_SVC   = UUID.fromString("00001814-0000-1000-8000-00805f9b34fb")
        private val RSC_CHAR  = UUID.fromString("00002a53-0000-1000-8000-00805f9b34fb")
        private val HR_SVC    = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HR_CHAR   = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CCC       = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_DURATION_MS = 5_000L
        private const val SCAN_INTERVAL_MS = 30_000L
        private const val IDLE_CHECK_MS = 10_000L
        private const val TELEMETRY_PUSH_INTERVAL_MS = 500L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var wake: PowerManager.WakeLock? = null
    private var profile: String? = null
    private var targetMac: String? = null
    private var connected = false
    private var scanning = false
    private var isStopping = false
    private var scanner: BluetoothLeScanner? = null
    private var pendingWriteJob: Job? = null
    private var weightKg = 70.0
    private var hrMac: String? = null
    private var hrConnected = false
    private var hrGatt: BluetoothGatt? = null
    private var runnConnecting = false
    private var hrConnecting = false
    private var lastTelemetryPushMs = 0L
    private lateinit var workout: WorkoutStateMachine
    private var liveCalorieStartMs = 0L
    private val liveSpeedSamples = mutableListOf<Pair<Long, Float>>()
    private val liveInclineSamples = mutableListOf<Pair<Long, Float>>()
    private var lastLiveNetCalories = 0.0
    private var lastLiveGrossCalories = 0.0

    override fun onBind(i: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, notif("⏳ Starting..."))

        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RunnBridge::ble")
            .apply { acquire() }

        weightKg = prefs().getFloat(PREF_WEIGHT_KG, 70f).toDouble()
        targetMac = prefs().getString("mac", null)
        hrMac = prefs().getString(PREF_HR_MAC, null)
        if (targetMac == null) { stopSelf(); return }
        BleScanReceiver.stopBackgroundScan(this, "service-create")
        appendServiceLog("Service started. Weight=${"%.1f".format(weightKg)} kg")

        scanner = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter?.bluetoothLeScanner

        workout = WorkoutStateMachine(
            onStart = {
                appendServiceLog("Workout started")
                updateNotif("🏃 Workout active...")
                triggerHrReconnectScan("workout-start")
            },
            onFinish = { data ->
                pendingWriteJob = scope.launch {
                    try {
                        HealthConnectWriter.writeWorkout(this@BleForegroundService, data)
                        val durationMs = (data.endMs - data.startMs).coerceAtLeast(0L)
                        val profile = readProfileFromPrefs()
                        weightKg = profile.weightKg
                        val calories = CalorieEngine.estimateFromSamples(
                            speedSamples = data.speeds,
                            inclineSamples = data.inclines,
                            startMs = data.startMs,
                            endMs = data.endMs,
                            profile = profile
                        )
                        val summary = formatMetricsLine(
                            durationMs = durationMs,
                            speedKmh = data.lastSpeedMps * 3.6f,
                            incline = data.lastInclinePercent,
                            netCalories = calories.netCalories,
                            grossCalories = calories.grossCalories,
                            heartRateBpm = data.lastHeartRateBpm
                        )
                        updateNotif("✅ $summary")
                        appendServiceLog("Workout saved: $summary")
                        if (!isStopping) {
                            delay(30_000)
                            if (connected) updateNotif("📡 Monitoring ($profile)")
                            else updateNotif("⏳ Waiting for RUNN...")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Health Connect write failed", e)
                        appendServiceLog("Write failed: ${e.javaClass.simpleName}: ${e.message}")
                        updateNotif("⚠️ Write error ${e.javaClass.simpleName}")
                    }
                }
            },
            onUpdate = { stats -> publishTelemetry(stats, stats.state == WorkoutStateMachine.State.IDLE) }
        )

        startScanLoop()
        startIdleChecker()
    }

    @SuppressLint("MissingPermission")
    private fun startScanLoop() {
        scope.launch {
            while (true) {
                hrMac = prefs().getString(PREF_HR_MAC, hrMac)
                val needsRunn = !connected && !runnConnecting
                val needsHr = !hrConnected && !hrConnecting
                if (needsRunn || needsHr) {
                    scanning = true
                    scanner?.startScan(null, ScanSettings.Builder().build(), scanCallback)
                    delay(SCAN_DURATION_MS)
                    scanner?.stopScan(scanCallback)
                    scanning = false
                }
                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    private fun startIdleChecker() {
        handler.post(object : Runnable {
            override fun run() {
                workout.checkIdle()
                handler.postDelayed(this, IDLE_CHECK_MS)
            }
        })
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(cb: Int, result: ScanResult?) {
            val device = result?.device ?: return
            if (device.address.equals(targetMac, ignoreCase = true) && !connected && !runnConnecting) {
                runnConnecting = true
                appendServiceLog("RUNN found: ${device.address}")
                device.connectGatt(this@BleForegroundService, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
                return
            }

            val knownHrMac = hrMac
            val matchesKnownHr = !knownHrMac.isNullOrBlank() && device.address.equals(knownHrMac, ignoreCase = true)
            val autoDiscoverableHr = knownHrMac.isNullOrBlank() && result.hasHrService()
            if ((matchesKnownHr || autoDiscoverableHr) && !hrConnected && !hrConnecting) {
                if (autoDiscoverableHr) {
                    hrMac = device.address
                    prefs().edit().putString(PREF_HR_MAC, device.address).apply()
                    appendServiceLog("HR sensor auto-selected: ${device.address}")
                } else {
                    appendServiceLog("HR sensor found: ${device.address}")
                }
                hrConnecting = true
                hrGatt = device.connectGatt(
                    this@BleForegroundService,
                    true,
                    hrGattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                this@BleForegroundService.gatt = gatt
                runnConnecting = false
                connected = true
                appendServiceLog("BLE connected")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                runnConnecting = false
                appendServiceLog("BLE disconnected")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val ftmsChar = gatt.getService(FTMS_SVC)?.getCharacteristic(FTMS_CHAR)
            val rscChar = gatt.getService(RSC_SVC)?.getCharacteristic(RSC_CHAR)

            when {
                ftmsChar != null -> {
                    profile = "FTMS"
                    appendServiceLog("Profile FTMS")
                    enableNotify(gatt, ftmsChar)
                }
                rscChar != null -> {
                    profile = "RSC"
                    appendServiceLog("Profile RSC")
                    enableNotify(gatt, rscChar)
                }
                else -> {
                    appendServiceLog("No supported profile found")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            when (characteristic.uuid) {
                FTMS_CHAR -> if (profile == "FTMS") workout.onFtms(FtmsParser.parse(data))
                RSC_CHAR -> if (profile == "RSC") workout.onRsc(FtmsParser.parseRsc(data))
            }
        }
    }

    private val hrGattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                hrGatt = gatt
                hrConnecting = false
                hrConnected = true
                appendServiceLog("HR BLE connected")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                hrConnected = false
                hrConnecting = false
                appendServiceLog("HR BLE disconnected")
                triggerHrReconnectScan("hr-disconnected")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val hrChar = gatt.getService(HR_SVC)?.getCharacteristic(HR_CHAR)
            if (hrChar != null) {
                appendServiceLog("HR profile connected")
                enableNotify(gatt, hrChar)
            } else {
                appendServiceLog("HR profile not found")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != HR_CHAR) return
            val heartRate = parseHeartRateMeasurement(characteristic.value)
            if (heartRate > 0) {
                workout.onHeartRate(heartRate)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun triggerHrReconnectScan(reason: String) {
        if (hrConnected || hrConnecting || scanning) return

        scope.launch {
            hrMac = prefs().getString(PREF_HR_MAC, hrMac)
            if (hrConnected || hrConnecting || scanning) return@launch

            scanning = true
            appendServiceLog("Scanning for HR sensor ($reason)")
            scanner?.startScan(null, ScanSettings.Builder().build(), scanCallback)
            delay(SCAN_DURATION_MS)
            scanner?.stopScan(scanCallback)
            scanning = false
        }
    }

    private fun ScanResult.hasHrService(): Boolean {
        val serviceUuids = this.scanRecord?.serviceUuids.orEmpty()
        return serviceUuids.any { parcelUuid -> parcelUuid?.uuid == HR_SVC }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(activeGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        activeGatt.setCharacteristicNotification(characteristic, true)
        characteristic.getDescriptor(CCC)?.let { writeDescriptorCompat(activeGatt, it) }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptorCompat(
        activeGatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activeGatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            )
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            activeGatt.writeDescriptor(descriptor)
        }
    }

    private fun parseHeartRateMeasurement(data: ByteArray): Int {
        if (data.isEmpty()) return 0
        val flags = data[0].toInt() and 0xFF
        val is16Bit = flags and 0x01 != 0
        return if (is16Bit && data.size >= 3) {
            ((data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8))
        } else if (!is16Bit && data.size >= 2) {
            data[1].toInt() and 0xFF
        } else {
            0
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CH_ID, "ruNNNpe bridge", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Background BLE monitoring"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notif(text: String) = NotificationCompat.Builder(this, CH_ID)
        .setContentTitle("ruNNNpe bridge")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true)
        .build()

    private fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif(text))
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int = START_STICKY

    override fun onDestroy() {
        isStopping = true
        appendServiceLog("Service stopping")
        handler.removeCallbacksAndMessages(null)
        workout.forceFinish()
        runBlocking {
            withTimeoutOrNull(10_000L) {
                pendingWriteJob?.join()
            }
        }
        scope.cancel()
        BleScanReceiver.startBackgroundScan(this, "service-destroy")
        wake?.let { if (it.isHeld) it.release() }
        hrGatt?.close()
        hrGatt = null
        hrConnected = false
        gatt?.close()
        super.onDestroy()
    }

    private fun publishTelemetry(
        stats: WorkoutStateMachine.LiveStats,
        force: Boolean
    ) {
        val now = System.currentTimeMillis()
        if (!force && now - lastTelemetryPushMs < TELEMETRY_PUSH_INTERVAL_MS) return
        lastTelemetryPushMs = now

        val profile = readProfileFromPrefs()
        weightKg = profile.weightKg
        val currentSpeedKmh = stats.currentSpeedMps * 3.6f
        val calories = computeLiveCalories(stats, profile, now)

        prefs().edit()
            .putLong(PREF_DURATION_MS, stats.durationMs)
            .putFloat(PREF_CURRENT_SPEED_KMH, currentSpeedKmh)
            .putFloat(PREF_CURRENT_INCLINE, stats.currentInclinePercent)
            .putFloat(PREF_CAL_NET, calories.netCalories.toFloat())
            .putFloat(PREF_CAL_GROSS, calories.grossCalories.toFloat())
            .putLong(PREF_HEART_RATE_BPM, stats.heartRateBpm ?: 0L)
            .apply()

        if (stats.state != WorkoutStateMachine.State.IDLE) {
            updateNotif(
                formatMetricsLine(
                    durationMs = stats.durationMs,
                    speedKmh = currentSpeedKmh,
                    incline = stats.currentInclinePercent,
                    netCalories = calories.netCalories,
                    grossCalories = calories.grossCalories,
                    heartRateBpm = stats.heartRateBpm
                )
            )
        }

        sendBroadcast(
            Intent(ACTION_TELEMETRY)
                .setPackage(packageName)
                .putExtra(EXTRA_DURATION_MS, stats.durationMs)
                .putExtra(EXTRA_CURRENT_SPEED_KMH, currentSpeedKmh)
                .putExtra(EXTRA_CURRENT_INCLINE, stats.currentInclinePercent)
                .putExtra(EXTRA_CAL_NET, calories.netCalories)
                .putExtra(EXTRA_CAL_GROSS, calories.grossCalories)
                .putExtra(EXTRA_WEIGHT_KG, weightKg)
                .putExtra(EXTRA_HEART_RATE_BPM, stats.heartRateBpm ?: 0L)
        )

        if (stats.state == WorkoutStateMachine.State.IDLE) {
            resetLiveCalorieState()
        }
    }

    private fun computeLiveCalories(
        stats: WorkoutStateMachine.LiveStats,
        profile: UserProfile,
        nowMs: Long
    ): CalorieEstimate {
        if (stats.state == WorkoutStateMachine.State.IDLE) {
            return CalorieEstimate(
                netCalories = lastLiveNetCalories,
                grossCalories = lastLiveGrossCalories,
                equation = "acsm_segmented_live",
                restingKcalPerMin = CalorieEngine.restingKcalPerMin(profile)
            )
        }

        if (liveCalorieStartMs <= 0L || stats.durationMs <= 0L) {
            liveCalorieStartMs = nowMs - stats.durationMs.coerceAtLeast(0L)
            liveSpeedSamples.clear()
            liveInclineSamples.clear()
            lastLiveNetCalories = 0.0
            lastLiveGrossCalories = 0.0
        }

        appendLiveSample(nowMs, stats.currentSpeedMps, stats.currentInclinePercent)

        val raw = CalorieEngine.estimateFromSamples(
            speedSamples = liveSpeedSamples,
            inclineSamples = liveInclineSamples,
            startMs = liveCalorieStartMs,
            endMs = nowMs,
            profile = profile
        )

        val net = max(raw.netCalories, lastLiveNetCalories)
        val gross = max(raw.grossCalories, lastLiveGrossCalories)
        lastLiveNetCalories = net
        lastLiveGrossCalories = gross

        return CalorieEstimate(
            netCalories = net,
            grossCalories = gross,
            equation = raw.equation,
            restingKcalPerMin = raw.restingKcalPerMin
        )
    }

    private fun appendLiveSample(
        timestampMs: Long,
        speedMps: Float,
        inclinePercent: Float
    ) {
        val lastTs = liveSpeedSamples.lastOrNull()?.first
        val lastSpeed = liveSpeedSamples.lastOrNull()?.second
        val lastIncline = liveInclineSamples.lastOrNull()?.second
        val shouldAppend = lastTs == null ||
            (timestampMs - lastTs >= 1_000L) ||
            lastSpeed == null ||
            lastIncline == null ||
            abs(lastSpeed - speedMps) >= 0.01f ||
            abs(lastIncline - inclinePercent) >= 0.1f

        if (!shouldAppend) return
        liveSpeedSamples += timestampMs to speedMps
        liveInclineSamples += timestampMs to inclinePercent
    }

    private fun resetLiveCalorieState() {
        liveCalorieStartMs = 0L
        liveSpeedSamples.clear()
        liveInclineSamples.clear()
    }

    private fun formatMetricsLine(
        durationMs: Long,
        speedKmh: Float,
        incline: Float,
        netCalories: Double,
        grossCalories: Double,
        heartRateBpm: Long?
    ): String {
        val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
        val mm = totalSec / 60L
        val ss = totalSec % 60L
        val hrPart = if (heartRateBpm != null && heartRateBpm > 0L) {
            " HR $heartRateBpm"
        } else {
            ""
        }
        return String.format(
            "%02d:%02d %.1f km/h %.1f%% net %.0f gross %.0f kcal%s",
            mm,
            ss,
            speedKmh,
            incline,
            netCalories,
            grossCalories,
            hrPart
        )
    }

    private fun readProfileFromPrefs(): UserProfile {
        val sex = when (prefs().getString(PREF_SEX, BiologicalSex.UNKNOWN.name)) {
            BiologicalSex.MALE.name -> BiologicalSex.MALE
            BiologicalSex.FEMALE.name -> BiologicalSex.FEMALE
            else -> BiologicalSex.UNKNOWN
        }

        return UserProfile(
            weightKg = prefs().getFloat(PREF_WEIGHT_KG, 70f).toDouble(),
            heightCm = prefs().getFloat(PREF_HEIGHT_CM, 0f).toDouble().takeIf { it > 0.0 },
            ageYears = prefs().getInt(PREF_AGE_YEARS, 0).takeIf { it > 0 },
            sex = sex,
            bmrWatts = prefs().getFloat(PREF_BMR_WATTS, 0f).toDouble().takeIf { it > 0.0 }
        )
    }

    private fun appendServiceLog(message: String) {
        Log.i(TAG, message)
        val existing = prefs().getString(PREF_LOG_TEXT, "").orEmpty()
        val updated = (message + "\n" + existing).take(8_000)
        prefs().edit().putString(PREF_LOG_TEXT, updated).apply()
        sendBroadcast(
            Intent(ACTION_TELEMETRY)
                .setPackage(packageName)
                .putExtra(EXTRA_LOG_LINE, message)
        )
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
}
