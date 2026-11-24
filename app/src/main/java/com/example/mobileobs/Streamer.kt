package com.example.mobileobs

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import kotlin.concurrent.thread

/**
 * 直播：獨立的 H.264 + AAC 編碼器 → RtmpClient。
 * 與錄影各自編碼，所以直播與錄影可以用不同解析度/碼率，也可同時進行。
 */
class Streamer(private val compositor: GlCompositor, private val mixer: AudioMixer) {
    @Volatile var active = false; private set
    @Volatile private var busy = false
    val running: Boolean get() = active || busy

    private val stopLock = Any()
    private val teardownLock = Any()
    private val audioLock = Any()
    @Volatile private var session = 0
    private var client: RtmpClient? = null
    private var vEnc: MediaCodec? = null
    private var aEnc: MediaCodec? = null
    private var vThread: Thread? = null
    private var startNs = 0L
    private var audioPtsUs = -1L
    private val aInfo = MediaCodec.BufferInfo()

    fun start(url: String, w: Int, h: Int, fps: Int, bitrate: Int, onState: (String) -> Unit) {
        val my = synchronized(stopLock) {
            if (running) return
            busy = true
            ++session
        }
        thread(name = "stream-start") {
            try {
                onState("連線中…")
                val c = RtmpClient(url) { msg -> fail(my, msg, onState) }
                c.connect()
                if (session != my) { c.close(); return@thread }
                client = c
                c.sendMetadata(w, h, fps, bitrate / 1000)
                c.sendAudioConfig(byteArrayOf(0x12, 0x08))   // AAC-LC / 44.1kHz / 單聲道

                val (ve, surface) = createVideoEncoder(w, h, fps, bitrate)
                vEnc = ve
                val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioMixer.RATE, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                val ae = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                ae.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                ae.start()
                aEnc = ae
                audioPtsUs = -1
                vThread = thread(name = "stream-video-drain") { drainVideo(ve, c) }
                mixer.addSink("stream") { buf, len -> feedAudio(buf, len) }
                startNs = System.nanoTime()
                compositor.setEncoder("stream", surface, w, h, startNs, fps)
                active = true
                busy = false
                onState("直播中")
            } catch (e: Exception) {
                if (session == my) {
                    teardown()
                    onState("直播失敗：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    fun stop(onDone: () -> Unit = {}) {
        synchronized(stopLock) { session++ }
        thread(name = "stream-stop") { teardown(); onDone() }
    }

    private fun fail(my: Int, msg: String, onState: (String) -> Unit) {
        synchronized(stopLock) { if (session != my) return; session++ }
        thread(name = "stream-fail") { teardown(); onState("直播中斷：$msg") }
    }

    private fun teardown() {
        synchronized(teardownLock) {
            active = false
            compositor.clearEncoder("stream")
            mixer.removeSink("stream")
            if (vThread != null) {
                try { vEnc?.signalEndOfInputStream() } catch (_: Exception) {}
                vThread?.join(3000)
            } else {
                vEnc?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
            }
            vThread = null; vEnc = null
            synchronized(audioLock) {
                aEnc?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
                aEnc = null
            }
            client?.close(); client = null
            busy = false
        }
    }

    private fun createVideoEncoder(w: Int, h: Int, fps: Int, bitrate: Int): Pair<MediaCodec, Surface> {
        for (cbr in listOf(true, false)) {      // 先試固定碼率，不支援就退回預設
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    if (cbr) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                }
                enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val s = enc.createInputSurface()
                enc.start()
                return enc to s
            } catch (e: Exception) {
                enc.release()
                if (!cbr) throw e
            }
        }
        throw IllegalStateException("無法建立影像編碼器")
    }

    private fun drainVideo(enc: MediaCodec, c: RtmpClient) {
        val info = MediaCodec.BufferInfo()
        var eos = false
        while (!eos) {
            val i = try { enc.dequeueOutputBuffer(info, 10_000) } catch (_: Exception) { break }
            when {
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = enc.outputFormat
                    val nals = ArrayList<ByteArray>()
                    for (k in listOf("csd-0", "csd-1")) f.getByteBuffer(k)?.let {
                        val raw = ByteArray(it.remaining()); it.duplicate().get(raw); nals.addAll(AnnexB.split(raw))
                    }
                    val sps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
                    val pps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
                    if (sps != null && pps != null && sps.size >= 4) c.sendVideoConfig(sps, pps)
                }
                i >= 0 -> {
                    val buf = enc.getOutputBuffer(i)!!
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (info.size > 0 && !config) c.sendVideo(buf, info, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                    enc.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                }
            }
        }
        try { enc.stop() } catch (_: Exception) {}
        enc.release()
    }

    private fun feedAudio(pcm: ByteArray, len: Int) {
        synchronized(audioLock) {
            val enc = aEnc ?: return
            val c = client ?: return
            if (audioPtsUs < 0) audioPtsUs = maxOf(0L, (System.nanoTime() - startNs) / 1000 - len / 2 * 1_000_000L / AudioMixer.RATE)
            var off = 0
            var guard = 0
            while (off < len && guard++ < 50) {
                val ii = enc.dequeueInputBuffer(10_000)
                if (ii < 0) { drainAudio(enc, c); continue }
                val ib = enc.getInputBuffer(ii)!!
                ib.clear()
                val n = minOf(ib.remaining(), len - off)
                ib.put(pcm, off, n)
                enc.queueInputBuffer(ii, 0, n, audioPtsUs, 0)
                audioPtsUs += n / 2 * 1_000_000L / AudioMixer.RATE
                off += n
                drainAudio(enc, c)
            }
        }
    }

    private fun drainAudio(enc: MediaCodec, c: RtmpClient) {
        while (true) {
            val i = enc.dequeueOutputBuffer(aInfo, 0)
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (i < 0) continue
            val buf = enc.getOutputBuffer(i)!!
            val config = aInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (aInfo.size > 0 && !config) c.sendAudio(buf, aInfo)
            enc.releaseOutputBuffer(i, false)
        }
    }
}
