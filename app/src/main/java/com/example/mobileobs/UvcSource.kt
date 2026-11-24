package com.example.mobileobs

import android.hardware.usb.UsbDevice
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size
import kotlin.math.abs

/**
 * USB 採集卡(UVC)直連。使用 UVCAndroid 函式庫，不經過 Android 的 Camera2。
 * 注意：這個檔案是整個專案唯一用到 UVCAndroid 的地方。
 * 若函式庫版本的 API 與此不同，只需要修改這個檔案。
 */
class UvcSource(
    private val device: UsbDevice,
    private val layer: VideoLayer,
    private val onMessage: (String) -> Unit
) : VideoSource {
    private var helper: ICameraHelper? = null
    private var selected = false

    fun start() {
        val h = CameraHelper()
        helper = h
        h.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(dev: UsbDevice?) { select(dev) }
            override fun onDeviceOpen(dev: UsbDevice?, isFirstOpen: Boolean) { h.openCamera() }
            override fun onCameraOpen(dev: UsbDevice?) {
                try {
                    val sizes: List<Size> = h.supportedSizeList ?: emptyList()
                    val best = sizes.minByOrNull {
                        val ratioPenalty = if (abs(it.width / it.height.toFloat() - 16f / 9f) > 0.02f) 2_000_000 else 0
                        abs(it.width * it.height - 1280 * 720) + ratioPenalty
                    }
                    if (best != null) {
                        h.setPreviewSize(best)
                        layer.surfaceTexture.setDefaultBufferSize(best.width, best.height)
                        layer.aspect = best.width / best.height.toFloat()
                        layer.keepAspectForWidth()
                    }
                    h.startPreview()
                    h.addSurface(layer.surface, false)
                } catch (e: Exception) { onMessage("UVC 預覽失敗：${e.message}") }
            }
            override fun onCameraClose(dev: UsbDevice?) {}
            override fun onDeviceClose(dev: UsbDevice?) {}
            override fun onDetach(dev: UsbDevice?) { if (dev?.deviceName == device.deviceName) onMessage("採集卡已拔除") }
            override fun onCancel(dev: UsbDevice?) { onMessage("未取得 USB 裝置權限") }
        })
        // 裝置可能早就插著，不一定會再收到 onAttach，主動選一次
        h.deviceList?.firstOrNull { it.deviceName == device.deviceName }?.let { select(it) }
    }

    private fun select(dev: UsbDevice?) {
        if (dev == null || dev.deviceName != device.deviceName || selected) return
        selected = true
        helper?.selectDevice(dev)
    }

    override fun stop() {
        try { helper?.release() } catch (_: Exception) {}
        helper = null
    }
}
