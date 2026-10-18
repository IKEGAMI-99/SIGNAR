package com.ikegami99.signar

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.widget.FrameLayout
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

data class ArMarker(val x: Float, val y: Float, val label: String, val rssi: Int, val uncertainty: Int)

class ARSignalView(context: Context, private val store: SignalStore,
                   private val motion: MotionTracker, private val onStatus: (String) -> Unit): FrameLayout(context) {
    private var session: Session? = null
    private var triedInstall = false
    private var running = false
    private val overlay = Overlay(context)
    private val renderer = CameraRenderer(this)
    private val surface = GLSurfaceView(context).apply {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }
    private val info = TextView(context).apply {
        text = "ARを初期化しています…"
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.argb(160, 5, 19, 29))
        setPadding(16, 14, 16, 14)
        textSize = 12f
    }
    init {
        addView(surface, LayoutParams(-1,-1))
        addView(overlay, LayoutParams(-1,-1))
        addView(info, LayoutParams(-1, -2, Gravity.TOP))
    }
    fun resume(activity: Activity) {
        if (running) return
        try {
            when (ArCoreApk.getInstance().requestInstall(activity, !triedInstall)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    triedInstall = true
                    onStatus("Google Play開発者サービス（AR）のインストールが必要です")
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> {}
            }
            if (session == null) session = Session(activity)
            session?.resume()
            session?.setDisplayGeometry(activity.windowManager.defaultDisplay.rotation,
                if (surface.width > 0) surface.width else 1,
                if (surface.height > 0) surface.height else 1)
            running = true
            surface.onResume()
            info.text = "歩きながらスキャンすると推定位置がAR表示されます。位置は目安です。"
        } catch (e: Exception) {
            onStatus("ARCoreを利用できません: " + (e.message ?: e.javaClass.simpleName))
            info.text = "ARCore非対応、または初期化に失敗しました。レーダーモードは使用できます。"
        }
    }
    fun pause() {
        if (running) {
            surface.onPause()
            try { session?.pause() } catch (_: Exception) {}
            running = false
        }
        motion.setARInactive()
    }
    fun release() { pause(); session?.close(); session = null }
    private class CameraRenderer(private val parent: ARSignalView): GLSurfaceView.Renderer {
        private var program = 0
        private var texture = 0
        private var frameIndex = 0
        private var w = 1
        private var h = 1
        private val quad = direct(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f))
        private val uv = direct(FloatArray(8))
        private val view = FloatArray(16)
        private val proj = FloatArray(16)
        private val vp = FloatArray(16)
        private val projected = FloatArray(4)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(.015f,.04f,.065f,1f)
            val vertex = compile(GLES20.GL_VERTEX_SHADER, "attribute vec2 a_Pos; attribute vec2 a_UV; varying vec2 v_UV; void main(){gl_Position=vec4(a_Pos,0.0,1.0);v_UV=a_UV;}")
            val fragment = compile(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 v_UV; uniform samplerExternalOES u_Tex; void main(){gl_FragColor=texture2D(u_Tex,v_UV);}")
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program,vertex); GLES20.glAttachShader(program,fragment)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
            val textures = IntArray(1)
            GLES20.glGenTextures(1,textures,0)
            texture = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        }
        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            w=width;h=height
            GLES20.glViewport(0,0,w,h)
            parent.session?.setDisplayGeometry((parent.context as? Activity)?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0,w,h)
        }
        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val session=parent.session ?: return
            try {
                session.setCameraTextureName(texture)
                val frame=session.update()
                if (frame.hasDisplayGeometryChanged()) {
                    quad.rewind();uv.rewind()
                    frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                        quad,Coordinates2d.TEXTURE_NORMALIZED,uv)
                    uv.rewind()
                }
                GLES20.glUseProgram(program)
                GLES20.glDepthMask(false)
                val loc=GLES20.glGetAttribLocation(program,"a_Pos")
                val uLoc=GLES20.glGetAttribLocation(program,"a_UV")
                GLES20.glEnableVertexAttribArray(loc)
                quad.rewind()
                GLES20.glVertexAttribPointer(loc,2,GLES20.GL_FLOAT,false,0,quad)
                GLES20.glEnableVertexAttribArray(uLoc)
                uv.rewind()
                GLES20.glVertexAttribPointer(uLoc,2,GLES20.GL_FLOAT,false,0,uv)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"u_Tex"),0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
                GLES20.glDisableVertexAttribArray(loc); GLES20.glDisableVertexAttribArray(uLoc)
                GLES20.glDepthMask(true)

                val camera=frame.camera
                if (camera.trackingState != TrackingState.TRACKING) return
                parent.motion.setAR(camera.pose.tx(),camera.pose.ty(),camera.pose.tz())
                if (++frameIndex % 12 != 0) return
                camera.getViewMatrix(view,0)
                camera.getProjectionMatrix(proj,0,0.1f,80f)
                Matrix.multiplyMM(vp,0,proj,0,view,0)
                val markers=mutableListOf<ArMarker>()
                parent.store.list().take(70).forEach { signal ->
                    val estimate=parent.store.estimate(signal) ?: return@forEach
                    Matrix.multiplyMV(projected,0,vp,0,floatArrayOf(estimate.x.toFloat(),estimate.y.toFloat(),estimate.z.toFloat(),1f),0)
                    if (projected[3] <= .01f) return@forEach
                    val nx=projected[0]/projected[3]
                    val ny=projected[1]/projected[3]
                    if (nx in -1.15f..1.15f && ny in -1.15f..1.15f) {
                        markers.add(ArMarker((nx+1)*w/2, (1-ny)*h/2,
                            signal.label.take(22),signal.rssi,estimate.radius.roundToInt()))
                    }
                }
                parent.overlay.post { parent.overlay.setMarkers(markers) }
            } catch (_: Exception) {
                // ARCore can temporarily lose camera tracking; keep the UI responsive.
            }
        }
        private fun compile(type: Int, src: String): Int {
            val shader=GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader,src);GLES20.glCompileShader(shader)
            return shader
        }
        private fun direct(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(4*a.size).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a);position(0) }
    }
    private class Overlay(context: Context): android.view.View(context) {
        private var markers: List<ArMarker> = emptyList()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun setMarkers(value: List<ArMarker>) { markers=value; invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            markers.forEach { m ->
                paint.style=Paint.Style.STROKE;paint.strokeWidth=2.5f;paint.color=Color.rgb(66,245,200)
                canvas.drawCircle(m.x,m.y,21f,paint)
                paint.style=Paint.Style.FILL
                canvas.drawCircle(m.x,m.y,4f,paint)
                val left=(m.x-85).coerceIn(4f,width-180f)
                val top=(m.y-66).coerceAtLeast(48f)
                paint.color=Color.argb(210,8,27,39)
                canvas.drawRoundRect(left,top,left+177,top+49,10f,10f,paint)
                paint.color=Color.WHITE;paint.textSize=13f
                canvas.drawText(m.label,left+9,top+19,paint)
                paint.color=Color.rgb(110,235,211);paint.textSize=11f
                canvas.drawText(m.rssi.toString()+" dBm  /  ±"+m.uncertainty+"m",left+9,top+36,paint)
            }
        }
    }
}
