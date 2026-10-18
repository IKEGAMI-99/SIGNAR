package com.ikegami99.signar

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
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
    private var arView: ARSignalView? = null
    private var pendingUpdate: UpdateInfo? = null
    private var updateButton: Button? = null
    private var updateStatus: TextView? = null
    private var radarView: RadarView? = null
    private var listView: LinearLayout? = null
    private var refreshQueued = false
    private var foreground=false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=NAVY
        window.navigationBarColor=NAVY
        motion=MotionTracker(this)
        scanner=RadioScanner(this,signals,motion) { scheduleRefresh() }
        updater=GithubUpdater(this) {
            status.text=it
            updateStatus?.text=it
        }
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(NAVY) }
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
        if (lastTab == "AR" && activeTab != "AR") {
            // ARCore uses its own arbitrary world origin; never mix it with PDR samples.
            signals.resetMeasurements()
            motion.reset()
        }
        lastTab = activeTab
        arView?.pause()
        content.removeAllViews()
        radarView=null
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
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        panel.addView(heading("空間レーダー"))
        panel.addView(text("位置が推定できた電波源だけを表示します。円の大きさは推定誤差です。",12,WHITE).apply { setPadding(dp(16),0,dp(16),dp(10)) })
        radarView=RadarView(this,signals,motion)
        panel.addView(radarView,LinearLayout.LayoutParams(-1,0,1f))
        val counts=text("",12,MINT).apply { tag="counts";setPadding(dp(16),dp(6),0,dp(10)) }
        panel.addView(counts)
        content.addView(panel)
        refreshScreen()
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
            signals.resetMeasurements() // ARCore world origin is distinct from PDR coordinates.
            motion.reset()
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
        (radarView?.parent as? LinearLayout)?.findViewWithTag<TextView>("counts")?.text =
            "Wi-Fi "+detected.count { it.kind=="WIFI" }+"   BLE "+detected.count { it.kind=="BLE" }+
            "   Bluetooth "+detected.count { it.kind=="BT" }+
            "   位置推定 "+detected.count { signals.estimate(it)!=null }
        listView?.let { list ->
            list.removeAllViews()
            if (detected.isEmpty()) list.addView(text("探索中です。位置情報とBluetoothを有効にしてください。",14,WHITE))
            for (d in detected.take(150)) {
                val estimated=signals.estimate(d)
                val row=LinearLayout(this).apply {
                    orientation=LinearLayout.VERTICAL
                    setPadding(dp(15),dp(12),dp(15),dp(12))
                    background=GradientDrawable().apply { setColor(PANEL);cornerRadius=dp(12).toFloat() }
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity).setTitle(d.label)
                            .setMessage("種類: "+d.kind+"\nID: "+d.id+"\nRSSI: "+d.rssi+" dBm\n周波数: "+d.mhz+
                                " MHz\n位置推定: "+(estimated?.let { "約±"+it.radius.roundToInt()+" m（信頼度 "+it.confidence+"%）" } ?: "データ不足"))
                            .setPositiveButton("閉じる",null).show()
                    }
                }
                row.addView(text((if(d.kind=="WIFI") "◉ " else "● ")+d.label,15,
                    if(d.kind=="WIFI") CYAN else MINT,true))
                row.addView(text(d.kind+"   "+d.rssi+" dBm  •  "+(estimated?.let { "推定誤差 ±"+it.radius.roundToInt()+"m" } ?: "方向未推定"),12,WHITE))
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
    private fun ensurePermissions(camera:Boolean) {
        val missing=required(camera).filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }
        if(missing.isNotEmpty()) requestPermissions(missing.toTypedArray(),42)
        else if(camera && activeTab=="AR") renderTab()
        else if(foreground) scanner.start()
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if (requestCode==42) {
            if(hasPermissions(false) && foreground) scanner.start()
            if(activeTab=="AR" && hasPermissions(true)) renderTab()
            if(!hasPermissions(false)) status.text="Wi-Fi/Bluetoothを探索するには周辺デバイスと位置情報の権限が必要です"
        }
    }
    override fun onResume() {
        super.onResume()
        foreground=true
        motion.start()
        if(hasPermissions(false)) scanner.start()
        if(activeTab=="AR" && hasPermissions(true)) arView?.resume(this)
    }
    override fun onPause() {
        arView?.pause()
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

private class RadarView(context:Context, private val store:SignalStore,private val motion:MotionTracker): View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val cx=width/2f
        val cy=height/2f
        val radius=min(width,height)*.41f
        canvas.drawColor(NAVY)
        paint.style=Paint.Style.STROKE
        paint.strokeWidth=1.5f
        paint.color=Color.rgb(31,89,100)
        for (r in 1..4) canvas.drawCircle(cx,cy,radius*r/4,paint)
        canvas.drawLine(cx-radius,cy,cx+radius,cy,paint)
        canvas.drawLine(cx,cy-radius,cx,cy+radius,paint)
        paint.style=Paint.Style.FILL
        paint.color=MINT
        canvas.drawCircle(cx,cy,6f,paint)
        paint.color=WHITE
        paint.textSize=12f
        canvas.drawText("YOU",cx+10,cy+16,paint)
        paint.color=Color.rgb(100,160,169)
        canvas.drawText("20m",cx+radius-35,cy-10,paint)
        val pose=motion.snapshot()
        var shown=0
        store.list().filter { System.currentTimeMillis()-it.seenAt<300_000 }.take(70).forEach { d ->
            val estimate=store.estimate(d) ?: return@forEach
            val dx=estimate.x-pose.x;val dz=estimate.z-pose.z
            val distance=hypot(dx,dz)
            if(distance>20) return@forEach
            val x=cx+(dx/20*radius).toFloat()
            val y=cy+(dz/20*radius).toFloat()
            paint.color=if(d.kind=="WIFI") CYAN else if(d.kind=="BLE") MINT else AMBER
            paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f
            canvas.drawCircle(x,y,(estimate.radius/20*radius).toFloat().coerceAtLeast(9f),paint)
            paint.style=Paint.Style.FILL
            canvas.drawCircle(x,y,5f,paint)
            paint.textSize=12f
            canvas.drawText(d.label.take(12),x+8,y-10,paint)
            shown++
        }
        if(shown==0) {
            paint.color=WHITE;paint.textSize=14f
            val message="信号の推定位置を計測中"
            canvas.drawText(message,cx-paint.measureText(message)/2,cy+radius+28,paint)
            paint.textSize=11f;paint.color=Color.rgb(153,189,198)
            val caption="複数地点で測定すると表示されます"
            canvas.drawText(caption,cx-paint.measureText(caption)/2,cy+radius+48,paint)
        }
    }
}
