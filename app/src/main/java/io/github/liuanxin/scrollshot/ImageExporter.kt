package io.github.liuanxin.scrollshot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Rect
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt
import kotlin.math.sqrt

object ImageExporter {
    data class Spec(val width: Int, val height: Int, val scale: Double)
    fun spec(crop: Rect, highQuality: Boolean = true): Spec {
        val pixelLimit = if (highQuality) { 40_000_000.0 } else { minOf(16_000_000.0, Runtime.getRuntime().maxMemory() / 16.0) }
        val widthScale = if (highQuality) { 1.0 } else { minOf(1.0, 1080.0 / crop.width()) }
        val scale = minOf(widthScale, 60000.0 / crop.height(), sqrt(pixelLimit / (crop.width().toDouble() * crop.height())))
        return Spec(maxOf(1, (crop.width() * scale).roundToInt()), maxOf(1, (crop.height() * scale).roundToInt()), scale)
    }
    fun write(doc: CaptureDocument, crop: Rect, file: File, highQuality: Boolean = true, cancelled: () -> Boolean) {
        if (highQuality) { writePng(doc, crop, file, cancelled) }
        else { writeJpeg(doc, crop, file, cancelled) }
    }

    private fun writeJpeg(doc: CaptureDocument, crop: Rect, file: File, cancelled: () -> Boolean) {
        val spec = spec(crop, false)
        // 普通模式整图不超过堆上限的四分之一和 64MB; 高清继续按行编码.
        var output: Bitmap? = null
        try {
            if (cancelled()) { throw CancellationException() }
            output = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            var offset = 0
            var written = 0
            for (part in doc.parts) {
                if (cancelled()) { throw CancellationException() }
                val top = maxOf(0, crop.top - offset)
                val bottom = minOf(part.height, crop.bottom - offset)
                if (bottom > top) {
                    val targetBottom = ((offset + bottom - crop.top) * spec.scale).roundToInt().coerceAtMost(spec.height)
                    if (targetBottom > written) {
                        val image = requireNotNull(BitmapFactory.decodeFile(File(doc.directory, part.file).path))
                        try {
                            canvas.drawBitmap(image, Rect(crop.left, top, crop.right, bottom), Rect(0, written, spec.width, targetBottom), paint)
                        } finally { image.recycle() }
                        written = targetBottom
                    }
                }
                offset += part.height
            }
            check(written == spec.height) { "图片行数不完整" }
            if (cancelled()) { throw CancellationException() }
            file.outputStream().buffered().use { check(output.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
            if (cancelled()) { throw CancellationException() }
        } catch (error: Exception) { file.delete(); throw error }
        catch (error: OutOfMemoryError) { file.delete(); throw error }
        finally { output?.recycle() }
    }

    private fun writePng(doc: CaptureDocument, crop: Rect, file: File, cancelled: () -> Boolean) {
        val spec = spec(crop)
        try {
            file.outputStream().buffered().use { output ->
                PngWriter(output, spec.width, spec.height).use { png ->
                    val pixels = IntArray(spec.width)
                    var offset = 0
                    var written = 0
                    for (part in doc.parts) {
                        if (cancelled()) { throw CancellationException() }
                        val top = maxOf(0, crop.top - offset)
                        val bottom = minOf(part.height, crop.bottom - offset)
                        if (bottom > top) {
                            val targetBottom = ((offset + bottom - crop.top) * spec.scale).roundToInt().coerceAtMost(spec.height)
                            val targetHeight = targetBottom - written
                            if (targetHeight > 0) {
                                val original = requireNotNull(BitmapFactory.decodeFile(File(doc.directory, part.file).path))
                                var clipped: Bitmap? = null
                                var scaled: Bitmap? = null
                                try {
                                    clipped = Bitmap.createBitmap(original, crop.left, top, crop.width(), bottom - top)
                                    scaled = Bitmap.createScaledBitmap(clipped, spec.width, targetHeight, true)
                                    for (y in 0 until targetHeight) {
                                        if (y % 64 == 0 && cancelled()) { throw CancellationException() }
                                        scaled.getPixels(pixels, 0, spec.width, 0, y, spec.width, 1)
                                        png.row(pixels)
                                    }
                                } finally {
                                    if (scaled !== clipped && scaled !== original) { scaled?.recycle() }
                                    if (clipped !== original) { clipped?.recycle() }
                                    original.recycle()
                                }
                                written = targetBottom
                            }
                        }
                        offset += part.height
                    }
                }
            }
        } catch (error: Exception) { file.delete(); throw error }
    }

    fun friendlyBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.2f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024 * 1024))
        }
    }
}
