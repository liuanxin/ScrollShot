package io.github.liuanxin.scrollshot

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class EditorActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var preview: Bitmap? = null
    private var document: CaptureDocument? = null
    private var saving = false
    private lateinit var shareButton: android.widget.ImageButton
    private var shared = false
    private var shareToken: String? = null
    private var shareReturned = false
    private var shareFile: File? = null
    private var shareCallback: android.app.PendingIntent? = null
    private val shareAction by lazy { "$packageName.SHARE_SELECTED" }
    private val shareReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.getStringExtra("token") == shareToken && shareToken != null) {
                shared = true
                finishShare()
            }
        }
    }
    private var png = false
    private var readyPng = false
    private lateinit var formatLabel: TextView
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val exportVersion = java.util.concurrent.atomic.AtomicInteger(0)
    private var readyExport: File? = null
    private var readyCrop: android.graphics.Rect? = null
    private lateinit var cropView: CropView
    private lateinit var saveButton: Button
    private lateinit var title: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerReceiver(shareReceiver, android.content.IntentFilter(shareAction), RECEIVER_NOT_EXPORTED)
        shareToken = savedInstanceState?.getString("shareToken")
        shareReturned = savedInstanceState?.getBoolean("shareReturned") ?: false
        shared = savedInstanceState?.getBoolean("shared") == true ||
            (shareToken != null && getSharedPreferences("share", MODE_PRIVATE).getString(shareToken, null) == "chosen")
        shareFile = savedInstanceState?.getString("shareFile")?.let { File(it) }
        if (shared) { finishShare(); return }
        png = savedInstanceState?.getBoolean("png") ?: false
        window.insetsController?.setSystemBarsAppearance(
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { confirmDiscard() }
        val id = intent.getStringExtra("capture")
        if (id == null || id.any { !it.isDigit() }) { finish(); return }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xfff7f8fa.toInt())
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        val header = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(12.dp(), 4.dp(), 12.dp(), 4.dp())
        }
        formatLabel = TextView(this).apply {
            text = if (png) { "PNG" } else { "JPG" }
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xff3568b0.toInt())
            setPadding(8.dp(), 0, 8.dp(), 0)
            background = actionBackground()
            isEnabled = false
            setOnClickListener {
                if (!saving) {
                    png = !png
                    formatLabel.isEnabled = true
                    updateTitle()
                    prepareExport()
                }
            }
        }
        header.addView(formatLabel, LinearLayout.LayoutParams(-2, 40.dp()))
        title = TextView(this).apply {
            text = "正在准备..."
            textSize = 16f
            setTextColor(0xff252a34.toInt())
            setPadding(8.dp(), 0, 0, 0)
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        shareButton = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_share)
            imageTintList = android.content.res.ColorStateList.valueOf(0xff3568b0.toInt())
            contentDescription = "分享"
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            background = actionBackground()
            isEnabled = false
            setOnClickListener { share() }
        }
        header.addView(shareButton, LinearLayout.LayoutParams(44.dp(), 44.dp()).apply { marginStart = 8.dp() })
        body.addView(header)
        setContentView(body)
        worker.execute {
            try {
                ShareProvider.clean(this)
                val doc = CaptureDocument.read(File(filesDir, "captures/$id"))
                val bitmap = doc.preview()
                runOnUiThread {
                    if (isDestroyed || isFinishing) { bitmap.recycle(); return@runOnUiThread }
                    document = doc
                    preview = bitmap
                    cropView = CropView(this, bitmap, doc.width, doc.height)
                    savedInstanceState?.getFloatArray("crop")?.let { values ->
                        if (values.size == 4) { cropView.crop.set(values[0], values[1], values[2], values[3]) }
                    }
                    cropView.onCropChanged = { updateTitle(); prepareExport() }
                    body.addView(cropView, LinearLayout.LayoutParams(-1, 0, 1f))
                    val actions = LinearLayout(this).apply {
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
                    }
                    fun button(label: String, action: () -> Unit): Button {
                        val button = Button(this).apply {
                            text = label
                            textSize = 14f
                            isAllCaps = false
                            minWidth = 0
                            minimumWidth = 0
                            minHeight = 0
                            minimumHeight = 0
                            setPadding(0, 0, 0, 0)
                            stateListAnimator = null
                            backgroundTintList = null
                            background = actionBackground()
                            setTextColor(android.content.res.ColorStateList(
                                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_selected), intArrayOf()),
                                intArrayOf(0xff9198a3.toInt(), 0xffffffff.toInt(), 0xff35435c.toInt())))
                            setOnClickListener { if (!saving) { action() } }
                        }
                        actions.addView(button, LinearLayout.LayoutParams(0, 48.dp(), 1f).apply {
                            if (actions.childCount > 0) { marginStart = 8.dp() }
                        })
                        return button
                    }
                    button("取消") { confirmDiscard() }
                    button("重置") { cropView.reset() }
                    saveButton = button("保存") { save() }.apply { isSelected = true }
                    body.addView(actions)
                    formatLabel.isEnabled = true
                    updateTitle()
                    prepareExport()
                }
            } catch (error: Exception) {
                runOnUiThread { Toast.makeText(this, "无法打开截图", Toast.LENGTH_LONG).show(); finish() }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        if (shareToken != null && getSharedPreferences("share", MODE_PRIVATE).getString(shareToken, null) == "chosen") {
            shared = true
            finishShare()
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun actionBackground(): android.graphics.drawable.Drawable {
        fun shape(color: Int) = android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = 16.dp().toFloat()
        }
        val states = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(0xffedf0f4.toInt()))
            addState(intArrayOf(android.R.attr.state_selected), shape(0xff3568b0.toInt()))
            addState(intArrayOf(), shape(0xffe5ebf4.toInt()))
        }
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x333568b0), states, shape(Color.WHITE))
    }

    private fun updateTitle(size: Long? = null) {
        val rect = cropView.cropPixels()
        val spec = ImageExporter.spec(rect, png)
        formatLabel.text = if (png) { "PNG" } else { "JPG" }
        title.text = "${spec.width} × ${spec.height} · ${size?.let { ImageExporter.friendlyBytes(it) } ?: "计算大小中..."}"
    }

    private fun prepareExport() {
        if (saving) { return }
        val doc = document ?: return
        val rect = cropView.cropPixels()
        val usePng = png
        val extension = if (usePng) { "png" } else { "jpg" }
        val token = exportVersion.incrementAndGet()
        handler.removeCallbacksAndMessages(null)
        saveButton.isEnabled = false
        shareButton.isEnabled = false
        handler.postDelayed({
            worker.execute {
                val file = File(cacheDir, "export-${doc.directory.name}-$token.$extension")
                try {
                    ImageExporter.write(doc, rect, file, usePng) { token != exportVersion.get() }
                    runOnUiThread {
                        if (isDestroyed || token != exportVersion.get()) { file.delete(); return@runOnUiThread }
                        readyExport?.delete()
                        readyExport = file
                        readyCrop = rect
                        readyPng = usePng
                        saveButton.text = "保存"
                        saveButton.isEnabled = true
                        shareButton.isEnabled = true
                        updateTitle(file.length())
                    }
                } catch (error: OutOfMemoryError) {
                    exportFailed(file, token, "内存不足, 请缩小裁剪范围后重试")
                } catch (error: Exception) {
                    exportFailed(file, token, "图片处理失败, 可重新裁剪或稍后重试")
                }
            }
        }, 300)
    }

    private fun exportFailed(file: File, token: Int, message: String) {
        file.delete()
        runOnUiThread {
            if (!isDestroyed && token == exportVersion.get()) {
                title.text = message
                saveButton.isEnabled = true
                saveButton.text = "重试"
            }
        }
    }

    private fun share() {
        if (saving) { return }
        val file = readyExport ?: return
        if (!file.isFile || readyCrop != cropView.cropPixels() || readyPng != png) { prepareExport(); return }
        saving = true
        shareButton.isEnabled = false
        saveButton.isEnabled = false
        formatLabel.isEnabled = false
        cropView.isEnabled = false
        worker.execute {
            var copy: File? = null
            try {
                val folder = File(cacheDir, "shares").apply { mkdirs() }
                copy = File(folder, "${java.util.UUID.randomUUID()}.${file.extension}")
                file.copyTo(copy)
                copy.setLastModified(System.currentTimeMillis())
                val uri = android.net.Uri.Builder().scheme("content").authority("$packageName.share").appendPath(copy.name).build()
                getSystemService(android.app.job.JobScheduler::class.java).schedule(
                    android.app.job.JobInfo.Builder(41, android.content.ComponentName(this, ShareCleanupService::class.java))
                        .setMinimumLatency(ShareProvider.RETENTION).build())
                runOnUiThread {
                    if (isDestroyed || isFinishing) { copy.delete(); return@runOnUiThread }
                    shareFile = copy
                    shared = false
                    shareReturned = false
                    shareToken = java.util.UUID.randomUUID().toString()
                    getSharedPreferences("share", MODE_PRIVATE).edit().putString(shareToken, document!!.directory.name).commit()
                    try {
                        shareCallback = android.app.PendingIntent.getBroadcast(this, 0,
                            android.content.Intent(this, ShareReceiver::class.java).setAction(shareToken).putExtra("token", shareToken),
                            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_ONE_SHOT)
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = if (readyPng) { "image/png" } else { "image/jpeg" }
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            clipData = android.content.ClipData.newRawUri("截图", uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivityForResult(android.content.Intent.createChooser(send, null, shareCallback!!.intentSender), 41)
                    } catch (_: Exception) { cancelShare(); Toast.makeText(this, "无法打开分享面板", Toast.LENGTH_SHORT).show() }
                }
            } catch (_: Exception) {
                copy?.delete()
                runOnUiThread { cancelShare(); Toast.makeText(this, "分享图片准备失败", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    @Deprecated("系统分享面板返回")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 41) {
            shareReturned = true
            if (shareToken != null && getSharedPreferences("share", MODE_PRIVATE).getString(shareToken, null) == "chosen") {
                shared = true
                finishShare()
            }
            if (!shared) {
                // 选择回调可能晚于返回; 保留事务, 直到用户再次操作编辑页才作废.
                saving = false
                if (::cropView.isInitialized) {
                    cropView.isEnabled = true
                    formatLabel.isEnabled = true
                    saveButton.isEnabled = true
                    shareButton.isEnabled = true
                }
            }
        }
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (shareReturned && event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            // 继续编辑即结束上次分享事务, 迟到回调不能删除新修改的草稿.
            shareToken?.let { getSharedPreferences("share", MODE_PRIVATE).edit().remove(it).commit() }
            shareToken = null
            shareCallback?.cancel()
            shareReturned = false
        }
        return super.dispatchTouchEvent(event)
    }

    private fun cancelShare() {
        shareToken?.let { getSharedPreferences("share", MODE_PRIVATE).edit().remove(it).commit() }
        shareToken = null
        shareCallback?.cancel()
        shareFile?.delete()
        shareFile = null
        saving = false
        if (::cropView.isInitialized) {
            cropView.isEnabled = true
            formatLabel.isEnabled = true
            saveButton.isEnabled = true
            shareButton.isEnabled = true
        }
    }

    private fun finishShare() {
        // 系统只报告目标选择, 不提供对方应用实际发送成功的状态.
        shareToken?.let { getSharedPreferences("share", MODE_PRIVATE).edit().remove(it).commit() }
        shareToken = null
        saving = false
        finish()
    }

    private fun save() {
        val doc = document ?: return
        val rect = cropView.cropPixels()
        val file = readyExport
        if (file == null || !file.isFile || readyCrop != rect || readyPng != png) { prepareExport(); return }
        val spec = ImageExporter.spec(rect, png)
        val usePng = png
        saving = true
        shareButton.isEnabled = false
        formatLabel.isEnabled = false
        cropView.isEnabled = false
        saveButton.isEnabled = false
        saveButton.text = "保存中..."
        worker.execute {
            var uri: android.net.Uri? = null
            try {
                val random = java.security.SecureRandom().nextInt(1_000_000)
                val extension = if (usePng) { "png" } else { "jpg" }
                val name = "%s-%06d.$extension".format(Locale.ROOT, SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()), random)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, if (usePng) { "image/png" } else { "image/jpeg" })
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ScrollShot")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                    put(MediaStore.Images.Media.WIDTH, spec.width)
                    put(MediaStore.Images.Media.HEIGHT, spec.height)
                }
                uri = requireNotNull(contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                requireNotNull(contentResolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
                check(contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
                doc.directory.deleteRecursively()
                file.delete()
                runOnUiThread {
                    saving = false
                    Toast.makeText(this, "已保存到相册 / ScrollShot", Toast.LENGTH_LONG).show()
                    finish()
                }
            } catch (error: Exception) {
                uri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
                runOnUiThread {
                    saving = false
                    cropView.isEnabled = true
                    formatLabel.isEnabled = true
                    saveButton.isEnabled = true
                    shareButton.isEnabled = true
                    saveButton.text = "重试保存"
                    Toast.makeText(this, "保存失败, 请检查存储空间后重试", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private fun confirmDiscard() {
        if (saving) { return }
        AlertDialog.Builder(this).setTitle("放弃这张截图?")
            .setNegativeButton("继续编辑", null)
            .setPositiveButton("放弃") { _, _ -> document?.directory?.deleteRecursively(); finish() }.show()
    }
    @Deprecated("兼容平台返回回调")
    override fun onBackPressed() { confirmDiscard() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("png", png)
        outState.putBoolean("shared", shared)
        outState.putString("shareToken", shareToken)
        outState.putBoolean("shareReturned", shareReturned)
        outState.putString("shareFile", shareFile?.path)
        if (::cropView.isInitialized) {
            val rect = cropView.crop
            outState.putFloatArray("crop", floatArrayOf(rect.left, rect.top, rect.right, rect.bottom))
        }
    }
    override fun onDestroy() {
        unregisterReceiver(shareReceiver)
        if (isFinishing) {
            shareCallback?.cancel()
            shareToken?.let { getSharedPreferences("share", MODE_PRIVATE).edit().remove(it).commit() }
        }
        exportVersion.incrementAndGet()
        handler.removeCallbacksAndMessages(null)
        if (!saving) { readyExport?.delete() }
        preview?.recycle()
        worker.shutdown()
        super.onDestroy()
    }
}
