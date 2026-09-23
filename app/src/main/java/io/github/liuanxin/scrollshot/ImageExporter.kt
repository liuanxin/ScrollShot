package io.github.liuanxin.scrollshot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt
import kotlin.math.sqrt

object ImageExporter {
    data class Spec(val width: Int, val height: Int, val scale: Double)
    fun spec(crop: Rect): Spec {
        val scale = minOf(1.0, 60000.0 / crop.height(), sqrt(40_000_000.0 / (crop.width().toDouble() * crop.height())))
        return Spec(maxOf(1, (crop.width() * scale).roundToInt()), maxOf(1, (crop.height() * scale).roundToInt()), scale)
    }
    fun write(doc: CaptureDocument, crop: Rect, file: File, cancelled: () -> Boolean) {
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
