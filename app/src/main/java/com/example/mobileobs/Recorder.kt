package com.example.mobileobs

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/** 錄影：GL 合成畫面 → H.264/H.265；混音 PCM → AAC；兩者寫入同一個 MP4 */
class Recorder(private val compositor: GlCompositor, private val mixer: AudioMixer) {
    @Volatile var recording = false; private set
    private var vEnc: MediaCodec? = null
    private var aEnc: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var vTrack = -1
    private var aTrack = -1
    @Volatile private var muxStarted = false
    private val muxLock = Any()
    private val audioLock = Any()
    private var vThread: Thread? = null
    private var startNs = 0L
    private var audioPtsUs = -1L
    private val aInfo = MediaCodec.BufferInfo()

    fun start(file: File, w: Int, h: Int, fps: Int, bitrate: Int, hevc: Boolean) {
        check(!recording) { "已在錄影中" }
        val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val vf = MediaFormat.createVideoFormat(mime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val ve = MediaCodec.createEncoderByType(mime)
        ve.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = ve.createInputSurface()
        ve.start()

        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioMixer.RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val ae = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        ae.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        ae.start()

        muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        vTrack = -1; aTrack = -1; muxStarted = false; audioPtsUs = -1
        vEnc = ve; aEnc = ae
        startNs = System.nanoTime()
        recording = true
        vThread = thread(name = "video-drain") { drainVideo(ve) }
        mixer.addSink("rec") { buf, len -> feedAudio(buf, len) }
        compositor.setEncoder("rec", surface, w, h, startNs, fps)
    }

    fun stop(onDone: () -> Unit) {
        if (!recording) { onDone(); return }
        recording = false
        thread(name = "rec-stop") {
            compositor.clearEncoder("rec")
            mixer.removeSink("rec")
            try { vEnc?.signalEndOfInputStream() } catch (_: Exception) {}
            vThread?.join(4000)
            synchronized(audioLock) {
                val enc = aEnc
                if (enc != null) {
                    try {
                        val ii = enc.dequeueInputBuffer(100_000)
                        if (ii >= 0) enc.queueInputBuffer(ii, 0, 0, maxOf(0L, audioPtsUs), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        drainAudio(true)
                        enc.stop()
                    } catch (_: Exception) {}
                    enc.release()
                }
                aEnc = null
            }
            synchronized(muxLock) {
                try { if (muxStarted) muxer?.stop() } catch (_: Exception) {}
                try { muxer?.release() } catch (_: Exception) {}
                muxer = null; muxStarted = false
            }
            onDone()
        }
    }

    // ---- 影像 ----
    private fun drainVideo(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var eos = false
        while (!eos) {
            val i = enc.dequeueOutputBuffer(info, 10_000)
            when {
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(true, enc.outputFormat)
                i >= 0 -> {
                    val buf = enc.getOutputBuffer(i)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) writeSample(true, buf, info)
                    enc.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                }
            }
        }
        try { enc.stop() } catch (_: Exception) {}
        enc.release()
    }

    // ---- 聲音 ----
    private fun feedAudio(pcm: ByteArray, len: Int) {
        synchronized(audioLock) {
            val enc = aEnc ?: return
            if (audioPtsUs < 0) audioPtsUs = maxOf(0L, (System.nanoTime() - startNs) / 1000 - len / 2 * 1_000_000L / AudioMixer.RATE)
            var off = 0
            var guard = 0
            while (off < len && guard++ < 50) {
                val ii = enc.dequeueInputBuffer(10_000)
                if (ii < 0) { drainAudio(false); continue }
                val ib = enc.getInputBuffer(ii)!!
                ib.clear()
                val n = minOf(ib.remaining(), len - off)
                ib.put(pcm, off, n)
                enc.queueInputBuffer(ii, 0, n, audioPtsUs, 0)
                audioPtsUs += n / 2 * 1_000_000L / AudioMixer.RATE
                off += n
                drainAudio(false)
            }
        }
    }

    private fun drainAudio(untilEos: Boolean) {
        val enc = aEnc ?: return
        var tries = 0
        while (true) {
            val i = enc.dequeueOutputBuffer(aInfo, if (untilEos) 10_000 else 0)
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!untilEos || ++tries > 300) return else continue }
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { addTrack(false, enc.outputFormat); continue }
            if (i < 0) continue
            val buf = enc.getOutputBuffer(i)!!
            if (aInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) aInfo.size = 0
            if (aInfo.size > 0) writeSample(false, buf, aInfo)
            enc.releaseOutputBuffer(i, false)
            if (aInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    // ---- Muxer(影音兩軌都就緒才開始寫) ----
    private fun addTrack(video: Boolean, fmt: MediaFormat) {
        synchronized(muxLock) {
            val m = muxer ?: return
            val t = m.addTrack(fmt)
            if (video) vTrack = t else aTrack = t
            if (vTrack >= 0 && aTrack >= 0) { m.start(); muxStarted = true }
        }
    }

    private fun writeSample(video: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        var n = 0
        while (!muxStarted && n++ < 100) Thread.sleep(20)
        if (!muxStarted) return
        synchronized(muxLock) {
            val m = muxer ?: return
            buf.position(info.offset); buf.limit(info.offset + info.size)
            m.writeSampleData(if (video) vTrack else aTrack, buf, info)
        }
    }
}
