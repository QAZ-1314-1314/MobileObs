package com.example.mobileobs

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** 保持 16:9、置中的容器 */
class AspectFrame(c: Context) : FrameLayout(c) {
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val pw = MeasureSpec.getSize(wSpec); val ph = MeasureSpec.getSize(hSpec)
        var w = pw; var h = pw * 9 / 16
        if (h > ph) { h = ph; w = ph * 16 / 9 }
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }
}

class MainActivity : AppCompatActivity() {
    private val compositor = GlCompositor()
    private lateinit var mixer: AudioMixer
    private lateinit var recorder: Recorder
    private val sources = HashMap<Layer, VideoSource>()
    private var selected: Layer? = null
    private var subtitleLayer: BitmapLayer? = null
    private var backgroundLayer: BitmapLayer? = null
    private var pickingBackground = false
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var selLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var recBtn: Button
    private lateinit var subtitleEdit: EditText
    private lateinit var liveCheck: CheckBox
    private lateinit var audioBox: LinearLayout
    private val levelBars = ArrayList<Pair<AudioChannel, ProgressBar>>()
    private val formatControls = ArrayList<View>()
    private var recStartMs = 0L
    private var lastFile: File? = null

    // 直播
    private lateinit var streamer: Streamer
    private lateinit var urlEdit: EditText
    private lateinit var keyEdit: EditText
    private lateinit var streamBtn: Button
    private lateinit var streamStatus: TextView
    private val streamControls = ArrayList<View>()
    private val sResList = listOf(Triple("480p", 854, 480), Triple("720p", 1280, 720), Triple("1080p", 1920, 1080))
    private val sBitrateList = listOf(1500, 2500, 4000, 6000)       // kbps
    private var sResIdx = 1; private var sFpsIdx = 0; private var sBrIdx = 1

    // 圖層旋轉/裁切
    private val cropBars = ArrayList<SeekBar>()
    private var syncing = false

    // 錄影格式選項
    private val resList = listOf(Triple("720p", 1280, 720), Triple("1080p", 1920, 1080))
    private val fpsList = listOf(30, 60)
    private val bitrateList = listOf(2, 4, 6, 8, 12, 20)       // Mbps
    private val codecList = listOf("H.264 (相容性最好)", "H.265 (檔案較小)")
    private var resIdx = 0; private var fpsIdx = 0; private var brIdx = 3; private var codecIdx = 0

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { mixer.reopenAll() }
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { onImagePicked(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mixer = AudioMixer(this)
        recorder = Recorder(compositor, mixer)
        streamer = Streamer(compositor, mixer)
        compositor.start()
        mixer.start()
        setContentView(buildUi())
        permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        ui.post(tick)
    }

    // ================= UI =================
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 15f; setTypeface(null, Typeface.BOLD); setPadding(0, dp(14), 0, dp(4))
    }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun spinner(items: List<String>, sel: Int, onSel: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(sel)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onSel(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // ---- 左：預覽(可觸控：拖曳移動、雙指縮放) ----
        val left = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val aspect = AspectFrame(this)
        val sv = SurfaceView(this)
        aspect.addView(sv, FrameLayout.LayoutParams(-1, -1))
        left.addView(aspect, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) = compositor.setPreview(h.surface, w, hh)
            override fun surfaceDestroyed(h: SurfaceHolder) = compositor.setPreview(null, 1, 1)
        })
        setupTouch(sv)
        root.addView(left, LinearLayout.LayoutParams(0, -1, 3f))

