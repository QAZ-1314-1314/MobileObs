package com.example.mobileobs

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 畫面圖層。座標以「畫布(16:9)」為基準：
 * x,y = 圖層中心(0..1，左上為原點)；w,h = 佔畫布寬/高的比例。
 */
open class Layer(val name: String) {
    @Volatile var x = 0.5f
    @Volatile var y = 0.5f
    @Volatile var w = 1f
    @Volatile var h = 1f
    @Volatile var visible = true
    @Volatile var locked = false          // 鎖定=不能用手勢拖曳(背景)
    @Volatile var aspect = 16f / 9f       // 來源內容像素寬高比(未旋轉、未裁切)
    @Volatile var rotation = 0            // 0/90/180/270，順時針
    @Volatile var flipH = false           // 水平鏡像
    @Volatile var cropL = 0f              // 裁切比例 0..0.45，以「旋轉後看到的畫面」的邊為準
    @Volatile var cropT = 0f
    @Volatile var cropR = 0f
    @Volatile var cropB = 0f
    internal var texId = 0

    /** 旋轉並裁切後，實際顯示內容的寬高比 */
    fun effAspect(): Float {
        val base = if (rotation % 180 != 0) 1f / aspect else aspect
        return base * (1f - cropL - cropR) / (1f - cropT - cropB)
    }
    fun keepAspectForWidth() { h = w * 16f / (9f * effAspect()) }
    /** 以寬 maxW 放入畫布，不超出 */
    fun fit(maxW: Float) { w = maxW; keepAspectForWidth(); if (h > 1f) { w /= h; h = 1f } }
    /** 鋪滿畫布(會裁切) */
    fun cover() { w = 1f; keepAspectForWidth(); if (h < 1f) { w /= h; h = 1f } }
    fun resetAdjust() { rotation = 0; flipH = false; cropL = 0f; cropT = 0f; cropR = 0f; cropB = 0f }

    /** 顯示座標(0..1) → 來源座標 的 4x4 矩陣(列主序)。順序：裁切 → 水平鏡像 → 以中心旋轉 */
    internal fun uvMatrix(): FloatArray {
        var a = 1f - cropL - cropR; val d = 1f - cropT - cropB
        var tx = cropL; val ty = cropB
        var c = 0f
        val b = 0f
        if (flipH) { a = -a; c = -c; tx = 1f - tx }
        val rad = Math.toRadians(rotation.toDouble())
        val co = Math.round(Math.cos(rad)).toFloat()
        val si = Math.round(Math.sin(rad)).toFloat()
        val na = co * a - si * b; val nc = co * c - si * d
        val ntx = co * (tx - 0.5f) - si * (ty - 0.5f) + 0.5f
        val nb = si * a + co * b; val nd = si * c + co * d
        val nty = si * (tx - 0.5f) + co * (ty - 0.5f) + 0.5f
        return floatArrayOf(na, nb, 0f, 0f, nc, nd, 0f, 0f, 0f, 0f, 1f, 0f, ntx, nty, 0f, 1f)
    }
}

class VideoLayer(name: String) : Layer(name) {
    lateinit var surfaceTexture: SurfaceTexture
    lateinit var surface: Surface
    internal val tm = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    @Volatile internal var ready = false
    @Volatile internal var newFrame = false
}

class BitmapLayer(name: String) : Layer(name) {
    @Volatile internal var pending: Bitmap? = null
    internal var uploaded = false
}

/** OpenGL 合成器：把所有圖層畫到「預覽」與「編碼器」兩個 Surface */
class GlCompositor {
    companion object {
        private val FLIP = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
        private val VERTS = floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }

    private val thread = HandlerThread("gl-compositor").apply { start() }
    private val handler = Handler(thread.looper)
    val layers = CopyOnWriteArrayList<Layer>()
    @Volatile private var loopFps = 30

    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var cfg: EGLConfig? = null
    private var pbuf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var prevSurf: EGLSurface = EGL14.EGL_NO_SURFACE
    private class Enc(val surf: EGLSurface, val w: Int, val h: Int, val startNs: Long, val fps: Int) { var nextNs = 0L }
    private val encs = LinkedHashMap<String, Enc>()
    private var prevW = 1; private var prevH = 1
    private var progOes = 0
    private var progTex = 0
    @Volatile private var running = false
    private val vbuf = ByteBuffer.allocateDirect(VERTS.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(VERTS); position(0) }

    // ---------- 對外 API ----------
    fun start() { handler.post { initEgl(); running = true; loop() } }

    fun setPreview(s: Surface?, w: Int, h: Int) = runSync {
        if (prevSurf != EGL14.EGL_NO_SURFACE) { EGL14.eglDestroySurface(dpy, prevSurf); prevSurf = EGL14.EGL_NO_SURFACE }
        if (s != null) {
            prevSurf = EGL14.eglCreateWindowSurface(dpy, cfg, s, intArrayOf(EGL14.EGL_NONE), 0)
            prevW = w; prevH = h
        }
    }

    /** 可同時有多個編碼輸出(錄影 "rec"、直播 "stream")，各自有尺寸與 fps */
    fun setEncoder(key: String, s: Surface, w: Int, h: Int, startNs: Long, fps: Int) = runSync {
        encs.remove(key)?.let { EGL14.eglDestroySurface(dpy, it.surf) }
        val surf = EGL14.eglCreateWindowSurface(dpy, cfg, s, intArrayOf(EGL14.EGL_NONE), 0)
        encs[key] = Enc(surf, w, h, startNs, fps)
        loopFps = if (encs.values.any { it.fps > 30 }) 60 else 30
    }

