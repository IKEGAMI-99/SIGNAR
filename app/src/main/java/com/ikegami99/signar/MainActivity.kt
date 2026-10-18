package com.ikegami99.signar

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.*

private val NAVY = Color.rgb(8, 19, 29)
private val PANEL = Color.rgb(19, 36, 49)
private val CYAN = Color.rgb(53, 231, 255)
private val MINT = Color.rgb(66, 245, 200)
private val AMBER = Color.rgb(255, 200, 87)
private val WHITE = Color.rgb(217, 244, 255)

class MainActivity : Activity() {
    private val handler=Handler(Looper.getMainLooper())
    private val signals=SignalStore()
    private lateinit var motion: MotionTracker
    private lateinit var scanner: RadioScanner
    private lateinit var updater: GithubUpdater
    private lateinit var content: FrameLayout
    private lateinit var status: TextView
    private var activeTab = "RADAR"
    private var lastTab = "RADAR"
    private var requestedMotionPermission = false
    private var radarProgress: TextView? = null
    private var radarSignalList: LinearLayout? = null
    private val heartbeat = object : Runnable {
        override fun run() {
            if (foreground) {
                refreshScreen()
                handler.postDelayed(this, 2500L)
            }
        }
    }
    private var arView: ARSignalView? = null
    private var pendingUpdate: UpdateInfo? = null
    private var updateButton: Button? = null
    private var updateStatus: TextView? = null
    private var radarView: RadarView? = null
    private var listView: LinearLayout? = null
    private var refreshQueued = false
    private var foreground=false
    private val radarScales = intArrayOf(5, 10, 20, 40, 80, 160)
    private val radarLimits = intArrayOf(5, 10, 20, 50)
    private var scaleIndex = 2
    private var limitIndex = 1
    private var showUncertainty = false
    private var scaleLabel: TextView? = null
    private var limitLabel: TextView? = null
    private var uncertaintyButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Target SDK 35 draws behind system bars. Explicit insets keep the header
        // below status icons and the bottom status panel above gesture navigation.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor=Color.TRANSPARENT
        window.navigationBarColor=Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars=false
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars=false
        val preferences=getSharedPreferences("display",MODE_PRIVATE)
        scaleIndex=preferences.getInt("radar_scale",2).coerceIn(0,radarScales.lastIndex)
        limitIndex=preferences.getInt("radar_limit",1).coerceIn(0,radarLimits.lastIndex)
        showUncertainty=preferences.getBoolean("radar_uncertainty",false)
        motion=MotionTracker(this)
        scanner=RadioScanner(this,signals,motion) { scheduleRefresh() }
        updater=GithubUpdater(this) {
            status.text=it
            updateStatus?.text=it
        }
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(NAVY) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sys=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(0,sys.top,0,sys.bottom)
            insets
        }
        val header=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(15),dp(18),dp(6))
        }
        header.addView(text("◉  SIGNAR",27, MINT,true))
        header.addView(text("SEE THE INVISIBLE  /  SIGNAL MAPPING",10,CYAN))
        root.addView(header)
        val tabs=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            setPadding(dp(8),dp(12),dp(8),dp(10))
        }
        listOf("RADAR" to "レーダー", "AR" to "ARカメラ", "DEVICES" to "デバイス", "UPDATE" to "更新").forEach { (tag,title) ->
            val b=button(title) { activeTab=tag;renderTab() }
            val p=LinearLayout.LayoutParams(0,dp(43),1f).apply { setMargins(dp(3),0,dp(3),0) }
            tabs.addView(b,p)
        }
        root.addView(tabs)
        content=FrameLayout(this)
        root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        status=text("権限の確認をしてください。スキャンはこのアプリの表示中だけ動きます。",11, WHITE)
        status.setPadding(dp(14),dp(10),dp(14),dp(14))
        status.setBackgroundColor(PANEL)
        root.addView(status)
        setContentView(root)
        renderTab()
        ensurePermissions(false)
    }

    private fun text(t:String,size:Int,color:Int,bold:Boolean=false)=TextView(this).apply {
        text=t;textSize=size.toFloat();setTextColor(color)
        if (bold) typeface=Typeface.DEFAULT_BOLD
    }
    private fun dp(v:Int)=(resources.displayMetrics.density*v).toInt()
    private fun button(t:String, action:()->Unit)=Button(this).apply {
        text=t;textSize=12f;isAllCaps=false;setTextColor(WHITE)
        background=GradientDrawable().apply { cornerRadius=dp(10).toFloat();setColor(PANEL);setStroke(dp(1), Color.rgb(32,85,98)) }
        setOnClickListener { action() }
    }
    private fun heading(t: String)=text(t,19,WHITE,true).apply { setPadding(dp(16),dp(10),dp(12),dp(12)) }
    private fun renderTab() {
        if (!::content.isInitialized) return
        // AR and walking measurements are isolated in SignalStore.
        // Do not throw away the accumulated radar observations on tab switches.
        lastTab = activeTab
        arView?.pause()
        content.removeAllViews()
        radarView=null
        scaleLabel=null
        limitLabel=null
        uncertaintyButton=null
        radarProgress=null
        radarSignalList=null
        listView=null
        updateButton=null
        updateStatus=null
        when(activeTab) {
            "RADAR" -> showRadar()
            "AR" -> showAR()
            "DEVICES" -> showDevices()
            else -> showUpdates()
        }
    }
    private fun showRadar() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(heading("空間レーダー"))
        panel.addView(text("点＝推定位置。誤差円と表示件数は切替可能です。",12,WHITE)
            .apply { setPadding(dp(16),0,dp(16),dp(7)) })

        val zoom=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(12),0,dp(12),dp(6))
        }
        val minus=button("−") { changeRadarScale(-1) }
        zoom.addView(minus,LinearLayout.LayoutParams(dp(54),dp(43)))
        scaleLabel=text("",16,MINT,true).apply { gravity=Gravity.CENTER }
        zoom.addView(scaleLabel,LinearLayout.LayoutParams(0,dp(43),1f))
        val plus=button("+") { changeRadarScale(1) }
        zoom.addView(plus,LinearLayout.LayoutParams(dp(54),dp(43)))
        panel.addView(zoom)

        val options=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(12),0,dp(12),dp(7))
        }
        val reduce=button("−") { changeRadarLimit(-1) }
        options.addView(reduce,LinearLayout.LayoutParams(dp(44),dp(39)))
        limitLabel=text("",12,WHITE,true).apply { gravity=Gravity.CENTER }
        options.addView(limitLabel,LinearLayout.LayoutParams(0,dp(39),1f))
        options.addView(button("+") { changeRadarLimit(1) }, LinearLayout.LayoutParams(dp(44),dp(39)))
        uncertaintyButton=button("") {
            showUncertainty=!showUncertainty
            getSharedPreferences("display",MODE_PRIVATE).edit().putBoolean("radar_uncertainty",showUncertainty).apply()
            updateRadarControls()
        }
        options.addView(uncertaintyButton,LinearLayout.LayoutParams(dp(114),dp(39)).apply { leftMargin=dp(8) })
        panel.addView(options)

        radarView = RadarView(this,signals,motion) {
            RadarOptions(radarScales[scaleIndex].toDouble(),radarLimits[limitIndex],showUncertainty)
        }
        val display=resources.displayMetrics
        val radarHeight=(display.heightPixels/display.density*0.38f).toInt().coerceIn(215,330)
        panel.addView(radarView,LinearLayout.LayoutParams(-1,dp(radarHeight)))
        val counts=text("",12,MINT).apply {
            tag="counts"
            setPadding(dp(15),dp(8),dp(15),dp(4))
        }
        panel.addView(counts)
        radarProgress = text("",12,WHITE).apply { setPadding(dp(15),dp(4),dp(15),dp(8)) }
        panel.addView(radarProgress)
        panel.addView(text("近くで受信中  /  方向未推定の電波も表示",13,CYAN,true)
            .apply { setPadding(dp(15),0,dp(15),dp(8)) })
        radarSignalList=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),0,dp(12),dp(12))
        }
        val scroll = ScrollView(this).apply { addView(radarSignalList) }
        panel.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        content.addView(panel)
        updateRadarControls()
        refreshScreen()
    }
    private fun changeRadarScale(direction: Int) {
        scaleIndex=(scaleIndex+direction).coerceIn(0,radarScales.lastIndex)
        getSharedPreferences("display",MODE_PRIVATE).edit().putInt("radar_scale",scaleIndex).apply()
        updateRadarControls()
    }
    private fun changeRadarLimit(direction: Int) {
        limitIndex=(limitIndex+direction).coerceIn(0,radarLimits.lastIndex)
        getSharedPreferences("display",MODE_PRIVATE).edit().putInt("radar_limit",limitIndex).apply()
        updateRadarControls()
    }
    private fun updateRadarControls() {
        scaleLabel?.text="半径 ${radarScales[scaleIndex]}m"
        limitLabel?.text="表示 最大${radarLimits[limitIndex]}件"
        uncertaintyButton?.text=if(showUncertainty) "誤差円 ON" else "誤差円 OFF"
        radarView?.invalidate()
    }
    private fun showDevices() {
        val view=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val top=heading("周辺デバイス")
        view.addView(top)
        val list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,dp(12),dp(12)) }
        listView=list
        view.addView(ScrollView(this).apply { addView(list) })
        content.addView(view)
        refreshScreen()
    }
    private fun showAR() {
        if (!hasPermissions(true)) {
            val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
            column.addView(heading("ARカメラ"))
            column.addView(text("AR表示にはカメラ権限が必要です。",14,WHITE))
            column.addView(button("カメラを許可") { ensurePermissions(true) })
            content.addView(column)
            return
        }
        if (arView == null) arView=ARSignalView(this,signals,motion) { status.text=it }
        val view=arView!!
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        content.addView(view,FrameLayout.LayoutParams(-1,-1))
        if (foreground) {
            view.resume(this)
        }
    }
    private fun showUpdates() {
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(10),dp(18),dp(12)) }
        layout.addView(heading("GitHub Releases / アプリ更新"))
        layout.addView(text("現在のバージョン  v"+BuildConfig.VERSION_NAME,15,MINT))
        layout.addView(text("更新APKはSHA-256とインストール済みアプリの署名を検証します。インストールにはAndroidの確認が必要です。",13,WHITE)
            .apply { setPadding(0,dp(18),0,dp(20)) })
        layout.addView(button("アップデートを確認") {
            updater.check { info ->
                pendingUpdate=info
                updateButton?.visibility=if(info!=null) View.VISIBLE else View.GONE
            }
        }.apply { layoutParams=LinearLayout.LayoutParams(-1,dp(52)) })
        updateButton=button("ダウンロードして更新") { pendingUpdate?.let { updater.downloadAndInstall(it) } }.apply {
            visibility=if(pendingUpdate==null) View.GONE else View.VISIBLE
        }
        layout.addView(updateButton,LinearLayout.LayoutParams(-1,dp(52)).apply { topMargin=dp(12) })
        layout.addView(button("ダウンロード済みAPKをインストール") { updater.retryInstall() },
            LinearLayout.LayoutParams(-1,dp(52)).apply { topMargin=dp(12) })
        updateStatus=text("リリースはGitHubの最新の安定版を取得します。",13,WHITE)
        layout.addView(updateStatus,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(20) })
        content.addView(layout)
    }
    private fun refreshScreen() {
        val now=System.currentTimeMillis()
        val detected=signals.list().filter { now - it.seenAt < 5*60*1000 }
        radarView?.invalidate()
        val estimates=detected.associate { it.id to signals.estimate(it) }
        (radarView?.parent as? LinearLayout)?.findViewWithTag<TextView>("counts")?.text =
            "Wi-Fi ${detected.count { it.kind=="WIFI" }}   BLE ${detected.count { it.kind=="BLE" }}" +
            "   BT ${detected.count { it.kind=="BT" }}   推定 ${estimates.values.count { it != null }}"
        val pose=motion.snapshot()
        val uniqueSites=mutableListOf<Sample>()
        detected.flatMap { it.readings.takeLast(15) }.forEach { sample ->
            if (uniqueSites.none { hypot(it.x-sample.x,it.z-sample.z) > 0.0 && hypot(it.x-sample.x,it.z-sample.z) < .9 || (it.x==sample.x && it.z==sample.z) }) {
                uniqueSites.add(sample)
            }
        }
        val maxBaseline = if (uniqueSites.isEmpty()) 0.0 else uniqueSites.maxOf { a ->
            uniqueSites.maxOf { b -> hypot(a.x-b.x,a.z-b.z) }
        }
        radarProgress?.text = "歩数 ${motion.steps()}歩  /  推定歩行距離 ${"%.1f".format(motion.travelled())}m" +
            "  /  観測地点 ${uniqueSites.size}  /  最大移動幅 ${"%.1f".format(maxBaseline)}m\n" +
            (if (maxBaseline<2.0) "移動しながら複数地点で測定してください（目安：3〜5m以上）"
             else "測定中。位置が特定できない電波は強度のみ表示します")
        radarSignalList?.let { list ->
            list.removeAllViews()
            val strongest=detected.sortedByDescending { it.rssi }.take(8)
            strongest.forEach { device ->
                val estimate=estimates[device.id]
                val row=LinearLayout(this).apply {
                    orientation=LinearLayout.VERTICAL
                    setPadding(dp(12),dp(8),dp(12),dp(8))
                    background=GradientDrawable().apply { setColor(PANEL);cornerRadius=dp(8).toFloat() }
                }
                row.addView(text("${if(device.kind=="WIFI") "◉" else "●"} ${device.label.take(32)}",13,if(device.kind=="WIFI") CYAN else MINT,true))
                row.addView(text("${device.kind}  ${device.rssi} dBm  /  ${signals.signalBand(device.rssi)}  " +
                    (estimate?.let { "位置推定・不確実性 約${it.radius.roundToInt()}m" } ?: "方向不明 / 観測 ${device.readings.size}回"),11,WHITE))
                list.addView(row,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(5) })
            }
        }
        listView?.let { list ->
            list.removeAllViews()
            if (detected.isEmpty()) list.addView(text("探索中です。位置情報とBluetoothを有効にしてください。",14,WHITE))
            for (d in detected.take(150)) {
                val estimated=estimates[d.id]
                val row=LinearLayout(this).apply {
                    orientation=LinearLayout.VERTICAL
                    setPadding(dp(15),dp(12),dp(15),dp(12))
                    background=GradientDrawable().apply { setColor(PANEL);cornerRadius=dp(12).toFloat() }
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity).setTitle(d.label)
                            .setMessage("種類: "+d.kind+"\nID: "+d.id+"\nRSSI: "+d.rssi+" dBm\n周波数: "+d.mhz+
                                " MHz\n有効な測定数: "+d.readings.size+"\n位置推定: "+
                                (estimated?.let { "大まかな不確実性 約"+it.radius.roundToInt()+" m（推定スコア "+it.confidence+"）" } ?: "方向不明・測定継続が必要"))
                            .setPositiveButton("閉じる",null).show()
                    }
                }
                row.addView(text((if(d.kind=="WIFI") "◉ " else "● ")+d.label,15,
                    if(d.kind=="WIFI") CYAN else MINT,true))
                row.addView(text(d.kind+"   "+d.rssi+" dBm  •  "+
                    (estimated?.let { "推定エリア 約"+it.radius.roundToInt()+"m" } ?: "方向未推定 / 測定 "+d.readings.size+"回"),12,WHITE))
                list.addView(row, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(7) })
            }
        }
    }
    private fun scheduleRefresh() {
        if (refreshQueued) return
        refreshQueued=true
        handler.postDelayed({ refreshQueued=false;refreshScreen() },1200)
    }
    private fun required(camera:Boolean):Array<String> {
        val perms=mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if(Build.VERSION.SDK_INT>=31) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if(Build.VERSION.SDK_INT>=33) perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if(camera) perms.add(Manifest.permission.CAMERA)
        return perms.toTypedArray()
    }
    private fun hasPermissions(camera:Boolean)=required(camera).all { checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED }
    private fun offerMotionPermission() {
        if (Build.VERSION.SDK_INT >= 29 && !requestedMotionPermission &&
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            requestedMotionPermission = true
            requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 43)
        }
    }
    private fun ensurePermissions(camera:Boolean) {
        val missing=required(camera).filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }
        if(missing.isNotEmpty()) requestPermissions(missing.toTypedArray(),42)
        else {
            offerMotionPermission()
            if(camera && activeTab=="AR") renderTab()
            else if(foreground) scanner.start()
        }
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if (requestCode==42) {
            if(hasPermissions(false) && foreground) scanner.start()
            if(activeTab=="AR" && hasPermissions(true)) renderTab()
            if(!hasPermissions(false)) status.text="Wi-Fi/Bluetoothを探索するには周辺デバイスと位置情報の権限が必要です"
            else offerMotionPermission()
        } else if (requestCode==43) {
            motion.stop()
            motion.start()
            scheduleRefresh()
        }
    }
    override fun onResume() {
        super.onResume()
        foreground=true
        motion.start()
        if(hasPermissions(false)) scanner.start()
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
        if(activeTab=="AR" && hasPermissions(true)) arView?.resume(this)
    }
    override fun onPause() {
        arView?.pause()
        handler.removeCallbacks(heartbeat)
        scanner.stop()
        motion.stop()
        foreground=false
        super.onPause()
    }
    override fun onDestroy() {
        arView?.release()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

private data class RadarOptions(
    val scaleMeters: Double,
    val maxDevices: Int,
    val uncertainty: Boolean
)

private class RadarView(
    context: Context,
    private val store: SignalStore,
    private val motion: MotionTracker,
    private val options: () -> RadarOptions
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private data class Marker(
        val x: Float, val y: Float, val label: String, val kind: String,
        val radius: Float, val rssi: Int
    )
    private data class Cluster(val centerX: Float, val centerY: Float, val main: Marker, var count: Int = 1)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val scale = options()
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * .43f
        canvas.drawColor(NAVY)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = Color.rgb(31,89,100)
        for (r in 1..4) canvas.drawCircle(cx,cy,radius*r/4,paint)
        canvas.drawLine(cx-radius,cy,cx+radius,cy,paint)
        canvas.drawLine(cx,cy-radius,cx,cy+radius,paint)
        paint.style = Paint.Style.FILL
        paint.color = MINT
        canvas.drawCircle(cx,cy,4f*density,paint)
        paint.color = WHITE
        paint.textSize = 11f * resources.displayMetrics.scaledDensity
        canvas.drawText("YOU",cx+7f*density,cy+15f*density,paint)
        paint.color = Color.rgb(100,160,169)
        paint.textSize = 11f * resources.displayMetrics.scaledDensity
        canvas.drawText("${scale.scaleMeters.toInt()}m",cx+radius-42f*density,cy-10f*density,paint)

        val pose = motion.snapshot()
        val now = System.currentTimeMillis()
        val markers = store.list().asSequence()
            .filter { now - it.seenAt < 300_000 }
            .mapNotNull { device ->
                val estimate = store.estimate(device) ?: return@mapNotNull null
                val dx=estimate.x-pose.x
                val dz=estimate.z-pose.z
                if (hypot(dx,dz) > scale.scaleMeters) return@mapNotNull null
                val x=cx+(dx/scale.scaleMeters*radius).toFloat()
                val y=cy+(dz/scale.scaleMeters*radius).toFloat()
                Marker(x,y,device.label,device.kind,
                    (estimate.radius/scale.scaleMeters*radius).toFloat(),
                    device.rssi)
            }
            .sortedByDescending { it.rssi }
            .take(scale.maxDevices)
            .toList()
        // Nearby device markers are grouped visually, rather than painting
        // overlapping names at identical positions.
        val clusters=mutableListOf<Cluster>()
        for (marker in markers) {
            val cluster=clusters.firstOrNull {
                hypot(it.centerX-marker.x,it.centerY-marker.y) < 17f*density
            }
            if (cluster != null) cluster.count++
            else clusters.add(Cluster(marker.x,marker.y,marker))
        }
        for (cluster in clusters) {
            val marker=cluster.main
            paint.color=when(marker.kind) { "WIFI" -> CYAN; "BLE" -> MINT; else -> AMBER }
            if (scale.uncertainty) {
                paint.style=Paint.Style.STROKE
                paint.strokeWidth=1.15f*density
                paint.alpha=85
                canvas.drawCircle(marker.x,marker.y,marker.radius.coerceIn(10f*density,radius*1.3f),paint)
                paint.alpha=255
            }
            paint.style=Paint.Style.FILL
            canvas.drawCircle(marker.x,marker.y,if (cluster.count>1) 8f*density else 5.5f*density,paint)
            if (cluster.count>1) {
                paint.color=NAVY
                paint.textAlign=Paint.Align.CENTER
                paint.typeface=Typeface.DEFAULT_BOLD
                paint.textSize=9f*resources.displayMetrics.scaledDensity
                canvas.drawText(cluster.count.toString(),marker.x,marker.y+3f*density,paint)
                paint.textAlign=Paint.Align.LEFT
                paint.typeface=Typeface.DEFAULT
            }
        }
        // Show a small number of readable labels, avoiding label collisions.
        val occupied=mutableListOf<RectF>()
        var labeled=0
        for (cluster in clusters) {
            if (labeled>=4) break
            val marker=cluster.main
            val caption=marker.label.take(15)+(if(cluster.count>1) " +${cluster.count-1}" else "")
            paint.textSize=11f*resources.displayMetrics.scaledDensity
            val textWidth=paint.measureText(caption)
            val labelWidth=textWidth+14f*density
            val labelHeight=24f*density
            val lx=(marker.x+9f*density).coerceIn(3f*density,
                max(3f*density,width-labelWidth-3f*density))
            val ly=(marker.y-labelHeight-10f*density).coerceIn(3f*density,
                max(3f*density,height-labelHeight-3f*density))
            val rect=RectF(lx,ly,lx+labelWidth,ly+labelHeight)
            if (occupied.any { RectF.intersects(it,rect) }) continue
            occupied.add(rect)
            paint.style=Paint.Style.FILL
            paint.color=Color.argb(225,12,32,43)
            canvas.drawRoundRect(rect,6f*density,6f*density,paint)
            paint.color=when(marker.kind) { "WIFI"->CYAN;"BLE"->MINT;else->AMBER }
            canvas.drawText(caption,lx+7f*density,ly+16f*density,paint)
            labeled++
        }
        if (markers.isEmpty()) {
            paint.style=Paint.Style.FILL
            paint.color=WHITE
            paint.textSize=13f*resources.displayMetrics.scaledDensity
            val line="この範囲に推定位置がありません"
            canvas.drawText(line,cx-paint.measureText(line)/2,cy-radius*.45f,paint)
        }
        paint.color=Color.rgb(134,178,188)
        paint.textSize=10f*resources.displayMetrics.scaledDensity
        canvas.drawText("表示 ${markers.size}件  /  範囲 半径${scale.scaleMeters.toInt()}m",
            10f*density,height-10f*density,paint)
    }
}