        // ---- 右：控制面板 ----
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16)) }

        panel.addView(header("① 影像來源 / 子母畫面"))
        panel.addView(btn("＋ 新增攝影機 / HDMI 採集卡") { addCameraDialog() })
        selLabel = TextView(this).apply { text = "在預覽上點選圖層後：拖曳=移動、雙指=縮放" }
        panel.addView(selLabel)
        panel.addView(btn("刪除選取的圖層") { deleteSelected() })

        panel.addView(header("選取圖層：旋轉 / 翻轉 / 裁切"))
        val adjRow = LinearLayout(this)
        adjRow.addView(btn("↻ 旋轉90°") { selected?.let { it.rotation = (it.rotation + 90) % 360; it.fit(it.w) } })
        adjRow.addView(btn("⇋ 翻轉") { selected?.let { it.flipH = !it.flipH } })
        adjRow.addView(btn("重設") { selected?.let { it.resetAdjust(); it.fit(it.w); syncAdjustUi() } })
        panel.addView(adjRow)
        listOf("左", "上", "右", "下").forEachIndexed { i, n ->
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            r.addView(TextView(this).apply { text = "裁切$n"; width = dp(64) })
            val sb = SeekBar(this).apply {
                max = 45
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                        if (syncing) return
                        val l = selected ?: return
                        val v = p / 100f
                        when (i) { 0 -> l.cropL = v; 1 -> l.cropT = v; 2 -> l.cropR = v; else -> l.cropB = v }
                        l.fit(l.w)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            cropBars.add(sb)
            r.addView(sb, LinearLayout.LayoutParams(0, -2, 1f))
            panel.addView(r)
        }

        panel.addView(header("② 字幕"))
        subtitleEdit = EditText(this).apply { hint = "輸入字幕文字"; minLines = 1; maxLines = 3 }
        panel.addView(subtitleEdit)
        liveCheck = CheckBox(this).apply { text = "邊打邊顯示(即時字幕)"; isChecked = true }
        panel.addView(liveCheck)
        subtitleEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (liveCheck.isChecked) renderSubtitle(s.toString()) }
        })
        val subRow = LinearLayout(this)
        subRow.addView(btn("送出字幕") { renderSubtitle(subtitleEdit.text.toString()) })
        subRow.addView(btn("清除字幕") { subtitleEdit.setText(""); renderSubtitle("") })
        panel.addView(subRow)

        panel.addView(header("③ 圖片 / 背景"))
        val imgRow = LinearLayout(this)
        imgRow.addView(btn("加入圖片") { pickingBackground = false; pickImage.launch("image/*") })
        imgRow.addView(btn("設定背景") { pickingBackground = true; pickImage.launch("image/*") })
        imgRow.addView(btn("清除背景") { backgroundLayer?.let { compositor.removeLayer(it) }; backgroundLayer = null })
        panel.addView(imgRow)

        panel.addView(header("④ 聲音來源 / 音量"))
        audioBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(audioBox)
        buildAudioSection()

        panel.addView(header("⑤ 錄影格式"))
        formatControls += spinner(resList.map { it.first }, resIdx) { resIdx = it }
        formatControls += spinner(fpsList.map { "$it fps" }, fpsIdx) { fpsIdx = it }
        formatControls += spinner(bitrateList.map { "$it Mbps" }, brIdx) { brIdx = it }
        formatControls += spinner(codecList, codecIdx) { codecIdx = it }
        formatControls.forEach { panel.addView(it) }

        panel.addView(header("⑥ 錄影"))
        recBtn = btn("● 開始錄影") { toggleRecord() }
        panel.addView(recBtn)
        statusText = TextView(this).apply { text = "待機" }
        panel.addView(statusText)

        panel.addView(header("直播 (RTMP)"))
        urlEdit = EditText(this).apply {
            hint = "伺服器網址，例如 rtmp://a.rtmp.youtube.com/live2"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(getSharedPreferences("obs", MODE_PRIVATE).getString("url", ""))
        }
        keyEdit = EditText(this).apply {
            hint = "串流金鑰 (Stream Key)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        panel.addView(urlEdit); panel.addView(keyEdit)
        streamControls.add(urlEdit); streamControls.add(keyEdit)
        val sSpinners = listOf(
            spinner(sResList.map { it.first }, sResIdx) { sResIdx = it },
            spinner(fpsList.map { "$it fps" }, sFpsIdx) { sFpsIdx = it },
            spinner(sBitrateList.map { "%.1f Mbps".format(it / 1000.0) }, sBrIdx) { sBrIdx = it })
        sSpinners.forEach { panel.addView(it) }
        streamControls.addAll(sSpinners)
        streamBtn = btn("● 開始直播") { toggleStream() }
        panel.addView(streamBtn)
        streamStatus = TextView(this).apply { text = "未直播（直播固定使用 H.264）" }
        panel.addView(streamStatus)

        root.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(0, -1, 2f))
        return root
    }

    // ---- 音訊區 ----
    private fun deviceLabel(d: AudioDeviceInfo): String {
        val type = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "手機麥克風"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有線耳機麥克風"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "藍牙"
            else -> "其他"
        }
        return "$type - ${d.productName}"
    }

    private fun buildAudioSection() {
        audioBox.removeAllViews(); levelBars.clear()
        val devices = mixer.inputDevices()
        audioBox.addView(btn("重新整理音源(插上 USB 音效卡後按)") { buildAudioSection() })
        mixer.channels.forEachIndexed { idx, ch ->
            val items = listOf("關閉", "預設麥克風") + devices.map { deviceLabel(it) }
            val initial = if (ch.enabled) 1 else 0
            audioBox.addView(TextView(this).apply { text = ch.name; setPadding(0, dp(8), 0, 0) })
            audioBox.addView(spinner(items, initial) { pos ->
                ch.enabled = pos != 0
                ch.device = if (pos >= 2) devices[pos - 2] else null
                ch.restart = true; ch.retryAtMs = 0
            })
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(SeekBar(this).apply {
                max = 200; progress = (ch.gain * 100).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { ch.gain = p / 100f }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(CheckBox(this).apply { text = "靜音"; isChecked = ch.muted; setOnCheckedChangeListener { _, c -> ch.muted = c } })
            audioBox.addView(row)
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            audioBox.addView(bar); levelBars += ch to bar
        }
    }

    // ================= 觸控：選取 / 拖曳 / 縮放 =================
    private fun setupTouch(v: View) {
        var lastX = 0f; var lastY = 0f
        val sgd = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                selected?.takeIf { !it.locked }?.let {
                    val f = d.scaleFactor
                    it.w = (it.w * f).coerceIn(0.05f, 2f)
                    it.keepAspectForWidth()
                }
                return true
            }
        })
        v.setOnTouchListener { view, e ->
            sgd.onTouchEvent(e)
            val nx = e.x / view.width; val ny = e.y / view.height
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val hit = compositor.layers.lastOrNull { !it.locked && it.visible && Math.abs(nx - it.x) <= it.w / 2 && Math.abs(ny - it.y) <= it.h / 2 }
                    select(hit)
                    hit?.let { if (it !== subtitleLayer) compositor.bringToFront(it); subtitleLayer?.let { s -> compositor.bringToFront(s) } }
                }
                MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !sgd.isInProgress) {
                    selected?.takeIf { !it.locked }?.let {
                        it.x = (it.x + (e.x - lastX) / view.width).coerceIn(0f, 1f)
                        it.y = (it.y + (e.y - lastY) / view.height).coerceIn(0f, 1f)
                    }
                }
            }
            lastX = e.x; lastY = e.y
            true
        }
    }

    private fun select(l: Layer?) {
        selected = l
        selLabel.text = if (l == null) "未選取圖層(點預覽上的畫面來選取)" else "已選取：${l.name}（拖曳移動，雙指縮放）"
        syncAdjustUi()
    }

    private fun syncAdjustUi() {
        if (cropBars.size < 4) return
        syncing = true
        val l = selected
        cropBars[0].progress = ((l?.cropL ?: 0f) * 100).toInt()
        cropBars[1].progress = ((l?.cropT ?: 0f) * 100).toInt()
        cropBars[2].progress = ((l?.cropR ?: 0f) * 100).toInt()
        cropBars[3].progress = ((l?.cropB ?: 0f) * 100).toInt()
        syncing = false
    }

    // ================= 直播 =================
    private fun toggleStream() {
        if (streamer.running) {
            streamBtn.isEnabled = false
            streamer.stop { runOnUiThread { streamBtn.isEnabled = true; refreshStreamUi("已停止直播") } }
            return
        }
        val server = urlEdit.text.toString().trim()
        val key = keyEdit.text.toString().trim()
        if (server.isEmpty() || key.isEmpty()) { toast("請填入伺服器網址與串流金鑰"); return }
        getSharedPreferences("obs", MODE_PRIVATE).edit().putString("url", server).apply()
        val (_, w, h) = sResList[sResIdx]
        streamer.start(server.trimEnd('/') + "/" + key, w, h, fpsList[sFpsIdx], sBitrateList[sBrIdx] * 1000) { msg ->
            runOnUiThread { refreshStreamUi(msg) }
        }
        refreshStreamUi("連線中…")
    }

    private fun refreshStreamUi(msg: String) {
        streamStatus.text = msg
        streamBtn.text = if (streamer.running) "■ 停止直播" else "● 開始直播"
        streamControls.forEach { it.isEnabled = !streamer.running }
    }

    // ================= 影像來源 =================
    private fun isUvc(d: UsbDevice): Boolean {
        if (d.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        for (i in 0 until d.interfaceCount) if (d.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        return false
    }

    private fun addCameraDialog() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)); return
        }
        val names = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        for (id in cm.cameraIdList) {
            val label = when (cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "後置鏡頭"
                CameraCharacteristics.LENS_FACING_FRONT -> "前置鏡頭"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "外接相機(系統提供)"
                else -> "相機"
            }
            val n = "$label (id $id)"
            names.add(n)
            actions.add { addSource(n) { layer -> CameraSource(this, id, layer).also { it.start() } } }
        }
        val usb = getSystemService(USB_SERVICE) as UsbManager
        for (d in usb.deviceList.values.filter { isUvc(it) }) {
            val n = "USB 採集卡直連 (UVC)：${d.productName ?: d.deviceName}"
            names.add(n)
            actions.add { addSource(n) { layer -> UvcSource(d, layer) { m -> runOnUiThread { toast(m) } }.also { it.start() } } }
        }
        if (names.isEmpty()) { toast("找不到任何相機或 USB 採集卡"); return }
        AlertDialog.Builder(this).setTitle("選擇影像來源")
            .setItems(names.toTypedArray()) { _, i -> actions[i]() }
            .setNegativeButton("取消", null).show()
    }

    private fun addSource(name: String, make: (VideoLayer) -> VideoSource) {
        val isFirst = compositor.layers.none { it is VideoLayer }
        val pipCount = compositor.layers.count { it is VideoLayer }
        val layer = compositor.addVideoLayer(name)
        val src = try { make(layer) } catch (e: Exception) {
            toast("無法開啟：${e.message}"); compositor.removeLayer(layer); return
        }
        sources[layer] = src
        if (isFirst) { layer.fit(1f); layer.x = 0.5f; layer.y = 0.5f }
        else { layer.fit(0.3f); layer.x = 0.83f; layer.y = (0.8f - 0.28f * (pipCount - 1)).coerceAtLeast(0.2f) }
        subtitleLayer?.let { compositor.bringToFront(it) }
        select(layer)
    }

    private fun deleteSelected() {
        val l = selected ?: return
        if (l === subtitleLayer) { subtitleLayer = null; subtitleEdit.setText("") }
        sources.remove(l)?.stop()
        compositor.removeLayer(l)
        select(null)
    }

    // ================= 字幕 =================
    private fun renderSubtitle(text: String) {
        if (text.isBlank()) { subtitleLayer?.visible = false; return }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 48f; setShadowLayer(4f, 0f, 2f, Color.BLACK) }
        val width = 1280; val pad = 24
        val sl = StaticLayout.Builder.obtain(text, 0, text.length, tp, width - pad * 2)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        val bmp = Bitmap.createBitmap(width, sl.height + pad, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.argb(140, 0, 0, 0))
        c.translate(pad.toFloat(), pad / 2f)
        sl.draw(c)
        val layer = subtitleLayer ?: compositor.addBitmapLayer("字幕").also {
            it.w = 0.9f; it.x = 0.5f; it.y = 0.88f; subtitleLayer = it
        }
        layer.aspect = bmp.width.toFloat() / bmp.height
        layer.keepAspectForWidth()
        compositor.setBitmap(layer, bmp)
        layer.visible = true
    }

    // ================= 圖片 / 背景 =================
    private fun decode(uri: Uri, maxDim: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (max(o.outWidth, o.outHeight) / s > maxDim) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
    }

    private fun onImagePicked(uri: Uri) {
        val bmp = decode(uri, 2048) ?: run { toast("無法讀取圖片"); return }
        if (pickingBackground) {
            backgroundLayer?.let { compositor.removeLayer(it) }
            val l = compositor.addBitmapLayer("背景", 0)
            l.aspect = bmp.width.toFloat() / bmp.height
            l.cover(); l.x = 0.5f; l.y = 0.5f; l.locked = true
            compositor.setBitmap(l, bmp)
            backgroundLayer = l
        } else {
            val l = compositor.addBitmapLayer("圖片")
            l.aspect = bmp.width.toFloat() / bmp.height
            l.fit(0.25f); l.x = 0.15f; l.y = 0.18f
            compositor.setBitmap(l, bmp)
            subtitleLayer?.let { compositor.bringToFront(it) }
            select(l)
        }
    }

    // ================= 錄影 =================
    private fun toggleRecord() {
        if (recorder.recording) {
            recBtn.isEnabled = false
            recorder.stop {
                runOnUiThread {
                    recBtn.isEnabled = true; recBtn.text = "● 開始錄影"
                    formatControls.forEach { it.isEnabled = true }
                    statusText.text = "已儲存：${lastFile?.absolutePath}"
                }
            }
            return
        }
        val (_, w, h) = resList[resIdx]
        val file = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "OBS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4")
        try {
            recorder.start(file, w, h, fpsList[fpsIdx], bitrateList[brIdx] * 1_000_000, codecIdx == 1)
        } catch (e: Exception) { toast("無法開始錄影：${e.message}"); return }
        lastFile = file
        recStartMs = System.currentTimeMillis()
        recBtn.text = "■ 停止錄影"
        formatControls.forEach { it.isEnabled = false }
    }

    private val tick = object : Runnable {
        override fun run() {
            for ((ch, bar) in levelBars) bar.progress = (ch.level * 100).toInt()
            if (recorder.recording) {
                val s = (System.currentTimeMillis() - recStartMs) / 1000
                statusText.text = "● 錄影中 %02d:%02d".format(s / 60, s % 60)
            }
            ui.postDelayed(this, 80)
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(tick)
        val cleanup = {
            sources.values.forEach { it.stop() }
            mixer.stop()
            compositor.release()
        }
        val afterRec = { if (streamer.running) streamer.stop { cleanup() } else cleanup() }
        if (recorder.recording) recorder.stop { afterRec() } else afterRec()
    }
}
