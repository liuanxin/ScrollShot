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
            result.putString("stream", "PASS: 120000px -> 60000px, cross-part crop exact, cancellation cleanup, friendly sizes, JPEG dimensions/crop/cancellation\n")
            root.deleteRecursively()
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            root.deleteRecursively()
            finish(Activity.RESULT_CANCELED, result)
        } finally { root.deleteRecursively() }
    }
    private fun color(x: Int, y: Int): Int = 0xff000000.toInt() or ((x * 2) shl 16) or ((y % 256) shl 8) or ((x + y) % 256)
}
