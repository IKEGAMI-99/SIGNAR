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
    @Synchronized fun resetMeasurements() { signals.values.forEach { it.readings.clear() } }
    @Synchronized fun upsert(id: String, label: String, kind: String, rssi: Int, mhz: Int, pose: SpatialPose) {
        val d = signals.getOrPut(id) { Signal(id, label, kind, rssi) }
        if (label.isNotBlank() && label != "<unknown>") d.label = label
        d.rssi = rssi
        d.mhz = mhz
        d.seenAt = System.currentTimeMillis()
        if (rssi !in -110..-15) return
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
    fun estimate(device: Signal): Estimated? {
        val samples = device.readings.takeLast(45)
        if (samples.size < 4) return null
        val distinct = mutableListOf<Sample>()
        for (s in samples) if (distinct.none { hypot(it.x - s.x, it.z - s.z) < .75 }) distinct.add(s)
        if (distinct.size < 3) return null
        val baseline = distinct.maxOf { a -> distinct.maxOf { b -> hypot(a.x - b.x, a.z - b.z) } }
        if (baseline < 1.8) return null
        val obs = samples.filter { System.currentTimeMillis() - it.time < 20 * 60 * 1000 }
        if (obs.size < 4) return null
        val cx = obs.map { it.x }.average()
        val cz = obs.map { it.z }.average()
        val cy = obs.map { it.y }.average()
        val range = min(22.0, max(7.0, baseline * 2.2))
        var bestLoss = Double.POSITIVE_INFINITY
        var bx = cx
        var bz = cz
        val iterations = 44
        for (ix in 0..iterations) {
            val x = cx - range + (2 * range * ix / iterations)
            for (iz in 0..iterations) {
                val z = cz - range + (2 * range * iz / iterations)
                var loss = 0.0
                for (s in obs) {
                    val measured = rssiDistance(s.rssi)
                    val model = max(.4, hypot(x - s.x, z - s.z))
                    val difference = ln(model / measured)
                    loss += min(6.0, difference * difference)
                }
                loss /= obs.size
                if (loss < bestLoss) { bestLoss = loss; bx = x; bz = z }
            }
        }
        if (bestLoss > 1.3) return null
        val uncertainty = (2.0 + sqrt(bestLoss) * 7.0 + 6.0 / baseline).coerceIn(2.0, 20.0)
        val confidence = ((1 - bestLoss / 1.3) * min(1.0, baseline / 6.0) * 70).roundToInt().coerceIn(5, 70)
        return Estimated(bx, cy, bz, uncertainty, confidence)
    }
    private fun rssiDistance(rssi: Int) = 10.0.pow((-46.0 - rssi.coerceIn(-105, -20)) / 24.0).coerceIn(.4, 35.0)
}

data class SpatialPose(val x: Double, val y: Double, val z: Double, val heading: Double, val ar: Boolean)

class MotionTracker(context: Context): SensorEventListener {
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
    @Synchronized fun snapshot(): SpatialPose = SpatialPose(x, y, z, heading, inAR)
    @Synchronized fun reset() { x = 0.0; y = 1.4; z = 0.0; inAR = false }
    @Synchronized fun setAR(x0: Float, y0: Float, z0: Float) { x = x0.toDouble(); y = y0.toDouble(); z = z0.toDouble(); inAR = true }
    @Synchronized fun setARInactive() { inAR = false }
    fun start() {
        rotation?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        val stepSensor = steps ?: accelerometer
        stepSensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }
    fun stop() { sensors.unregisterListener(this) }
    @Synchronized private fun step() {
        if (inAR) return
        // Relative pedestrian dead reckoning; stride is an estimate, not a measured distance.
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
            Sensor.TYPE_ACCELEROMETER -> if (steps == null) {
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
    private val loop = object: Runnable {
        override fun run() {
            if (!active) return
            try { wifi.startScan(); readWifi() } catch (_: SecurityException) {}
            handler.postDelayed(this, 32_000)
        }
    }
    private val receiver = object: BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> readWifi()
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
    private fun readWifi() {
        try {
            wifi.scanResults.forEach { item ->
                if (item.BSSID.isNullOrBlank()) return@forEach
                val ssid = item.SSID.takeIf { it.isNotBlank() } ?: "Hidden Wi-Fi"
                store.upsert("WIFI:" + item.BSSID, ssid, "WIFI", item.level, item.frequency, motion.snapshot())
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