    fun clearEncoder(key: String) = runSync {
        encs.remove(key)?.let { EGL14.eglDestroySurface(dpy, it.surf) }
        loopFps = if (encs.values.any { it.fps > 30 }) 60 else 30
    }

    fun addVideoLayer(name: String): VideoLayer {
        val l = VideoLayer(name)
        runSync {
            l.texId = genTex(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            l.surfaceTexture = SurfaceTexture(l.texId)
            l.surface = Surface(l.surfaceTexture)
            l.surfaceTexture.setOnFrameAvailableListener { l.ready = true; l.newFrame = true }
            layers.add(l)
        }
        return l
    }

    fun addBitmapLayer(name: String, index: Int = -1): BitmapLayer {
        val l = BitmapLayer(name)
        runSync {
            l.texId = genTex(GLES20.GL_TEXTURE_2D)
            if (index in 0..layers.size) layers.add(index, l) else layers.add(l)
        }
        return l
    }

    fun setBitmap(l: BitmapLayer, bmp: Bitmap) { l.pending = bmp }
    fun bringToFront(l: Layer) { if (layers.remove(l)) layers.add(l) }

    fun removeLayer(l: Layer) {
        handler.post {
            layers.remove(l)
            if (l.texId != 0) GLES20.glDeleteTextures(1, intArrayOf(l.texId), 0)
            if (l is VideoLayer) { l.surface.release(); l.surfaceTexture.release() }
        }
    }

    fun release() {
        running = false
        runSync {
            layers.clear()
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            for (s in listOf(prevSurf, pbuf) + encs.values.map { it.surf }) if (s != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, s)
            encs.clear()
            EGL14.eglDestroyContext(dpy, ctx)
            EGL14.eglTerminate(dpy)
        }
        thread.quitSafely()
    }

    // ---------- 內部 ----------
    private fun runSync(block: () -> Unit) {
        val latch = CountDownLatch(1)
        handler.post { try { block() } finally { latch.countDown() } }
        latch.await(3, TimeUnit.SECONDS)
    }

    private fun loop() {
        if (!running) return
        val t0 = System.nanoTime()
        try { drawFrame() } catch (e: Exception) { e.printStackTrace() }
        val spent = (System.nanoTime() - t0) / 1_000_000
        handler.postDelayed({ loop() }, maxOf(1L, 1000L / loopFps - spent))
    }

    private fun initEgl() {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2); EGL14.eglInitialize(dpy, v, 0, v, 1)
        val attr = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(dpy, attr, 0, cfgs, 0, 1, n, 0)
        cfg = cfgs[0]
        ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        pbuf = EGL14.eglCreatePbufferSurface(dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)
        progOes = buildProgram(true)
        progTex = buildProgram(false)
    }

    private fun genTex(target: Int): Int {
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(target, t[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    private fun drawFrame() {
        EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)
        for (l in layers) {
            if (l is VideoLayer && l.newFrame) {
                l.newFrame = false
                try { l.surfaceTexture.updateTexImage(); l.surfaceTexture.getTransformMatrix(l.tm) } catch (_: Exception) {}
            } else if (l is BitmapLayer) {
                val b = l.pending
                if (b != null) {
                    l.pending = null
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, l.texId)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
                    l.uploaded = true
                }
            }
        }
        if (prevSurf != EGL14.EGL_NO_SURFACE) render(prevSurf, prevW, prevH, -1L)
        val now = System.nanoTime()
        for (e in encs.values) {
            val interval = 1_000_000_000L / e.fps
            if (now >= e.nextNs - interval / 4) {
                render(e.surf, e.w, e.h, now - e.startNs)
                e.nextNs = if (now - e.nextNs > interval) now + interval else e.nextNs + interval
            }
        }
    }

    private fun render(surf: EGLSurface, w: Int, h: Int, ptsNs: Long) {
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        for (l in layers) if (l.visible) drawLayer(l)
        if (ptsNs >= 0) EGLExt.eglPresentationTimeANDROID(dpy, surf, ptsNs)
        EGL14.eglSwapBuffers(dpy, surf)
    }

    private fun drawLayer(l: Layer) {
        val video = l as? VideoLayer
        if (video != null && !video.ready) return
        if (l is BitmapLayer && !l.uploaded) return
        val prog = if (video != null) progOes else progTex
        val target = if (video != null) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        GLES20.glUseProgram(prog)
        val texM = FloatArray(16)
        android.opengl.Matrix.multiplyMM(texM, 0, video?.tm ?: FLIP, 0, l.uvMatrix(), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uTex"), 1, false, texM, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uScale"), l.w, l.h)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uOff"), l.x * 2f - 1f, 1f - l.y * 2f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, l.texId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uSampler"), 0)
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aTex = GLES20.glGetAttribLocation(prog, "aTex")
        vbuf.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vbuf)
        GLES20.glEnableVertexAttribArray(aPos)
        vbuf.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, vbuf)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun buildProgram(oes: Boolean): Int {
        val vs = "attribute vec2 aPos; attribute vec2 aTex; uniform vec2 uScale; uniform vec2 uOff; uniform mat4 uTex;" +
            "varying vec2 vTex; void main(){ gl_Position = vec4(aPos*uScale+uOff, 0.0, 1.0); vTex = (uTex*vec4(aTex,0.0,1.0)).xy; }"
        val fs = if (oes)
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vTex; uniform samplerExternalOES uSampler; void main(){ gl_FragColor = texture2D(uSampler, vTex); }"
        else
            "precision mediump float; varying vec2 vTex; uniform sampler2D uSampler; void main(){ gl_FragColor = texture2D(uSampler, vTex); }"
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Shader error: " + GLES20.glGetShaderInfoLog(s))
        return s
    }
}
