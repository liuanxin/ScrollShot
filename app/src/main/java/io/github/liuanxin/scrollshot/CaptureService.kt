package io.github.liuanxin.scrollshot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

class CaptureService : AccessibilityService() {
    companion object { var instance: CaptureService? = null; private set }
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var active = false
    private var stopping = false
    private var busy = false
    private var generation = 0
    private var windowId = -1
    private var windowBounds = Rect()
    private var statusBar: Bitmap? = null
    private var captureTop = 0
    private var region = Rect()
    private var screenRegion = Rect()
    private var previous: Bitmap? = null
    private var previousFrame: OverlapMatcher.Frame? = null
    private val debuggable by lazy { applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 }
    private var document: CaptureDocument? = null
    private var overlay: TextView? = null
    private var dimOverlay: View? = null
    private var feedback: TextView? = null
    private var lastFrame: OverlapMatcher.Frame? = null
    private var stabilityStartedAt = 0L
    private var lastScreenshotAt = 0L
    private var unchangedCount = 0
    private var startedAt = 0L
    private var finishReason = "已停止"
    private var boundsRefined = false

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stop("服务被中断") }
    override fun onDestroy() {
        generation++
        active = false
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        statusBar?.recycle()
        statusBar = null
        removeFeedback()
        worker.execute {
            previous?.recycle()
            previous = null
            document?.let { doc ->
                if (doc.reason.isEmpty()) {
                    doc.reason = "服务被中断, 已恢复完成部分"
                    try { doc.persist() } catch (_: Exception) {}
                }
            }
        }
        worker.shutdown()
        if (instance === this) { instance = null }
        super.onDestroy()
    }

    fun requestStart() {
        removeFeedback()
        if (active) { stop("已停止"); return }
        val token = ++generation
        handler.postDelayed({ awaitTarget(token, 0) }, 600)
    }

    private fun awaitTarget(token: Int, attempt: Int) {
        if (token != generation) { return }
        val window = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
        val root = window?.root
        if (window == null || root?.packageName?.toString() == "com.android.systemui") {
            if (attempt < 15) { handler.postDelayed({ awaitTarget(token, attempt + 1) }, 200) }
            else { showFeedback("未能获取当前页面, 请收起快捷面板后重试") }
            return
        }
        windowId = window.id
        window.getBoundsInScreen(windowBounds)
        screenRegion.set(windowBounds)
        // 节点只辅助避开固定栏; 缺少节点或滚动声明时仍从整个当前窗口开始.
        val scroll = root?.let { findScroll(it, windowBounds) }
        if (scroll != null) {
            scroll.getBoundsInScreen(screenRegion)
            screenRegion.intersect(windowBounds)
            clipFixedSiblings(scroll)
            if (screenRegion.height() < 250 || screenRegion.width() <= 0) { screenRegion.set(windowBounds) }
        }
        active = true
        stopping = false
        busy = false
        startedAt = SystemClock.uptimeMillis()
        previous = null
        previousFrame = null
        document = null
        unchangedCount = 0
        boundsRefined = false
        lastFrame = null
        stabilityStartedAt = 0L
        captureStatusBar(token)
    }

    private fun captureStatusBar(token: Int) {
        statusBar?.recycle()
        statusBar = null
        captureTop = windowBounds.top
        val metrics = getSystemService(WindowManager::class.java).currentWindowMetrics
        val top = metrics.windowInsets.getInsets(android.view.WindowInsets.Type.statusBars()).top
        // 仅补齐位于屏幕顶部的应用, 分屏下方窗口不带入其他应用画面.
        if (top <= 0 || windowBounds.top > top) {
            showMonitor()
            captureStable(token, true)
            return
        }
        busy = true
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val screen = readScreenshot(result)
                if (!active || token != generation) { screen?.recycle(); return }
                if (screen == null) { busy = false; stop("无法读取系统状态栏"); return }
                try {
                    if (!targetValid()) { busy = false; stop("页面已变化, 请重新截图"); return }
                    val sx = screen.width.toFloat() / metrics.bounds.width()
                    val sy = screen.height.toFloat() / metrics.bounds.height()
                    val left = (windowBounds.left * sx).toInt().coerceIn(0, screen.width - 1)
                    val right = (windowBounds.right * sx).toInt().coerceIn(left + 1, screen.width)
                    statusBar = Bitmap.createBitmap(screen, left, 0, right - left, (top * sy).toInt().coerceIn(1, screen.height))
                    captureTop = 0
                } finally { if (screen !== statusBar) { screen.recycle() } }
                showMonitor()
                captureStable(token, true)
            }
            override fun onFailure(errorCode: Int) {
                if (!active || token != generation) { return }
                busy = false
                stop("系统状态栏截图失败($errorCode), 请重试")
            }
        })
    }

    private fun readScreenshot(result: ScreenshotResult): Bitmap? {
        try {
            val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
            return try { hardware?.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware?.recycle() }
        } finally { result.hardwareBuffer.close() }
    }

    private fun includeStatusBar(window: Bitmap): Bitmap {
        val bar = statusBar ?: return window
        val addedTop = ((windowBounds.top - captureTop) * window.height.toFloat() / windowBounds.height()).toInt()
        val result = Bitmap.createBitmap(window.width, window.height + addedTop, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        canvas.drawBitmap(window, 0f, addedTop.toFloat(), null)
        val height = (bar.height.toFloat() * window.width / bar.width).toInt().coerceAtMost(result.height)
        canvas.drawBitmap(bar, null, Rect(0, 0, result.width, height), null)
        window.recycle()
        return result
    }

    private fun findScroll(root: AccessibilityNodeInfo, bounds: Rect): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var area = 0L
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 2000) {
            val node = queue.removeFirst()
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (node.isVisibleToUser && node.isScrollable && rect.intersect(bounds)) {
                val size = rect.width().toLong() * rect.height()
                if (size > area && rect.height() > bounds.height() / 3) { area = size; best = node }
            }
            for (i in 0 until node.childCount) { node.getChild(i)?.let { queue.add(it) } }
        }
        return best
    }

    private fun clipFixedSiblings(scroll: AccessibilityNodeInfo) {
        var current = scroll
        var depth = 0
        while (depth++ < 24) {
            val parent = current.parent ?: break
            for (i in 0 until parent.childCount) {
                val sibling = parent.getChild(i) ?: continue
                if (sibling == current || !sibling.isVisibleToUser) { continue }
                val bounds = Rect()
                sibling.getBoundsInScreen(bounds)
                if (bounds.width() < windowBounds.width() * 0.7 || bounds.height() > windowBounds.height() * 0.35) { continue }
                if (bounds.top <= windowBounds.top + windowBounds.height() / 4 && bounds.bottom > screenRegion.top && bounds.bottom < screenRegion.bottom) {
                    screenRegion.top = bounds.bottom
                } else if (bounds.bottom >= windowBounds.bottom - windowBounds.height() / 4 && bounds.top < screenRegion.bottom && bounds.top > screenRegion.top) {
                    screenRegion.bottom = bounds.top
                }
            }
            current = parent
        }
    }

    private fun targetValid(): Boolean {
        val target = windows.firstOrNull { it.id == windowId } ?: return false
        val rect = Rect()
        target.getBoundsInScreen(rect)
        return rect == windowBounds &&
            windows.none { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive && it.id != windowId }
    }

    private fun captureStable(token: Int, first: Boolean) {
        val probeStart = SystemClock.uptimeMillis()
        if (!active || token != generation) { return }
        if (!targetValid()) { busy = false; stop("页面或屏幕方向已变化, 已保留完成部分"); return }
        busy = true
        val now = SystemClock.uptimeMillis()
        // 系统截图间隔阈值为 333ms, 留少量余量避免限流后额外重试.
        val delay = 350L - (now - lastScreenshotAt)
        if (delay > 0) {
            handler.postDelayed({ captureStable(token, first) }, delay)
            return
        }
        if (stabilityStartedAt == 0L) { stabilityStartedAt = now }
        lastScreenshotAt = now
        takeScreenshotOfWindow(windowId, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                trace("截图回调", probeStart)
                val captured = readScreenshot(result)
                if (!active || token != generation) { captured?.recycle(); return }
                if (captured == null) { busy = false; stop("无法读取画面"); return }
                val bitmap = includeStatusBar(captured)
                if (first) {
                    val sx = bitmap.width.toFloat() / windowBounds.width()
                    val sy = bitmap.height.toFloat() / (windowBounds.bottom - captureTop)
                    region = Rect(((screenRegion.left - windowBounds.left) * sx).toInt(), ((maxOf(screenRegion.top, windowBounds.top) - captureTop) * sy).toInt(), ((screenRegion.right - windowBounds.left) * sx).toInt(), ((screenRegion.bottom - captureTop) * sy).toInt())
                    statusBar?.let { bar -> region.top = maxOf(region.top, (bar.height.toFloat() * bitmap.width / bar.width).toInt()) }
                    region.intersect(0, 0, bitmap.width, bitmap.height)
                }
                worker.execute {
                    try {
                        val frame = sample(bitmap)
                        trace("采样完成", probeStart)
                        val last = lastFrame
                        // 首帧直接保留当前位置, 后续才检测稳定与重叠.
                        val stable = first || (last != null && OverlapMatcher.isStable(last, frame))
                        lastFrame = frame
                        handler.post {
                            if (!active || token != generation) { bitmap.recycle(); return@post }
                            if (!stable && SystemClock.uptimeMillis() - stabilityStartedAt < 3000L) {
                                bitmap.recycle()
                                handler.post { captureStable(token, first) }
                            } else if (!stable) {
                                bitmap.recycle()
                                busy = false
                                stop("画面持续变化, 已暂停并保留完成部分")
                            } else {
                                stabilityStartedAt = 0L
                                lastFrame = null
                                process(bitmap, frame, token, first)
                            }
                        }
                    } catch (error: Exception) {
                        bitmap.recycle()
                        handler.post { busy = false; stop("画面处理失败, 已保留完成部分") }
                    }
                }
            }
            override fun onFailure(errorCode: Int) {
                if (!active || token != generation) { return }
                if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                    trace("截图限频", lastScreenshotAt)
                    if (SystemClock.uptimeMillis() - stabilityStartedAt >= 3000L) {
                        busy = false
                        stop("系统截图暂不可用, 已保留完成部分")
                    } else { handler.postDelayed({ captureStable(token, first) }, 400) }
                } else {
                    busy = false
                    stop(if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) { "当前页面禁止截图" } else { "截图失败($errorCode), 已保留完成部分" })
                }
            }
        })
    }

    private fun sample(bitmap: Bitmap): OverlapMatcher.Frame {
        require(region.width() > 0 && region.height() > 0 && region.bottom <= bitmap.height)
        // 裁剪与横向缩小一步完成, 不分配整块区域的中间位图.
        val matrix = android.graphics.Matrix().apply { setScale(96f / region.width(), 1f) }
        val small = Bitmap.createBitmap(bitmap, region.left, region.top, region.width(), region.height(), matrix, true)
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        for (i in pixels.indices) {
            val c = pixels[i]
            pixels[i] = (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8
        }
        val frame = OverlapMatcher.Frame(small.width, small.height, pixels)
        if (small !== bitmap) { small.recycle() }
        return frame
    }

    private fun process(bitmap: Bitmap, frame: OverlapMatcher.Frame, token: Int, first: Boolean) {
        worker.execute {
            val processStart = SystemClock.uptimeMillis()
            var message: String? = null
            var accepted = false
            try {
                if (first) {
                    cleanDrafts()
                    val directory = File(filesDir, "captures/${System.currentTimeMillis()}")
                    val doc = CaptureDocument(directory, bitmap.width)
                    // 拼接文档只延后固定底栏; debug 包另存首帧原图便于核对.
                    directory.mkdirs()
                    if (debuggable) { File(directory, "first.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    doc.append(bitmap, 0, region.bottom)
                    document = doc
                    previousFrame = frame
                    accepted = true
                } else {
                    val old = requireNotNull(previous)
                    var oldFrame = requireNotNull(previousFrame)
                    var nowFrame = frame
                    if (!boundsRefined && OverlapMatcher.difference(oldFrame, nowFrame) >= 0.7) {
                        val before = Rect(region)
                        refineContentBounds(old, bitmap)
                        document?.replaceInitial(old, region.bottom)
                        boundsRefined = true
                        if (region != before) {
                            oldFrame = sample(old)
                            nowFrame = sample(bitmap)
                        }
                    }
                    val match = OverlapMatcher.match(oldFrame, nowFrame)
                    if (debuggable) { android.util.Log.d("ScrollShot", "region=$region match=$match") }
                    when (match) {
                        is OverlapMatcher.Result.Match -> {
                            val shift = refineShift(old, bitmap, match.shift)
                            if (shift == null) { message = "拼接位置不确定, 已保留完成部分" }
                            else {
                                val doc = requireNotNull(document)
                                if (doc.height + shift + bitmap.height - region.bottom > doc.maxHeight) { message = "已达到安全长度上限" }
                                else {
                                    doc.append(bitmap, region.bottom - shift, region.bottom)
                                    previousFrame = nowFrame
                                    accepted = true
                                    unchangedCount = 0
                                }
                            }
                        }
                        OverlapMatcher.Result.Unchanged -> {
                            unchangedCount++
                            if (unchangedCount >= 2) { message = "页面未继续移动, 已结束截取" }
                        }
                        OverlapMatcher.Result.Uncertain -> { message = "页面跳动或重叠不明确, 已保留完成部分" }
                    }
                }
                if (message != null && !first && debuggable) {
                    try {
                        document?.let { doc ->
                            File(doc.directory, "failed-current.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            previous?.let { image -> File(doc.directory, "failed-previous.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                        }
                    } catch (_: Exception) {}
                }
            } catch (error: Exception) { message = "处理或存储失败, 已保留完成部分" }
            handler.post {
                if (!active || token != generation) { bitmap.recycle(); return@post }
                if (accepted) { previous?.recycle(); previous = bitmap }
                else { bitmap.recycle() }
                busy = false
                trace("拼接完成", processStart)
                if (message != null) { stop(message!!) }
                else if (stopping) { finishCapture() }
                else {
                    if (overlay == null) { showMonitor() }
                    overlay?.text = "${document?.height ?: 0}px · 点击此处停止"
                    handler.post { scrollNext(token) }
                }
            }
        }
    }

    private fun refineContentBounds(old: Bitmap, now: Bitmap) {
        if (old.width != now.width || old.height != now.height) { return }
        fun moving(y: Int): Boolean {
            var changed = 0
            var samples = 0
            for (x in region.left + region.width() / 20 until region.right - region.width() / 20 step 4) {
                val a = old.getPixel(x, y)
                val b = now.getPixel(x, y)
                val delta = abs((a and 255) - (b and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs(((a shr 16) and 255) - ((b shr 16) and 255))
                if (delta > 60) { changed++ }
                samples++
            }
            return changed > maxOf(5, samples / 20)
        }
        var top = region.top
        var bottom = region.bottom
        while (top < region.bottom && !moving(top)) { top++ }
        while (bottom > top && !moving(bottom - 1)) { bottom-- }
        // 只剔除连续静止的边缘区域; 首屏顶栏仍保留在文档开头.
        if (bottom - top >= region.height() / 2) {
            if (top - region.top >= 40) { region.top = top }
            if (region.bottom - bottom >= 40) { region.bottom = bottom }
        }
    }

    private fun refineShift(old: Bitmap, now: Bitmap, estimate: Int): Int? {
        if (old.width != now.width || old.height != now.height) { return null }
        val radius = maxOf(3, region.width() / 160 * 2)
        var best = Double.MAX_VALUE
        var bestShift = -1
        for (shift in maxOf(1, estimate - radius)..minOf(region.height() * 3 / 4, estimate + radius)) {
            var error = 0L
            var count = 0
            for (y in region.top + 4 until region.bottom - shift - 4 step 5) {
                for (x in region.left + region.width() / 10 until region.right - region.width() / 10 step maxOf(1, region.width() / 48)) {
                    val a = old.getPixel(x, y + shift)
                    val b = now.getPixel(x, y)
                    error += abs(((a shr 16) and 255) - ((b shr 16) and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))
                    count += 3
                }
            }
            val score = error.toDouble() / maxOf(count, 1)
            if (score < best) { best = score; bestShift = shift }
        }
        return if (best < 13.0 && bestShift > 0) { bestShift } else { null }
    }

    private fun scrollNext(token: Int) {
        if (!active || token != generation || stopping) { return }
        if (!targetValid()) { stop("页面已变化, 已保留完成部分"); return }
        if (SystemClock.uptimeMillis() - startedAt > 600_000) { stop("已达到本次截取时长上限"); return }
        val x = screenRegion.exactCenterX()
        val startY = screenRegion.top + screenRegion.height() * 0.80f
        val endY = screenRegion.top + screenRegion.height() * 0.30f
        val path = Path().apply { moveTo(x, startY); lineTo(x, endY) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 850)).build()
        val submitted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (active && !stopping && token == generation) { handler.postDelayed({ captureStable(token, false) }, 100) }
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (active && !stopping && token == generation) { stop("滚动被打断, 已保留完成部分") }
            }
        }, handler)
        if (!submitted) { stop("系统未接受滚动操作, 已保留完成部分") }
    }

    private fun showMonitor() {
        val wm = getSystemService(WindowManager::class.java)
        val dim = View(this).apply { setBackgroundColor(0x22000000) }
        wm.addView(dim, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT))
        dimOverlay = dim
        val text = TextView(this).apply {
            text = "正在截取 · 点击此处停止"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xf02b4941.toInt())
                cornerRadius = 22 * resources.displayMetrics.density
                setStroke((resources.displayMetrics.density).toInt(), 0xff94cbb8.toInt())
            }
            elevation = 6 * resources.displayMetrics.density
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) { stopByTouch() }
                true
            }
        }
        val density = resources.displayMetrics.density
        val params = WindowManager.LayoutParams((250 * density).toInt(), (44 * density).toInt(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (12 * density).toInt() }
        overlay = text
        wm.addView(text, params)
    }

    private fun stopByTouch() {
        if (stopping || !active) { return }
        // 点击始终由提示条自身接收. 取消手势也落在提示条上, 不发送给原页面.
        stopping = true
        finishReason = "已手动停止"
        overlay?.let { view ->
            view.text = "正在完成截图..."
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val cancelPath = Path().apply { moveTo(location[0] + view.width / 2f, location[1] + view.height / 2f) }
            dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(cancelPath, 0, 1)).build(), null, handler)
        }
        if (!busy) { busy = true; handler.postDelayed({ captureStable(generation, false) }, 350) }
    }

    private fun stop(reason: String) {
        if (!active) { return }
        finishReason = reason
        stopping = true
        if (!busy) { finishCapture() }
    }

    private fun finishCapture() {
        if (!active || busy) { return }
        busy = true
        val doc = document
        val last = previous
        previous = null
        previousFrame = null
        worker.execute {
            var saved = false
            try {
                if (doc != null && last != null) {
                    if (last.height > region.bottom && doc.height + last.height - region.bottom <= doc.maxHeight) { doc.append(last, region.bottom, last.height) }
                    doc.reason = finishReason
                    doc.persist()
                    saved = true
                }
            } catch (error: Exception) {
                saved = doc != null && File(doc.directory, "document.json").isFile
            } finally { last?.recycle() }
            handler.post {
                active = false
                busy = false
                generation++
                removeOverlay()
                statusBar?.recycle()
                statusBar = null
                if (saved && doc != null) {
                    startActivity(Intent(this, EditorActivity::class.java).putExtra("capture", doc.directory.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else { showFeedback(finishReason) }
            }
        }
    }

    private fun removeOverlay() {
        overlay?.let { try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: IllegalArgumentException) {} }
        dimOverlay?.let { try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: IllegalArgumentException) {} }
        dimOverlay = null
        overlay = null
    }
    /** 只保留最新一份有效草稿供恢复, 其余目录连同调试图一并删除. */
    private fun cleanDrafts() {
        val dirs = File(filesDir, "captures").listFiles() ?: return
        var latest: File? = null
        for (dir in dirs) {
            if (File(dir, "document.json").isFile && (latest == null || dir.name > latest.name)) { latest = dir }
        }
        for (dir in dirs) {
            if (dir != latest) { dir.deleteRecursively() }
        }
    }

    private fun trace(stage: String, start: Long) {
        if (debuggable) {
            android.util.Log.d("ScrollShotTiming", "$stage ${SystemClock.uptimeMillis() - start}ms")
        }
    }
    private fun removeFeedback() {
        feedback?.let { try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: IllegalArgumentException) {} }
        feedback = null
    }

    private fun showFeedback(message: String) {
        android.util.Log.d("ScrollShotEntry", message)
        removeFeedback()
        val density = resources.displayMetrics.density
        val view = TextView(this).apply {
            text = message
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xf02b4941.toInt())
                cornerRadius = 18 * density
            }
        }
        val params = WindowManager.LayoutParams(
            minOf((340 * density).toInt(), resources.displayMetrics.widthPixels - (32 * density).toInt()), -2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (12 * density).toInt() }
        try {
            getSystemService(WindowManager::class.java).addView(view, params)
            feedback = view
            handler.postDelayed({ if (feedback === view) { removeFeedback() } }, 3500)
        } catch (_: WindowManager.BadTokenException) {}
    }
}
