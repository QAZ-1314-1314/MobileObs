package com.example.mobileobs

import android.media.MediaCodec
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

object AnnexB {
    /** 把 Annex-B 位元流(00 00 01 / 00 00 00 01 起始碼)切成一個個 NAL(不含起始碼) */
    fun split(d: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        val codeLen = ArrayList<Int>()
        var i = 0
        while (i + 3 <= d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0) {
                if (i + 3 < d.size && d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1) { starts.add(i); codeLen.add(4); i += 4; continue }
                if (d[i + 2].toInt() == 1) { starts.add(i); codeLen.add(3); i += 3; continue }
            }
            i++
        }
        val out = ArrayList<ByteArray>()
        for (k in starts.indices) {
            val s = starts[k] + codeLen[k]
            val e = if (k + 1 < starts.size) starts[k + 1] else d.size
            if (e > s) out.add(d.copyOfRange(s, e))
        }
        return out
    }
}

/**
 * 自製 RTMP 推流端（簡易握手、AMF0 指令、分塊、H.264 + AAC 的 FLV 封裝）。
 * 支援 rtmp:// 與 rtmps://。送出走獨立執行緒與有限佇列，網路太慢時丟棄影格而不是卡住編碼器。
 */
class RtmpClient(private val url: String, private val onError: (String) -> Unit) {
    private class Out(val csid: Int, val type: Int, val ts: Int, val payload: ByteArray, val video: Boolean, val key: Boolean)
    private class Msg(val type: Int, val sid: Int, val payload: ByteArray)
    private class Cmd(val name: String, val txn: Double, val payload: ByteArray)
    private class ChunkState { var len = 0; var type = 0; var sid = 0; var ext = false; var buf: ByteArray? = null; var got = 0 }
    private class Target(val tls: Boolean, val host: String, val port: Int, val app: String, val stream: String, val tcUrl: String)

    private lateinit var sock: Socket
    private lateinit var input: DataInputStream
    private lateinit var out: OutputStream
    private val queue = LinkedBlockingQueue<Out>(400)
    @Volatile private var closed = false
    private var streamId = 1
    private var outChunk = 128
    private var inChunk = 128
    private var windowAck = 2_500_000L
    @Volatile private var bytesRead = 0L
    private var lastAckSent = 0L
    private val writeLock = Any()
    private val states = HashMap<Int, ChunkState>()
    @Volatile private var droppingVideo = false
    private var sender: Thread? = null
    private var reader: Thread? = null

    private inner class Counting(private val s: InputStream) : InputStream() {
        override fun read(): Int { val b = s.read(); if (b >= 0) bytesRead++; return b }
        override fun read(b: ByteArray, o: Int, l: Int): Int { val n = s.read(b, o, l); if (n > 0) bytesRead += n; return n }
    }

    // ------------------------------------------------------------ 連線
    private fun parse(u: String): Target {
        val m = Regex("^(rtmps?)://([^/:]+)(?::(\\d+))?/(.+)$").matchEntire(u.trim())
            ?: throw IOException("網址格式錯誤，應為 rtmp://主機/應用名稱/金鑰")
        val tls = m.groupValues[1] == "rtmps"
        val host = m.groupValues[2]
        val port = m.groupValues[3].ifEmpty { if (tls) "443" else "1935" }.toInt()
        val path = m.groupValues[4]
        val idx = path.lastIndexOf('/')
        if (idx <= 0) throw IOException("缺少串流金鑰")
        val app = path.substring(0, idx)
        return Target(tls, host, port, app, path.substring(idx + 1), "${m.groupValues[1]}://$host:$port/$app")
    }

    fun connect() {
        val t = parse(url)
        val plain = Socket()
        plain.tcpNoDelay = true
        plain.connect(InetSocketAddress(t.host, t.port), 8000)
        sock = if (t.tls) {
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, t.host, t.port, true)
                .also { (it as SSLSocket).startHandshake() }
        } else plain
        sock.soTimeout = 10_000
        input = DataInputStream(Counting(BufferedInputStream(sock.getInputStream(), 65536)))
        out = BufferedOutputStream(sock.getOutputStream(), 65536)

