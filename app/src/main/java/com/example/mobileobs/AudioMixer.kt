package com.example.mobileobs

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.max

class AudioChannel(val name: String) {
    @Volatile var enabled = false
    @Volatile var device: AudioDeviceInfo? = null   // null = 手機預設麥克風
    @Volatile var gain = 1f                         // 0..2
    @Volatile var muted = false
    @Volatile var level = 0f                        // 0..1 峰值(音量表)
    @Volatile var restart = true
    internal var rec: AudioRecord? = null
    internal var retryAtMs = 0L
}

/** 兩個音源通道 → 各自調音量 → 混成單聲道 PCM → 交給錄影 sink。沒有音源時輸出靜音以維持時間軸。 */
class AudioMixer(private val context: Context) {
    companion object { const val RATE = 44100; const val FRAME = 1024 }

    val channelA = AudioChannel("音源 A").also { it.enabled = true }
    val channelB = AudioChannel("音源 B")
    val channels = listOf(channelA, channelB)
    private val sinks = java.util.concurrent.ConcurrentHashMap<String, (ByteArray, Int) -> Unit>()
    fun addSink(key: String, f: (ByteArray, Int) -> Unit) { sinks[key] = f }
    fun removeSink(key: String) { sinks.remove(key) }
    @Volatile private var running = false
    private var thread: Thread? = null

    fun inputDevices(): List<AudioDeviceInfo> =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getDevices(AudioManager.GET_DEVICES_INPUTS).toList()

    fun reopenAll() = channels.forEach { it.restart = true; it.retryAtMs = 0 }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "audio-mixer").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        channels.forEach { close(it) }
    }

    private fun close(ch: AudioChannel) {
        try { ch.rec?.stop() } catch (_: Exception) {}
        ch.rec?.release(); ch.rec = null
    }

    @SuppressLint("MissingPermission")
    private fun open(ch: AudioChannel) {
        close(ch)
        ch.restart = false
        if (!ch.enabled) return
        try {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(max(min, FRAME * 8)).build()
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); throw IllegalStateException() }
            ch.device?.let { r.setPreferredDevice(it) }
            r.startRecording()
            ch.rec = r
        } catch (e: Exception) {
            ch.retryAtMs = System.currentTimeMillis() + 2000   // 沒權限/裝置忙碌：2 秒後再試
        }
    }

    private fun loop() {
        val acc = FloatArray(FRAME)
        val tmp = ShortArray(FRAME)
        val out = ByteArray(FRAME * 2)
        while (running) {
            java.util.Arrays.fill(acc, 0f)
            var any = false
            for (ch in channels) {
                if (!ch.enabled) { if (ch.rec != null) close(ch); ch.level = 0f; continue }
                if ((ch.restart || ch.rec == null) && System.currentTimeMillis() >= ch.retryAtMs) open(ch)
                val r = ch.rec ?: run { ch.level = 0f; null } ?: continue
                var got = 0
                while (got < FRAME) {
                    val n = r.read(tmp, got, FRAME - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < FRAME) {                       // 讀取失敗(例如 USB 音效卡被拔掉)
                    ch.level = 0f; ch.restart = true; ch.retryAtMs = System.currentTimeMillis() + 2000
                    continue
                }
                var peak = 0f
                for (i in 0 until FRAME) {
                    val s = tmp[i] / 32768f
                    peak = max(peak, abs(s))
                    if (!ch.muted) acc[i] += s * ch.gain
                }
                ch.level = if (ch.muted) 0f else (peak * ch.gain).coerceAtMost(1f)
                any = true
            }
            if (!any) Thread.sleep(23)                    // 無音源：以靜音維持節奏
            for (i in 0 until FRAME) {
                val s = (acc[i].coerceIn(-1f, 1f) * 32767f).toInt()
                out[2 * i] = (s and 0xFF).toByte()
                out[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
            }
            for (f in sinks.values) f(out, out.size)
        }
    }
}
