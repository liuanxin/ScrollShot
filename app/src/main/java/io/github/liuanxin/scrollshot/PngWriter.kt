package io.github.liuanxin.scrollshot

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** 按行写 PNG, 输出尺寸不受单张 Bitmap 或 GPU 纹理上限限制. */
class PngWriter(output: OutputStream, val width: Int, val height: Int) : AutoCloseable {
    private val target = DataOutputStream(output)
    private val buffer = ByteArrayOutputStream(65536)
    private val deflater = Deflater(3)
    private val compressed: DeflaterOutputStream
    private var rows = 0
    init {
        require(width > 0 && height > 0)
        target.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use { it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8, 2, 0, 0, 0)) }
        chunk("IHDR", header.toByteArray())
        compressed = DeflaterOutputStream(object : OutputStream() {
            override fun write(value: Int) { buffer.write(value); flushChunk() }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { buffer.write(bytes, offset, length); flushChunk() }
            private fun flushChunk() {
                if (buffer.size() >= 65536) { chunk("IDAT", buffer.toByteArray()); buffer.reset() }
            }
        }, deflater, 32768)
    }
    fun row(pixels: IntArray) {
        require(pixels.size >= width && rows < height)
        val bytes = ByteArray(1 + width * 3)
        for (x in 0 until width) {
            bytes[x * 3 + 1] = (pixels[x] shr 16).toByte()
            bytes[x * 3 + 2] = (pixels[x] shr 8).toByte()
            bytes[x * 3 + 3] = pixels[x].toByte()
        }
        compressed.write(bytes)
        rows++
    }
    override fun close() {
        try {
            check(rows == height) { "图片行数不完整" }
            compressed.finish()
            if (buffer.size() > 0) { chunk("IDAT", buffer.toByteArray()) }
            chunk("IEND", ByteArray(0))
            target.flush()
        } finally { deflater.end() }
    }
    private fun chunk(type: String, bytes: ByteArray) {
        val name = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32()
        crc.update(name)
        crc.update(bytes)
        target.writeInt(bytes.size)
        target.write(name)
        target.write(bytes)
        target.writeInt(crc.value.toInt())
    }
}
