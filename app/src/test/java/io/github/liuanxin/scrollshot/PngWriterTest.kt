package io.github.liuanxin.scrollshot

import org.junit.Assert
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class PngWriterTest {
    @Test fun exportedPixelsAndDimensionsAreExact() {
        val output = ByteArrayOutputStream()
        PngWriter(output, 47, 1234).use { png ->
            for (y in 0 until 1234) { png.row(IntArray(47) { x -> 0xff000000.toInt() or ((x * 5) shl 16) or ((y % 256) shl 8) or ((x + y) % 256) }) }
        }
        val image = ImageIO.read(ByteArrayInputStream(output.toByteArray()))
        Assert.assertEquals(47, image.width)
        Assert.assertEquals(1234, image.height)
        for (y in 0 until 1234) {
            for (x in 0 until 47) { Assert.assertEquals(0xff000000.toInt() or ((x * 5) shl 16) or ((y % 256) shl 8) or ((x + y) % 256), image.getRGB(x, y)) }
        }
    }
    @Test fun handlesMultipleCompressedChunks() {
        val output = ByteArrayOutputStream()
        val random = java.util.Random(93)
        val expected = IntArray(256 * 600) { 0xff000000.toInt() or random.nextInt(0x1000000) }
        PngWriter(output, 256, 600).use { png ->
            for (y in 0 until 600) { png.row(expected.copyOfRange(y * 256, (y + 1) * 256)) }
        }
        Assert.assertTrue(output.size() > 65536)
        val image = ImageIO.read(ByteArrayInputStream(output.toByteArray()))
        for (y in 0 until 600) {
            for (x in 0 until 256) { Assert.assertEquals(expected[y * 256 + x], image.getRGB(x, y)) }
        }
    }
    @Test(expected = IllegalStateException::class) fun refusesIncompleteImage() {
        PngWriter(ByteArrayOutputStream(), 10, 3).use { it.row(IntArray(10)) }
    }
}