        // 握手
        val c1 = ByteArray(1536); SecureRandom().nextBytes(c1); java.util.Arrays.fill(c1, 0, 8, 0)
        out.write(3); out.write(c1); out.flush()
        if (input.readUnsignedByte() != 3) throw IOException("握手失敗")
        val s1 = ByteArray(1536); input.readFully(s1)
        out.write(s1); out.flush()
        input.readFully(ByteArray(1536))   // S2

        // connect
        writeMessage(3, 20, 0, 0, Amf().str("connect").num(1.0).obj(
            "app" to t.app, "type" to "nonprivate",
            "flashVer" to "FMLE/3.0 (compatible; MobileObs)", "tcUrl" to t.tcUrl).bytes())
        waitCmd(1.0) { it.name == "_result" && it.txn == 1.0 }

        outChunk = 4096
        writeMessage(2, 1, 0, 0, int32(4096))        // Set Chunk Size
        writeMessage(3, 20, 0, 0, Amf().str("releaseStream").num(2.0).nul().str(t.stream).bytes())
        writeMessage(3, 20, 0, 0, Amf().str("FCPublish").num(3.0).nul().str(t.stream).bytes())
        writeMessage(3, 20, 0, 0, Amf().str("createStream").num(4.0).nul().bytes())
        val r = waitCmd(4.0) { it.name == "_result" && it.txn == 4.0 }
        streamId = createStreamId(r.payload)
        writeMessage(3, 20, streamId, 0, Amf().str("publish").num(0.0).nul().str(t.stream).str("live").bytes())
        val st = waitCmd(null) { it.name == "onStatus" }
        val code = findCode(st.payload) ?: ""
        if (!code.startsWith("NetStream.Publish.Start")) throw IOException("發佈失敗：$code")

