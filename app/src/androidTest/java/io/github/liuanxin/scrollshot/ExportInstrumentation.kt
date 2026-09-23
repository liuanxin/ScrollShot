package io.github.liuanxin.scrollshot

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Bundle
import java.io.File

/** 无第三方测试依赖, 在真实 Android 图像编解码器上验证分段导出. */
class ExportInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val root = File(targetContext.cacheDir, "export-instrumentation")
        root.deleteRecursively()
        root.mkdirs()
        val result = Bundle()
        try {
            var zoomFailure: Throwable? = null
            runOnMainSync {
                try { verifyZoom() } catch (error: Throwable) { zoomFailure = error }
            }
            zoomFailure?.let { throw it }
            val doc = CaptureDocument(File(root, "parts"), 128)
            for (part in 0 until 120) {
                val bitmap = Bitmap.createBitmap(128, 1000, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(128 * 1000) { i -> color(i % 128, part * 1000 + i / 128) }
                bitmap.setPixels(pixels, 0, 128, 0, 0, 128, 1000)
                doc.append(bitmap, 0, 1000)
                bitmap.recycle()
            }
            val full = File(root, "long.png")
            ImageExporter.write(doc, Rect(0, 0, 128, 120000), full) { false }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(full.path, bounds)
            check(bounds.outWidth == 64 && bounds.outHeight == 60000) { "超长图缩小尺寸错误" }
            val cropped = File(root, "crop.png")
            val crop = Rect(17, 750, 115, 2345)
            ImageExporter.write(doc, crop, cropped) { false }
            val decoded = requireNotNull(BitmapFactory.decodeFile(cropped.path))
            check(decoded.width == 98 && decoded.height == 1595)
            for (y in 0 until decoded.height) {
                for (x in 0 until decoded.width) { check(decoded.getPixel(x, y) == color(x + 17, y + 750)) { "跨分段裁剪像素错误($x,$y)" } }
            }
            decoded.recycle()
            val jpeg = File(root, "long.jpg")
            ImageExporter.write(doc, Rect(0, 0, 128, 120000), jpeg, false) { false }
            val jpegBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(jpeg.path, jpegBounds)
            check(jpegBounds.outWidth == 64 && jpegBounds.outHeight == 60000 && jpegBounds.outMimeType == "image/jpeg")
            ImageExporter.write(doc, crop, jpeg, false) { false }
            val jpegCrop = requireNotNull(BitmapFactory.decodeFile(jpeg.path))
            check(jpegCrop.width == 98 && jpegCrop.height == 1595)
            var delta = 0L
            for (y in 0 until jpegCrop.height) {
                for (x in 0 until jpegCrop.width) {
                    val actual = jpegCrop.getPixel(x, y)
                    val expected = color(x + 17, y + 750)
                    for (shift in intArrayOf(0, 8, 16)) { delta += kotlin.math.abs(((actual shr shift) and 255) - ((expected shr shift) and 255)) }
                }
            }
            check(delta.toDouble() / (jpegCrop.width * jpegCrop.height * 3) < 8.0) { "JPEG 裁剪误差过大" }
            jpegCrop.recycle()
            val regular = ImageExporter.spec(Rect(0, 0, 1080, 120000), false)
            check(regular.width.toLong() * regular.height <= 16_020_000L && regular.height <= 60000)
            try { ImageExporter.write(doc, crop, File(root, "cancel.jpg"), false) { true } } catch (_: Exception) {}
            check(!File(root, "cancel.jpg").exists())
            val source = File(targetContext.filesDir, "export-fixture.png")
            if (source.isFile) {
                val fixtureImage = requireNotNull(BitmapFactory.decodeFile(source.path))
                val fixtureDoc = CaptureDocument(File(root, "fixture"), fixtureImage.width)
                fixtureDoc.append(fixtureImage, 0, fixtureImage.height)
                val fixtureCrop = Rect(0, 0, fixtureImage.width, fixtureImage.height)
                fixtureImage.recycle()
                val pngOutput = File(targetContext.cacheDir, "compare-hd.png")
                val jpgOutput = File(targetContext.cacheDir, "compare-normal.jpg")
                ImageExporter.write(fixtureDoc, fixtureCrop, pngOutput, true) { false }
                ImageExporter.write(fixtureDoc, fixtureCrop, jpgOutput, false) { false }
                result.putString("sizes", "PNG=${pngOutput.length()}, JPG=${jpgOutput.length()}")
            }
            var cancelled = false
            try { ImageExporter.write(doc, Rect(0, 0, 128, 120000), File(root, "cancel.png")) { true } }
            catch (_: Exception) { cancelled = true }
            check(cancelled && !File(root, "cancel.png").exists())
            check(ImageExporter.friendlyBytes(2048) == "2.0 KB")
            check(ImageExporter.friendlyBytes(2L * 1024 * 1024) == "2.00 MB")
            result.putString("stream", "PASS: pinch shrink/enlarge preserves crop, 120000px -> 60000px, cross-part crop exact, cancellation cleanup, friendly sizes, JPEG dimensions/crop/cancellation\n")
            root.deleteRecursively()
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            root.deleteRecursively()
            finish(Activity.RESULT_CANCELED, result)
        } finally { root.deleteRecursively() }
    }
    private fun verifyZoom() {
        val source = Bitmap.createBitmap(108, 242, Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.WHITE)
        val view = CropView(targetContext, source, 1080, 2424)
        view.layout(0, 0, 1080, 1800)
        val originalCrop = view.cropPixels()
        fun visibleWidth(): Int {
            val rendered = Bitmap.createBitmap(1080, 1800, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(rendered))
            var left = 1080
            var right = -1
            for (x in 0 until 1080) {
                if (rendered.getPixel(x, 900) == android.graphics.Color.WHITE) {
                    left = minOf(left, x)
                    right = maxOf(right, x)
                }
            }
            rendered.recycle()
            return maxOf(0, right - left + 1)
        }
        var time = android.os.SystemClock.uptimeMillis()
        val down = time
        fun event(action: Int, span: Float, count: Int = 2, center: Float = 540f) {
            val properties = Array(count) { index -> android.view.MotionEvent.PointerProperties().apply {
                id = index
                toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
            } }
            val coordinates = Array(count) { index -> android.view.MotionEvent.PointerCoords().apply {
                x = center + if (index == 0) { -span / 2 } else { span / 2 }
                y = 900f
                pressure = 1f
                size = 1f
            } }
            time += 20
            val motion = android.view.MotionEvent.obtain(down, time, action, count, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            view.dispatchTouchEvent(motion)
            motion.recycle()
        }
        event(android.view.MotionEvent.ACTION_DOWN, 900f, 1)
        event(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 900f)
        event(android.view.MotionEvent.ACTION_MOVE, 880f)
        check(visibleWidth() in 980..1010) { "双指细小移动未立即缩放" }
        for (span in 880 downTo 80 step 20) { event(android.view.MotionEvent.ACTION_MOVE, span.toFloat()) }
        val smallWidth = visibleWidth()
        check(smallWidth in 50..110) { "单屏截图无法缩小: width=$smallWidth" }
        event(android.view.MotionEvent.ACTION_MOVE, 80f, center = 1500f)
        check(visibleWidth() == 0) { "双指平移未跟随中心" }
        event(android.view.MotionEvent.ACTION_MOVE, 80f)
        check(visibleWidth() == smallWidth) { "双指平移改变了比例" }
        for (span in 80..900 step 20) { event(android.view.MotionEvent.ACTION_MOVE, span.toFloat()) }
        check(visibleWidth() > smallWidth * 1.5) { "缩小后无法放大" }
        event(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl 8), 900f)
        val beforeDrag = visibleWidth()
        event(android.view.MotionEvent.ACTION_MOVE, 900f, 1, 790f)
        check(visibleWidth() < beforeDrag - 150) { "松开一指后无法继续平移" }
        event(android.view.MotionEvent.ACTION_UP, 900f, 1, 790f)
        check(view.cropPixels() == originalCrop) { "预览缩放改变了裁剪尺寸" }
        source.recycle()
    }
    private fun color(x: Int, y: Int): Int = 0xff000000.toInt() or ((x * 2) shl 16) or ((y % 256) shl 8) or ((x + y) % 256)
}
