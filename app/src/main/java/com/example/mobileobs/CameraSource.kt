package com.example.mobileobs

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.abs

/**
 * Camera2 影像來源：手機前/後鏡頭，以及 Android 以「外接相機」方式提供的 USB 採集卡(HDMI->USB UVC)。
 * 畫面直接寫進 VideoLayer 的 Surface。
 */
interface VideoSource { fun stop() }

class CameraSource(context: Context, private val id: String, private val layer: VideoLayer) : VideoSource {
    private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("cam-$id").apply { start() }
    private val h = Handler(thread.looper)
    private var device: CameraDevice? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("此相機沒有可用的輸出格式")
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)
        val best = sizes.minByOrNull {
            val ratioPenalty = if (abs(it.width / it.height.toFloat() - 16f / 9f) > 0.02f) 2_000_000 else 0
            abs(it.width * it.height - 1280 * 720) + ratioPenalty
        } ?: sizes[0]
        layer.surfaceTexture.setDefaultBufferSize(best.width, best.height)
        layer.aspect = best.width / best.height.toFloat()

        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                device = d
                val req = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(layer.surface) }.build()
                @Suppress("DEPRECATION")
                d.createCaptureSession(listOf(layer.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { s.setRepeatingRequest(req, null, h) }
                    override fun onConfigureFailed(s: CameraCaptureSession) { d.close() }
                }, h)
            }
            override fun onDisconnected(d: CameraDevice) { d.close() }
            override fun onError(d: CameraDevice, e: Int) { d.close() }
        }, h)
    }

    override fun stop() {
        try { device?.close() } catch (_: Exception) {}
        thread.quitSafely()
    }
}
