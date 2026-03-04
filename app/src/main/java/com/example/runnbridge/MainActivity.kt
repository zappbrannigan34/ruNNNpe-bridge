package com.example.runnbridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.companion.AssociationRequest
import android.companion.AssociationInfo
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PREFS_NAME = "runn"
        private const val PREF_HR_MAC = "hr_mac"
        private const val PREF_LOG_TEXT = "service_log_text"
        private const val PREF_DURATION_MS = "telemetry_duration_ms"
        private const val PREF_CURRENT_SPEED_KMH = "telemetry_current_speed_kmh"
        private const val PREF_CURRENT_INCLINE = "telemetry_current_incline"
        private const val PREF_CAL_NET = "telemetry_cal_net"
        private const val PREF_CAL_GROSS = "telemetry_cal_gross"
        private const val PREF_HEART_RATE_BPM = "telemetry_heart_rate_bpm"
        private const val PREF_WEIGHT_KG = "weight_kg"
        private const val PREF_HEIGHT_CM = "height_cm"
        private const val PREF_BMI = "bmi"
        private const val PREF_AGE_YEARS = "age_years"
        private const val PREF_SEX = "sex"
        private const val PREF_BMR_WATTS = "bmr_watts"
    }

    private lateinit var statusText: TextView
    private lateinit var metricsText: TextView
    private lateinit var logText: TextView
    private lateinit var ageInput: EditText
    private lateinit var heightInput: EditText
    private lateinit var sexSpinner: Spinner
    private val handler = Handler(Looper.getMainLooper())
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var telemetryReceiverRegistered = false
    private var pendingCompanionMac: String? = null
    private var afterBluetoothEnabled: (() -> Unit)? = null

    private val hcPerms = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getReadPermission(HeightRecord::class),
        HealthPermission.getWritePermission(HeightRecord::class),
        HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
        HealthPermission.getWritePermission(BasalMetabolicRateRecord::class),
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(SpeedRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(ElevationGainedRecord::class),
        HealthPermission.getWritePermission(androidx.health.connect.client.records.HeartRateRecord::class),
        HealthPermission.getWritePermission(androidx.health.connect.client.records.ActiveCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(androidx.health.connect.client.records.TotalCaloriesBurnedRecord::class),
    )

    private val telemetryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BleForegroundService.ACTION_TELEMETRY) return

            intent.getStringExtra(BleForegroundService.EXTRA_LOG_LINE)
                ?.takeIf { it.isNotBlank() }
                ?.let { refreshLogFromPrefs() }

            if (!intent.hasExtra(BleForegroundService.EXTRA_DURATION_MS)) return

            val durationMs = intent.getLongExtra(BleForegroundService.EXTRA_DURATION_MS, 0L)
            val currentSpeedKmh = intent.getFloatExtra(
                BleForegroundService.EXTRA_CURRENT_SPEED_KMH,
                0f
            )
            val currentIncline = intent.getFloatExtra(
                BleForegroundService.EXTRA_CURRENT_INCLINE,
                0f
            )
            val netCalories = intent.getDoubleExtra(BleForegroundService.EXTRA_CAL_NET, 0.0)
            val grossCalories = intent.getDoubleExtra(BleForegroundService.EXTRA_CAL_GROSS, 0.0)
            val heartRate = intent.getLongExtra(BleForegroundService.EXTRA_HEART_RATE_BPM, 0L)
            if (heartRate > 0L) prefs().edit().putLong(PREF_HEART_RATE_BPM, heartRate).apply()
            renderMetrics(
                durationMs = durationMs,
                currentSpeedKmh = currentSpeedKmh,
                currentIncline = currentIncline,
                netCalories = netCalories,
                grossCalories = grossCalories,
                heartRateBpm = heartRate.takeIf { it > 0L }
            )
        }
    }

    // Permission launchers for BLE and Health Connect
    private val blePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasAllPermissions(requiredBlePermissions())) {
            ensureBluetoothEnabled { requestHealthPerms() }
        }
        else statusText.text = "❌ BLE permissions required"
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val next = afterBluetoothEnabled
        afterBluetoothEnabled = null
        if (isBluetoothEnabled()) {
            log("Bluetooth enabled")
            next?.invoke()
        } else {
            log("Bluetooth enable canceled")
            statusText.text = "❌ Bluetooth required"
        }
    }

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) log("Notification permission denied")
        startMonitoringService()
    }

    private val hcPermLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(hcPerms)) {
            syncProfileFromHealthConnect { startBleScan() }
        } else {
            statusText.text = "❌ Health Connect permissions required"
        }
    }

    private val companionAssocLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val mac = pendingCompanionMac ?: return@registerForActivityResult
        if (result.resultCode == RESULT_OK) {
            log("Companion association confirmed")
            observeCompanionPresence(mac)
        } else {
            log("Companion association canceled")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
        }

        statusText = TextView(this).apply {
            textSize = 16f
            root.addView(this)
        }

        metricsText = TextView(this).apply {
            textSize = 14f
            root.addView(this)
        }

        ageInput = EditText(this).apply {
            hint = "Age (years)"
            inputType = InputType.TYPE_CLASS_NUMBER
            root.addView(this)
        }

        heightInput = EditText(this).apply {
            hint = "Height (cm)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            root.addView(this)
        }

        sexSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("unknown", "male", "female")
            )
            root.addView(this)
        }

        Button(this).apply {
            text = "💾 Save Profile Fallback"
            setOnClickListener { saveManualProfile() }
            root.addView(this)
        }

        Button(this).apply {
            text = "⚡ Find RUNN & Start"
            setOnClickListener { startSetup() }
            root.addView(this)
        }

        logText = TextView(this).apply {
            textSize = 12f
            root.addView(this)
        }

        setContentView(ScrollView(this).apply { addView(root) })

        val mac = prefs().getString("mac", null)
        statusText.text = if (mac != null) {
            "✅ Monitoring active\nRUNN: $mac"
        } else {
            "Press button to setup"
        }
        if (mac != null) {
            BleScanReceiver.startBackgroundScan(this, "activity-create")
            observeCompanionPresence(mac)
        }

        logText.text = prefs().getString(PREF_LOG_TEXT, "")
        fillProfileInputsFromPrefs()
        refreshMetricsFromPrefs()
        checkHealthConnectOnStartup()
    }

    override fun onStart() {
        super.onStart()
        registerTelemetryReceiver()
        refreshLogFromPrefs()
        fillProfileInputsFromPrefs()
        refreshMetricsFromPrefs()
    }

    override fun onStop() {
        if (telemetryReceiverRegistered) {
            unregisterReceiver(telemetryReceiver)
            telemetryReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startSetup() {
        log("Starting setup...")

        val hcAvailable = HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE
        if (!hcAvailable) {
            log("Health Connect not available")
            statusText.text = "Please install/update Health Connect"
            return
        }

        requestBlePermissions()
    }

    private fun requestHealthPerms() {
        hcPermLauncher.launch(hcPerms)
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        log("Scanning for RUNN...")

        val scanner = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter?.bluetoothLeScanner

        val callback = object : ScanCallback() {
            override fun onScanResult(cb: Int, result: ScanResult?) {
                val device = result?.device
                val name = device?.name
                if (name != null && (name.contains("RUNN", ignoreCase = true) || name.contains("treadmill", ignoreCase = true))) {
                    log("Found: ${device.name} (${device.address})")
                    scanner?.stopScan(this)
                    onRunnFound(device)
                }
            }
        }

        scanner?.startScan(null, ScanSettings.Builder().build(), callback)
        handler.postDelayed({ scanner?.stopScan(callback) }, 10_000)
    }

    @SuppressLint("MissingPermission")
    private fun onRunnFound(device: BluetoothDevice) {
        prefs().edit().putString("mac", device.address).apply()
        BleScanReceiver.startBackgroundScan(this, "device-selected")
        ensureCompanionPresence(device.address)
        requestBatteryExemption()
        ensureNotificationPermissionAndStartService()
    }

    private fun ensureCompanionPresence(targetMac: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (observeCompanionPresence(targetMac)) return
        requestCompanionAssociation(targetMac)
    }

    private fun observeCompanionPresence(targetMac: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val manager = getSystemService(CompanionDeviceManager::class.java) ?: return false
        return try {
            manager.startObservingDevicePresence(targetMac)
            log("Companion presence observing enabled")
            true
        } catch (e: Exception) {
            log("Companion presence unavailable: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun requestCompanionAssociation(targetMac: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(CompanionDeviceManager::class.java) ?: return

        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(targetMac).build())
            .setSingleDevice(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.associate(request, mainExecutor, object : CompanionDeviceManager.Callback() {
                    override fun onAssociationPending(chooserLauncher: IntentSender) {
                        pendingCompanionMac = targetMac
                        companionAssocLauncher.launch(
                            IntentSenderRequest.Builder(chooserLauncher).build()
                        )
                    }

                    override fun onAssociationCreated(associationInfo: AssociationInfo) {
                        val mac = associationInfo.deviceMacAddress?.toString() ?: targetMac
                        log("Companion associated: $mac")
                        observeCompanionPresence(mac)
                    }

                    override fun onFailure(error: CharSequence?) {
                        log("Companion association failed: ${error ?: "unknown"}")
                    }
                })
            } else {
                @Suppress("DEPRECATION")
                manager.associate(request, object : CompanionDeviceManager.Callback() {
                    override fun onDeviceFound(chooserLauncher: IntentSender) {
                        pendingCompanionMac = targetMac
                        companionAssocLauncher.launch(
                            IntentSenderRequest.Builder(chooserLauncher).build()
                        )
                    }

                    override fun onFailure(error: CharSequence?) {
                        log("Companion association failed: ${error ?: "unknown"}")
                    }
                }, null)
            }
        } catch (e: Exception) {
            log("Companion association error: ${e.javaClass.simpleName}")
        }
    }

    private fun requestBlePermissions() {
        val required = requiredBlePermissions()
        if (hasAllPermissions(required)) {
            ensureBluetoothEnabled { requestHealthPerms() }
            return
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        blePermLauncher.launch(missing.toTypedArray())
    }

    private fun requiredBlePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasAllPermissions(permissions: Array<String>): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun ensureNotificationPermissionAndStartService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startMonitoringService()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startMonitoringService()
        else notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startMonitoringService() {
        ensureBluetoothEnabled {
            BleScanReceiver.stopBackgroundScan(this, "service-start")
            SafeServiceStarter.startBleForegroundService(this, "activity-start")
            statusText.text = "✅ Ready! Service running in background."
        }
    }

    private fun ensureBluetoothEnabled(next: () -> Unit) {
        if (isBluetoothEnabled()) {
            next()
            return
        }
        afterBluetoothEnabled = next
        log("Requesting Bluetooth enable")
        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    @SuppressLint("MissingPermission")
    private fun isBluetoothEnabled(): Boolean {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            statusText.text = "❌ Bluetooth not supported"
            return false
        }
        return try {
            adapter.isEnabled
        } catch (_: SecurityException) {
            false
        }
    }

    private fun checkHealthConnectOnStartup() {
        when (val sdkStatus = HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> {
                log("Health Connect connected")
                syncProfileFromHealthConnect()
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                log("Health Connect provider update required")
            }
            else -> {
                log("Health Connect unavailable (status=$sdkStatus)")
            }
        }
    }

    private fun syncProfileFromHealthConnect(onDone: (() -> Unit)? = null) {
        uiScope.launch {
            try {
                val hc = HealthConnectClient.getOrCreate(this@MainActivity)
                val granted = hc.permissionController.getGrantedPermissions()

                val latestWeight = if (granted.contains(HealthPermission.getReadPermission(WeightRecord::class))) {
                    withContext(Dispatchers.IO) {
                        hc.readRecords(
                            ReadRecordsRequest(
                                recordType = WeightRecord::class,
                                timeRangeFilter = TimeRangeFilter.between(Instant.EPOCH, Instant.now()),
                                ascendingOrder = false,
                                pageSize = 1
                            )
                        ).records.firstOrNull()?.weight?.inKilograms
                    }
                } else {
                    null
                }

                val latestHeightCm = if (granted.contains(HealthPermission.getReadPermission(HeightRecord::class))) {
                    withContext(Dispatchers.IO) {
                        hc.readRecords(
                            ReadRecordsRequest(
                                recordType = HeightRecord::class,
                                timeRangeFilter = TimeRangeFilter.between(Instant.EPOCH, Instant.now()),
                                ascendingOrder = false,
                                pageSize = 1
                            )
                        ).records.firstOrNull()?.height?.inMeters?.times(100.0)
                    }
                } else {
                    null
                }

                val latestBmrWatts = if (granted.contains(HealthPermission.getReadPermission(BasalMetabolicRateRecord::class))) {
                    withContext(Dispatchers.IO) {
                        hc.readRecords(
                            ReadRecordsRequest(
                                recordType = BasalMetabolicRateRecord::class,
                                timeRangeFilter = TimeRangeFilter.between(Instant.EPOCH, Instant.now()),
                                ascendingOrder = false,
                                pageSize = 1
                            )
                        ).records.firstOrNull()?.basalMetabolicRate?.inWatts
                    }
                } else {
                    null
                }

                val editor = prefs().edit()
                if (latestWeight != null) editor.putFloat(PREF_WEIGHT_KG, latestWeight.toFloat())
                if (latestHeightCm != null) editor.putFloat(PREF_HEIGHT_CM, latestHeightCm.toFloat())
                if (latestBmrWatts != null) editor.putFloat(PREF_BMR_WATTS, latestBmrWatts.toFloat())

                if (latestWeight != null && latestHeightCm != null && latestHeightCm > 0.0) {
                    val bmi = CalorieEngine.bmi(latestWeight, latestHeightCm)
                    if (bmi > 0.0) editor.putFloat(PREF_BMI, bmi.toFloat())
                }

                editor.apply()
                fillProfileInputsFromPrefs()
                refreshMetricsFromPrefs()
                log("Profile synced from Health Connect")
            } catch (e: Exception) {
                log("Health Connect profile sync failed: ${e.javaClass.simpleName}")
            } finally {
                onDone?.invoke()
            }
        }
    }

    private fun saveManualProfile() {
        val age = ageInput.text.toString().trim().toIntOrNull()
        val heightCm = heightInput.text.toString().trim().toDoubleOrNull()
        val sex = selectedSex()

        val editor = prefs().edit()
        if (age != null && age > 0) editor.putInt(PREF_AGE_YEARS, age)
        if (heightCm != null && heightCm > 0.0) editor.putFloat(PREF_HEIGHT_CM, heightCm.toFloat())
        editor.putString(PREF_SEX, sex.name)

        val weight = prefs().getFloat(PREF_WEIGHT_KG, 0f).toDouble()
        if (weight > 0.0 && heightCm != null && heightCm > 0.0) {
            val bmi = CalorieEngine.bmi(weight, heightCm)
            if (bmi > 0.0) editor.putFloat(PREF_BMI, bmi.toFloat())
        }
        editor.apply()

        refreshMetricsFromPrefs()
        syncManualProfileToHealthConnect()
        log("Manual profile saved")
    }

    private fun syncManualProfileToHealthConnect() {
        uiScope.launch {
            try {
                val age = prefs().getInt(PREF_AGE_YEARS, 0).takeIf { it > 0 }
                val heightCm = prefs().getFloat(PREF_HEIGHT_CM, 0f).toDouble().takeIf { it > 0.0 }
                val sex = selectedSexFromPrefs()

                HealthConnectWriter.syncManualProfile(
                    context = this@MainActivity,
                    ageYears = age,
                    heightCm = heightCm,
                    sex = sex,
                    weightKg = prefs().getFloat(PREF_WEIGHT_KG, 0f).toDouble()
                )
                syncProfileFromHealthConnect()
            } catch (e: Exception) {
                log("Manual profile sync failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun fillProfileInputsFromPrefs() {
        val age = prefs().getInt(PREF_AGE_YEARS, 0)
        ageInput.setText(if (age > 0) age.toString() else "")

        val height = prefs().getFloat(PREF_HEIGHT_CM, 0f)
        heightInput.setText(if (height > 0f) String.format(Locale.US, "%.1f", height) else "")

        sexSpinner.setSelection(
            when (selectedSexFromPrefs()) {
                BiologicalSex.UNKNOWN -> 0
                BiologicalSex.MALE -> 1
                BiologicalSex.FEMALE -> 2
            }
        )
    }

    private fun selectedSex(): BiologicalSex {
        return when (sexSpinner.selectedItemPosition) {
            1 -> BiologicalSex.MALE
            2 -> BiologicalSex.FEMALE
            else -> BiologicalSex.UNKNOWN
        }
    }

    private fun selectedSexFromPrefs(): BiologicalSex {
        return when (prefs().getString(PREF_SEX, BiologicalSex.UNKNOWN.name)) {
            BiologicalSex.MALE.name -> BiologicalSex.MALE
            BiologicalSex.FEMALE.name -> BiologicalSex.FEMALE
            else -> BiologicalSex.UNKNOWN
        }
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            log("Battery optimization already disabled")
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    private fun registerTelemetryReceiver() {
        if (telemetryReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            telemetryReceiver,
            IntentFilter(BleForegroundService.ACTION_TELEMETRY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        telemetryReceiverRegistered = true
    }

    private fun refreshMetricsFromPrefs() {
        renderMetrics(
            durationMs = prefs().getLong(PREF_DURATION_MS, 0L),
            currentSpeedKmh = prefs().getFloat(PREF_CURRENT_SPEED_KMH, 0f),
            currentIncline = prefs().getFloat(PREF_CURRENT_INCLINE, 0f),
            netCalories = prefs().getFloat(PREF_CAL_NET, 0f).toDouble(),
            grossCalories = prefs().getFloat(PREF_CAL_GROSS, 0f).toDouble(),
            heartRateBpm = prefs().getLong(PREF_HEART_RATE_BPM, 0L).takeIf { it > 0L }
        )
    }

    private fun renderMetrics(
        durationMs: Long,
        currentSpeedKmh: Float,
        currentIncline: Float,
        netCalories: Double,
        grossCalories: Double,
        heartRateBpm: Long?
    ) {
        val weightKg = prefs().getFloat(PREF_WEIGHT_KG, 0f).toDouble()
        val heightCm = prefs().getFloat(PREF_HEIGHT_CM, 0f).toDouble()
        val bmi = prefs().getFloat(PREF_BMI, 0f).toDouble()
        val age = prefs().getInt(PREF_AGE_YEARS, 0)
        val bmrWatts = prefs().getFloat(PREF_BMR_WATTS, 0f).toDouble()
        val hrMac = prefs().getString(PREF_HR_MAC, null)

        metricsText.text = buildString {
            append("Time: ${formatDuration(durationMs)}\n")
            append(String.format(Locale.US, "Speed: %.1f km/h\n", currentSpeedKmh))
            append(String.format(Locale.US, "Incline: %.1f%%\n", currentIncline))
            append(String.format(Locale.US, "Calories: net %.0f / gross %.0f kcal\n", netCalories, grossCalories))
            if (weightKg > 0.0) append(String.format(Locale.US, "Weight: %.1f kg\n", weightKg))
            if (heightCm > 0.0) append(String.format(Locale.US, "Height: %.1f cm\n", heightCm))
            if (bmi > 0.0) append(String.format(Locale.US, "BMI: %.1f\n", bmi))
            if (age > 0) append("Age: $age\n")
            append("Sex: ${selectedSexFromPrefs().name.lowercase(Locale.US)}\n")
            if (bmrWatts > 0.0) append(String.format(Locale.US, "BMR: %.1f W\n", bmrWatts))
            if (heartRateBpm != null) append("Heart rate: $heartRateBpm bpm\n")
            append("HR sensor: ${hrMac ?: "not selected"}")
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
        val mm = totalSec / 60L
        val ss = totalSec % 60L
        return String.format(Locale.US, "%02d:%02d", mm, ss)
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun log(msg: String) {
        val existing = prefs().getString(PREF_LOG_TEXT, "").orEmpty()
        val updated = (msg + "\n" + existing).take(8_000)
        prefs().edit().putString(PREF_LOG_TEXT, updated).apply()
        logText.text = updated
    }

    private fun refreshLogFromPrefs() {
        logText.text = prefs().getString(PREF_LOG_TEXT, "")
    }
}