        sock.soTimeout = 0
        sender = thread(name = "rtmp-send") {
            try {
                while (!closed) {
                    val m = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    writeMessage(m.csid, m.type, streamId, m.ts, m.payload)
                }
            } catch (e: Exception) { if (!closed) onError(e.message ?: "送出失敗") }
        }
        reader = thread(name = "rtmp-read") {
            try {
                while (!closed) handleControl(readMessage())
            } catch (e: Exception) { if (!closed) onError(e.message ?: "伺服器中斷連線") }
        }
    }

    fun close() {
        closed = true
        try { sock.close() } catch (_: Exception) {}
        sender?.interrupt()
    }

    // ------------------------------------------------------------ 對外送資料
    fun sendMetadata(w: Int, h: Int, fps: Int, videoKbps: Int) {
        val a = Amf().str("@setDataFrame").str("onMetaData").ecma(
            "duration" to 0.0, "width" to w, "height" to h, "videodatarate" to videoKbps,
            "framerate" to fps, "videocodecid" to 7, "audiodatarate" to 128,
            "audiosamplerate" to AudioMixer.RATE, "audiosamplesize" to 16, "stereo" to false,
            "audiocodecid" to 10, "encoder" to "MobileObs")
        enqueue(Out(5, 18, 0, a.bytes(), false, false))
    }

    fun sendAudioConfig(asc: ByteArray) {
        enqueue(Out(4, 8, 0, byteArrayOf(0xAF.toByte(), 0) + asc, false, false))
    }

    fun sendVideoConfig(sps: ByteArray, pps: ByteArray) {
        val b = ByteArrayOutputStream()
        b.write(0x17); b.write(0); b.write(0); b.write(0); b.write(0)
        b.write(1); b.write(sps[1].toInt()); b.write(sps[2].toInt()); b.write(sps[3].toInt()); b.write(0xFF); b.write(0xE1)
        b.write(sps.size shr 8); b.write(sps.size and 0xFF); b.write(sps, 0, sps.size)
        b.write(1)
        b.write(pps.size shr 8); b.write(pps.size and 0xFF); b.write(pps, 0, pps.size)
        enqueue(Out(6, 9, 0, b.toByteArray(), true, true))
    }

    fun sendVideo(buf: ByteBuffer, info: MediaCodec.BufferInfo, key: Boolean) {
        val raw = ByteArray(info.size)
        buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(raw)
        val nals = AnnexB.split(raw)
        if (nals.isEmpty()) return
        var total = 5
        for (n in nals) total += 4 + n.size
        val p = ByteArray(total)
        p[0] = (if (key) 0x17 else 0x27).toByte(); p[1] = 1
        var o = 5
        for (n in nals) {
            p[o] = (n.size ushr 24).toByte(); p[o + 1] = (n.size ushr 16).toByte()
            p[o + 2] = (n.size ushr 8).toByte(); p[o + 3] = n.size.toByte()
            System.arraycopy(n, 0, p, o + 4, n.size); o += 4 + n.size
        }
        enqueue(Out(6, 9, (info.presentationTimeUs / 1000).toInt(), p, true, key))
    }

    fun sendAudio(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val p = ByteArray(info.size + 2)
        p[0] = 0xAF.toByte(); p[1] = 1
        buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(p, 2, info.size)
        enqueue(Out(4, 8, (info.presentationTimeUs / 1000).toInt(), p, false, false))
    }

    private fun enqueue(m: Out) {
        if (closed) return
        if (m.video) {
            if (droppingVideo && !m.key) return           // 掉過畫面後，等下一個關鍵影格再繼續
            if (droppingVideo && m.key) droppingVideo = false
        }
        if (!queue.offer(m) && m.video) droppingVideo = true   // 佇列滿：丟棄
    }

    // ------------------------------------------------------------ 寫入(分塊)
    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun writeMessage(csid: Int, type: Int, sid: Int, ts: Int, payload: ByteArray) {
        synchronized(writeLock) {
            val o = out
            val ext = ts >= 0xFFFFFF
            var off = 0
            var first = true
            do {
                val n = minOf(outChunk, payload.size - off)
                if (first) {
                    o.write(csid)                                  // fmt 0
                    val t24 = if (ext) 0xFFFFFF else ts
                    o.write(t24 ushr 16); o.write(t24 ushr 8); o.write(t24)
                    o.write(payload.size ushr 16); o.write(payload.size ushr 8); o.write(payload.size)
                    o.write(type)
                    o.write(sid); o.write(sid ushr 8); o.write(sid ushr 16); o.write(sid ushr 24)   // 小端
                } else o.write((3 shl 6) or csid)                  // fmt 3
                if (ext) o.write(int32(ts))
                o.write(payload, off, n)
                off += n; first = false
            } while (off < payload.size)
            o.flush()
        }
    }

    // ------------------------------------------------------------ 讀取(組塊)
    private fun read24() = (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()

    private fun readMessage(): Msg {
        while (true) {
            val b0 = input.readUnsignedByte()
            val fmt = b0 ushr 6
            var csid = b0 and 0x3F
            if (csid == 0) csid = input.readUnsignedByte() + 64
            else if (csid == 1) { val a = input.readUnsignedByte(); val b = input.readUnsignedByte(); csid = a + (b shl 8) + 64 }
            val st = states.getOrPut(csid) { ChunkState() }
            when (fmt) {
                0 -> { val ts = read24(); st.len = read24(); st.type = input.readUnsignedByte(); st.sid = Integer.reverseBytes(input.readInt()); st.ext = ts == 0xFFFFFF }
                1 -> { val ts = read24(); st.len = read24(); st.type = input.readUnsignedByte(); st.ext = ts == 0xFFFFFF }
                2 -> { val ts = read24(); st.ext = ts == 0xFFFFFF }
            }
            if (st.ext) input.readInt()
            if (st.buf == null) { st.buf = ByteArray(st.len); st.got = 0 }
            val n = minOf(inChunk, st.len - st.got)
            input.readFully(st.buf!!, st.got, n)
            st.got += n
            if (st.got >= st.len) {
                val m = Msg(st.type, st.sid, st.buf!!)
                st.buf = null; st.got = 0
                return m
            }
        }
    }

    private fun handleControl(m: Msg) {
        val p = m.payload
        when (m.type) {
            1 -> if (p.size >= 4) inChunk = ByteBuffer.wrap(p).int and 0x7FFFFFFF
            5 -> if (p.size >= 4) windowAck = ByteBuffer.wrap(p).int.toLong() and 0xFFFFFFFFL
            6 -> writeMessage(2, 5, 0, 0, int32(windowAck.toInt()))
            4 -> if (p.size >= 6 && (((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)) == 6) {
                val resp = ByteArray(6); resp[1] = 7
                System.arraycopy(p, 2, resp, 2, 4)
                writeMessage(2, 4, 0, 0, resp)
            }
        }
        if (bytesRead - lastAckSent >= windowAck) {
            lastAckSent = bytesRead
            writeMessage(2, 3, 0, 0, int32(bytesRead.toInt()))
        }
    }

    // ------------------------------------------------------------ AMF0
    private fun parseCmd(p: ByteArray): Cmd? {
        if (p.size < 3 || p[0].toInt() != 2) return null
        val l = ((p[1].toInt() and 0xFF) shl 8) or (p[2].toInt() and 0xFF)
        if (3 + l > p.size) return null
        val pos = 3 + l
        var txn = -1.0
        if (pos + 9 <= p.size && p[pos].toInt() == 0) txn = ByteBuffer.wrap(p, pos + 1, 8).double
        return Cmd(String(p, 3, l, Charsets.UTF_8), txn, p)
    }

    private fun createStreamId(p: ByteArray): Int {
        val l = ((p[1].toInt() and 0xFF) shl 8) or (p[2].toInt() and 0xFF)
        var pos = 3 + l + 9
        if (pos < p.size && p[pos].toInt() == 5) pos++
        return if (pos + 9 <= p.size && p[pos].toInt() == 0) ByteBuffer.wrap(p, pos + 1, 8).double.toInt() else 1
    }

    /** 在 onStatus / _error 的物件裡找 "code" 字串 */
    private fun findCode(p: ByteArray): String? {
        val key = byteArrayOf(0, 4, 'c'.code.toByte(), 'o'.code.toByte(), 'd'.code.toByte(), 'e'.code.toByte())
        var i = 0
        while (i + key.size + 3 <= p.size) {
            var match = true
            for (k in key.indices) if (p[i + k] != key[k]) { match = false; break }
            if (!match) { i++; continue }
            val j = i + key.size
            if (p[j].toInt() != 2) return null
            val l = ((p[j + 1].toInt() and 0xFF) shl 8) or (p[j + 2].toInt() and 0xFF)
            if (j + 3 + l > p.size) return null
            return String(p, j + 3, l, Charsets.UTF_8)
        }
        return null
    }

    /** 讀訊息直到 pred 成立；txn 不為 null 時，對應該 txn 的 _error 視為失敗 */
    private fun waitCmd(txn: Double?, pred: (Cmd) -> Boolean): Cmd {
        while (true) {
            val m = readMessage()
            handleControl(m)
            if (m.type != 20) continue
            val c = parseCmd(m.payload) ?: continue
            if (c.name == "_error" && txn != null && c.txn == txn)
                throw IOException("伺服器拒絕：" + (findCode(m.payload) ?: "unknown"))
            if (c.name == "onStatus") {
                val code = findCode(m.payload)
                if (code != null && listOf("Rejected", "Failed", "Denied", "BadName", "Closed").any { code.contains(it) })
                    throw IOException("伺服器回報：$code")
            }
            if (pred(c)) return c
        }
    }

    private class Amf {
        private val b = ByteArrayOutputStream()
        fun num(d: Double) = apply { b.write(0); b.write(ByteBuffer.allocate(8).putDouble(d).array(), 0, 8) }
        fun str(s: String) = apply { b.write(2); val x = s.toByteArray(Charsets.UTF_8); b.write(x.size shr 8); b.write(x.size and 0xFF); b.write(x, 0, x.size) }
        fun bool(v: Boolean) = apply { b.write(1); b.write(if (v) 1 else 0) }
        fun nul() = apply { b.write(5) }
        private fun key(k: String) { val x = k.toByteArray(Charsets.UTF_8); b.write(x.size shr 8); b.write(x.size and 0xFF); b.write(x, 0, x.size) }
        private fun props(p: Array<out Pair<String, Any>>) {
            for ((k, v) in p) {
                key(k)
                when (v) {
                    is String -> str(v)
                    is Boolean -> bool(v)
                    is Int -> num(v.toDouble())
                    is Double -> num(v)
                }
            }
            b.write(0); b.write(0); b.write(9)
        }
        fun obj(vararg p: Pair<String, Any>) = apply { b.write(3); props(p) }
        fun ecma(vararg p: Pair<String, Any>) = apply {
            b.write(8); b.write(0); b.write(0); b.write(0); b.write(p.size); props(p)
        }
        fun bytes(): ByteArray = b.toByteArray()
    }
}
