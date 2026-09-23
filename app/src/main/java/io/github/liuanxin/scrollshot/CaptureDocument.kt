package io.github.liuanxin.scrollshot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 全分辨率分段落盘, 预览单独缩小, 不分配整张长图位图. */
class CaptureDocument(val directory: File, val width: Int) {
    data class Part(val file: String, val height: Int)
    val parts = ArrayList<Part>()
    var height = 0
        private set
    var reason = ""
    val maxHeight: Int get() = minOf(120000, 160_000_000 / width)

    fun append(bitmap: Bitmap, top: Int, bottom: Int) {
        require(bitmap.width == width && top >= 0 && bottom <= bitmap.height && bottom > top)
        require(height + bottom - top <= maxHeight)
        directory.mkdirs()
        val name = "part-%04d.png".format(parts.size)
        val part = Bitmap.createBitmap(bitmap, 0, top, width, bottom - top)
        try {
            File(directory, name).outputStream().use { output -> check(part.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        } finally {
            if (part !== bitmap) { part.recycle() }
        }
        parts.add(Part(name, bottom - top))
        height += bottom - top
        persist()
    }

    fun replaceInitial(bitmap: Bitmap, bottom: Int) {
        require(parts.size == 1 && bottom in 1..bitmap.height)
        val prefix = Bitmap.createBitmap(bitmap, 0, 0, width, bottom)
        val name = "prefix.png"
        try {
            File(directory, name).outputStream().use { check(prefix.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { if (prefix !== bitmap) { prefix.recycle() } }
        val old = parts[0]
        parts[0] = Part(name, bottom)
        height = bottom
        persist()
        File(directory, old.file).delete()
    }

    fun persist() {
        val list = JSONArray()
        for (part in parts) { list.put(JSONObject().put("file", part.file).put("height", part.height)) }
        val json = JSONObject().put("width", width).put("height", height).put("reason", reason).put("parts", list)
        val temp = File(directory, "document.tmp")
        temp.writeText(json.toString())
        check(temp.renameTo(File(directory, "document.json")))
    }

    fun preview(): Bitmap {
        val scale = minOf(1f, 900f / width, 11000f / height)
        val result = Bitmap.createBitmap(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()), Bitmap.Config.RGB_565)
        val canvas = Canvas(result)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var y = 0
        for (part in parts) {
            val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, (1 / scale).toInt()); inPreferredConfig = Bitmap.Config.RGB_565 }
            val image = requireNotNull(BitmapFactory.decodeFile(File(directory, part.file).path, options))
            canvas.drawBitmap(image, null, Rect(0, (y * scale).toInt(), result.width, ((y + part.height) * scale).toInt()), paint)
            image.recycle()
            y += part.height
        }
        return result
    }

    companion object {
        fun read(directory: File): CaptureDocument {
            val json = JSONObject(File(directory, "document.json").readText())
            val doc = CaptureDocument(directory, json.getInt("width"))
            doc.reason = json.optString("reason")
            val list = json.getJSONArray("parts")
            for (i in 0 until list.length()) {
                val part = list.getJSONObject(i)
                doc.parts.add(Part(part.getString("file"), part.getInt("height")))
                doc.height += part.getInt("height")
            }
            return doc
        }
    }
}
