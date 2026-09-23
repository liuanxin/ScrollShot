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
    private var highQuality = false
    private var readyQuality = false
    private lateinit var qualityButton: Button
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val exportVersion = java.util.concurrent.atomic.AtomicInteger(0)
    private var readyExport: File? = null
    private var readyCrop: android.graphics.Rect? = null
    private lateinit var cropView: CropView
    private lateinit var saveButton: Button
    private lateinit var title: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        highQuality = savedInstanceState?.getBoolean("highQuality") ?: false
        window.insetsController?.setSystemBarsAppearance(0,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { confirmDiscard() }
        val id = intent.getStringExtra("capture")
        if (id == null || id.any { !it.isDigit() }) { finish(); return }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff17211e.toInt())
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        title = TextView(this).apply { text = "正在准备长图..."; textSize = 16f; setTextColor(Color.WHITE); setPadding(24, 20, 24, 20) }
        body.addView(title)
        setContentView(body)
        worker.execute {
            try {
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
                                intArrayOf(0xff71867c.toInt(), 0xff183b2b.toInt(), 0xffdeebe4.toInt())))
                            setOnClickListener { if (!saving) { action() } }
                        }
                        actions.addView(button, LinearLayout.LayoutParams(0, 48.dp(), 1f).apply {
                            if (actions.childCount > 0) { marginStart = 8.dp() }
                        })
                        return button
                    }
                    button("取消") { confirmDiscard() }
                    button("重置") { cropView.reset() }
                    qualityButton = button("高清") {
                        highQuality = !highQuality
                        updateQualityButton()
                        updateTitle()
                        prepareExport()
                    }
                    updateQualityButton()
                    saveButton = button("保存") { save() }.apply { isSelected = true }
                    body.addView(actions)
                    updateTitle()
                    prepareExport()
                }
            } catch (error: Exception) {
                runOnUiThread { Toast.makeText(this, "无法打开截图草稿", Toast.LENGTH_LONG).show(); finish() }
            }
        }
    }
    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun actionBackground(): android.graphics.drawable.Drawable {
        fun shape(color: Int) = android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = 16.dp().toFloat()
        }
        val states = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(0xff23342c.toInt()))
            addState(intArrayOf(android.R.attr.state_selected), shape(0xffafe6c8.toInt()))
            addState(intArrayOf(), shape(0xff2b3b33.toInt()))
        }
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x337fcda4), states, shape(Color.WHITE))
    }

    private fun updateQualityButton() {
        qualityButton.isSelected = highQuality
        qualityButton.stateDescription = if (highQuality) { "已开启" } else { "已关闭" }
    }

    private fun updateTitle(size: Long? = null) {
        val rect = cropView.cropPixels()
        val spec = ImageExporter.spec(rect, highQuality)
        val suffix = if (spec.scale < 0.999) { " · 已缩小" } else { "" }
        val format = if (highQuality) { "PNG" } else { "JPG" }
        title.text = "裁剪长图  $format  ${spec.width} × ${spec.height} · ${size?.let { ImageExporter.friendlyBytes(it) } ?: "计算大小中..."}$suffix"
    }

    private fun prepareExport() {
        if (saving) { return }
        val doc = document ?: return
        val rect = cropView.cropPixels()
        val quality = highQuality
        val extension = if (quality) { "png" } else { "jpg" }
        val token = exportVersion.incrementAndGet()
        handler.removeCallbacksAndMessages(null)
        saveButton.isEnabled = false
        handler.postDelayed({
            worker.execute {
                val file = File(cacheDir, "export-${doc.directory.name}-$token.$extension")
                try {
                    ImageExporter.write(doc, rect, file, quality) { token != exportVersion.get() }
                    runOnUiThread {
                        if (isDestroyed || token != exportVersion.get()) { file.delete(); return@runOnUiThread }
                        readyExport?.delete()
                        readyExport = file
                        readyCrop = rect
                        readyQuality = quality
                        saveButton.text = "保存"
                        saveButton.isEnabled = true
                        updateTitle(file.length())
                    }
                } catch (error: OutOfMemoryError) {
                    exportFailed(file, token, "内存不足, 请缩小裁剪范围或开启高清重试")
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

    private fun save() {
        val doc = document ?: return
        val rect = cropView.cropPixels()
        val file = readyExport
        if (file == null || !file.isFile || readyCrop != rect || readyQuality != highQuality) { prepareExport(); return }
        val spec = ImageExporter.spec(rect, highQuality)
        val quality = highQuality
        saving = true
        qualityButton.isEnabled = false
        cropView.isEnabled = false
        saveButton.isEnabled = false
        saveButton.text = "保存中..."
        worker.execute {
            var uri: android.net.Uri? = null
            try {
                val random = java.security.SecureRandom().nextInt(1_000_000)
                val extension = if (quality) { "png" } else { "jpg" }
                val name = "%s-%06d.$extension".format(Locale.ROOT, SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()), random)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, if (quality) { "image/png" } else { "image/jpeg" })
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
                    qualityButton.isEnabled = true
                    saveButton.isEnabled = true
                    saveButton.text = "重试保存"
                    Toast.makeText(this, "保存失败, 草稿仍保留, 请检查存储空间", Toast.LENGTH_LONG).show()
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
        outState.putBoolean("highQuality", highQuality)
        if (::cropView.isInitialized) {
            val rect = cropView.crop
            outState.putFloatArray("crop", floatArrayOf(rect.left, rect.top, rect.right, rect.bottom))
        }
    }
    override fun onDestroy() {
        exportVersion.incrementAndGet()
        handler.removeCallbacksAndMessages(null)
        if (!saving) { readyExport?.delete() }
        preview?.recycle()
        worker.shutdown()
        super.onDestroy()
    }
}
