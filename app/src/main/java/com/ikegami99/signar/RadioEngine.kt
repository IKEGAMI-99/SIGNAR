package com.ikegami99.signar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.*

data class Sample(val x: Double, val y: Double, val z: Double, val rssi: Int, val time: Long)
data class Estimated(val x: Double, val y: Double, val z: Double, val radius: Double, val confidence: Int)
data class Signal(
    val id: String,
    var label: String,
    val kind: String,
    var rssi: Int,
    var mhz: Int = 0,
    var seenAt: Long = System.currentTimeMillis(),
    val readings: MutableList<Sample> = mutableListOf()
)

class SignalStore {
    private val signals = linkedMapOf<String, Signal>()
    private val cachedEstimates = mutableMapOf<String, Pair<Long, Estimated?>>()
    @Synchronized fun resetMeasurements() {
        signals.values.forEach { it.readings.clear() }
        cachedEstimates.clear()
    }
    @Synchronized fun upsert(id: String, label: String, kind: String, rssi: Int, mhz: Int,
                             pose: SpatialPose, recordSample: Boolean = true) {
        val d = signals.getOrPut(id) { Signal(id, label, kind, rssi) }
        if (label.isNotBlank() && label != "<unknown>") d.label = label
        d.rssi = rssi
        d.mhz = mhz
        d.seenAt = System.currentTimeMillis()
        if (!recordSample || rssi !in -110..15) return
        val last = d.readings.lastOrNull()
        if (last == null || System.currentTimeMillis() - last.time >= 1100 ||
            hypot(last.x - pose.x, last.z - pose.z) > .35) {
            d.readings.add(Sample(pose.x, pose.y, pose.z, rssi, System.currentTimeMillis()))
            if (d.readings.size > 80) d.readings.removeAt(0)
        }
        if (signals.size > 500) {
            val oldest = signals.values.minByOrNull { it.seenAt }
            if (oldest != null) signals.remove(oldest.id)
        }
    }
    @Synchronized fun list(): List<Signal> = signals.values.sortedWith(compareByDescending<Signal> { it.seenAt }.thenByDescending { it.rssi }).map { it.copy(readings = it.readings.toMutableList()) }

    // Deliberately conservative: do not plot a made-up bearing from a single RSSI reading.
    // This is a coarse, flat-floor least-squares fit, not precision ranging.
    @Synchronized fun estimate(device: Signal): Estimated? {
        val key = device.readings.lastOrNull()?.time ?: return null
        cachedEstimates[device.id]?.let { if (it.first == key) return it.second }
        val estimate = solvePosition(device)
        cachedEstimates[device.id] = key to estimate
        return estimate
    }

    fun signalBand(rssi: Int): String = when {
        rssi >= -60 -> "強"
        rssi >= -78 -> "中"
        else -> "弱"
    }

    // Fit unknown transmitter strength from measurements taken at distinct locations.
    // RSSI alone cannot tell bearing and cannot guarantee true location accuracy.
    private fun solvePosition(device: Signal): Estimated? {
        val samples = device.readings.takeLast(45)
        if (samples.size < 4) return null
        val distinct = mutableListOf<Sample>()
        for (s in samples) if (distinct.none { hypot(it.x - s.x, it.z - s.z) < .75 }) distinct.add(s)
        if (distinct.size < 3) return null
        val baseline = distinct.maxOf { a -> distinct.maxOf { b -> hypot(a.x - b.x, a.z - b.z) } }
        if (baseline < 2.0) return null
        val obs = samples.filter { System.currentTimeMillis() - it.time < 20 * 60 * 1000 }
        if (obs.size < 4) return null
        if (obs.maxOf { it.rssi } - obs.minOf { it.rssi } < 4) return null
        val cx = obs.map { it.x }.average()
        val cz = obs.map { it.z }.average()
        val cy = obs.map { it.y }.average()
        val range = min(28.0, max(8.0, baseline * 1.8))
        var bestLoss = Double.POSITIVE_INFINITY
        var bx = cx
        var bz = cz
        val grid = 32
        for (ix in 0..grid) {
            val x = cx - range + (2 * range * ix / grid)
            for (iz in 0..grid) {
                val z = cz - range + (2 * range * iz / grid)
                // Unknown transmission power is estimated independently for each device.
                val offsets = DoubleArray(obs.size) { index ->
                    val p = obs[index]
                    val distance = max(.7, hypot(x - p.x, z - p.z))
                    p.rssi + 22.0 * log10(distance)
                }
                val tx = offsets.average()
                var loss = 0.0
                for (offset in offsets) {
                    val error = (offset - tx) / 8.0
                    loss += min(5.0, error * error)
                }
                loss /= obs.size
                if (loss < bestLoss) { bestLoss = loss; bx = x; bz = z }
            }
        }
        if (bestLoss > 1.0) return null
        // Broad heuristic radius; NOT a statistical measurement of accuracy.
        val uncertainty = (4.0 + sqrt(bestLoss) * 11.0 + 12.0 / baseline).coerceIn(4.0, 30.0)
        val confidence = ((1.0 - bestLoss) * min(1.0, baseline / 8.0) * 55).roundToInt().coerceIn(5, 55)
        return Estimated(bx, cy, bz, uncertainty, confidence)
    }

}

data class SpatialPose(val x: Double, val y: Double, val z: Double, val heading: Double, val ar: Boolean)

class MotionTracker(private val context: Context): SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val steps = sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var heading = 0.0
    private var x = 0.0
    private var y = 1.4
    private var z = 0.0
    private var inAR = false
    private var lastPeak = 0L
    private var wasPeak = false
    private var usingStepDetector = false
    private var stepsCounted = 0
    private var totalWalked = 0.0
    @Synchronized fun steps(): Int = stepsCounted
    @Synchronized fun travelled(): Double = totalWalked
    @Synchronized fun snapshot(): SpatialPose = SpatialPose(x, y, z, heading, inAR)
    @Synchronized fun reset() {
        x = 0.0; y = 1.4; z = 0.0; inAR = false
        stepsCounted = 0; totalWalked = 0.0
    }
    @Synchronized fun setAR(x0: Float, y0: Float, z0: Float) { x = x0.toDouble(); y = y0.toDouble(); z = z0.toDouble(); inAR = true }
    @Synchronized fun setARInactive() { inAR = false }
    fun start() {
        rotation?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        val permitted = Build.VERSION.SDK_INT < 29 ||
            context.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        usingStepDetector = false
        if (permitted && steps != null) {
            try {
                usingStepDetector = sensors.registerListener(this, steps, SensorManager.SENSOR_DELAY_GAME)
            } catch (_: SecurityException) { usingStepDetector = false }
        }
        // Fallback when no step sensor exists or motion permission was denied.
        if (!usingStepDetector) accelerometer?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }
    fun stop() { sensors.unregisterListener(this); usingStepDetector = false }
    @Synchronized private fun step() {
        stepsCounted++
        totalWalked += .72
        if (inAR) return
        // Approximate stride, not the actual measured travel distance.
        x += .72 * sin(heading)
        z -= .72 * cos(heading)
    }
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val matrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(matrix, orientation)
                synchronized(this) { heading = orientation[0].toDouble() }
            }
            Sensor.TYPE_STEP_DETECTOR -> synchronized(this) { step() }
            Sensor.TYPE_ACCELEROMETER -> if (!usingStepDetector) {
                val a = event.values
                val norm = sqrt(a[0]*a[0] + a[1]*a[1] + a[2]*a[2])
                val peak = norm > 12.2f
                val now = SystemClock.elapsedRealtime()
                if (peak && !wasPeak && now - lastPeak > 350) {
                    synchronized(this) { step() }
                    lastPeak = now
                }
                wasPeak = peak
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

class RadioScanner(private val context: Context, private val store: SignalStore,
                   private val motion: MotionTracker, private val onChange: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var active = false
    private var bleStarted = false
    private var registered = false
    private val wifiLastSeen = mutableMapOf<String, Long>()
    private val loop = object: Runnable {
        override fun run() {
            if (!active) return
            try { wifi.startScan(); readWifi(false) } catch (_: SecurityException) {}
            handler.postDelayed(this, 32_000)
        }
    }
    private val receiver = object: BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION ->
                    readWifi(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
                BluetoothDevice.ACTION_FOUND -> {
                    val d = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (d != null) {
                        val level = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, (-95).toShort()).toInt()
                        try { store.upsert("BT:" + d.address, d.name ?: "Bluetooth", "BT", level, 0, motion.snapshot()); onChange() } catch (_: SecurityException) {}
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (active) handler.postDelayed({ try { adapter?.startDiscovery() } catch (_: SecurityException) {} }, 15_000)
                }
            }
        }
    }
    private val bleCallback = object: ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { addBle(result) }
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { addBle(it) } }
    }
    @SuppressLint("MissingPermission")
    private fun addBle(result: ScanResult) {
        try {
            val address = result.device.address ?: return
            val label = result.scanRecord?.deviceName ?: result.device.name ?: "BLE " + address.takeLast(5)
            store.upsert("BLE:" + address, label, "BLE", result.rssi, 0, motion.snapshot())
            onChange()
        } catch (_: SecurityException) {}
    }
    @SuppressLint("MissingPermission")
    fun start() {
        if (active) return
        active = true
        val filter = IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (!registered) {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
            registered = true
        }
        handler.post(loop)
        try {
            if (adapter?.isEnabled == true) {
                adapter.bluetoothLeScanner?.startScan(null,
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), bleCallback)
                bleStarted = true
                adapter.startDiscovery()
            }
        } catch (_: SecurityException) {}
        catch (_: IllegalStateException) {}
    }
    @SuppressLint("MissingPermission")
    private fun readWifi(fresh: Boolean) {
        try {
            val nowMicros = SystemClock.elapsedRealtimeNanos() / 1_000L
            wifi.scanResults.forEach { item ->
                if (item.BSSID.isNullOrBlank()) return@forEach
                val key = "WIFI:" + item.BSSID
                val label = item.SSID.takeIf { it.isNotBlank() } ?: "Hidden Wi-Fi"
                // The AP list is still shown even if Android returned cached scan results.
                // A spatial measurement must have a fresh timestamp and a completed scan.
                val timestamp = item.timestamp
                val newTimestamp = timestamp > (wifiLastSeen[key] ?: 0L)
                val recent = timestamp > 0 && nowMicros - timestamp in 0L..15_000_000L
                val acceptSample = fresh && newTimestamp && recent
                if (newTimestamp) wifiLastSeen[key] = timestamp
                store.upsert(key, label, "WIFI", item.level, item.frequency, motion.snapshot(), acceptSample)
            }
            onChange()
        } catch (_: SecurityException) {}
    }
    @SuppressLint("MissingPermission")
    fun stop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        try { if (bleStarted) adapter?.bluetoothLeScanner?.stopScan(bleCallback) } catch (_: Exception) {}
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
        bleStarted = false
        if (registered) { try { context.unregisterReceiver(receiver) } catch (_: Exception) {}; registered = false }
    }
}
