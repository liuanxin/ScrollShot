package io.github.liuanxin.scrollshot

import org.junit.Assert
import org.junit.Test
import java.util.Random

class OverlapMatcherTest {
    private val width = 120
    private val height = 240
    private fun document(): IntArray {
        val random = Random(9123)
        return IntArray(width * 700) { random.nextInt(256) }
    }
    private fun frame(data: IntArray, start: Int): OverlapMatcher.Frame = OverlapMatcher.Frame(width, height, data.copyOfRange(start * width, (start + height) * width))
    @Test fun findsExactOverlapForDifferentDistances() {
        val data = document()
        for (shift in intArrayOf(8, 37, 96, 160)) {
            val result = OverlapMatcher.match(frame(data, 0), frame(data, shift))
            Assert.assertTrue(result is OverlapMatcher.Result.Match)
            Assert.assertEquals(shift, (result as OverlapMatcher.Result.Match).shift)
        }
    }
    @Test fun detectsSmallAnimatedRegion() {
        val still = OverlapMatcher.Frame(width, height, IntArray(width * height) { 255 })
        val animated = still.pixels.copyOf()
        for (y in 60 until 67) {
            for (x in 40 until 47) { animated[y * width + x] = 0 }
        }
        val changed = OverlapMatcher.Frame(width, height, animated)
        Assert.assertTrue(OverlapMatcher.difference(still, changed) < 1.2)
        Assert.assertFalse(OverlapMatcher.isStable(still, changed))
        Assert.assertTrue(OverlapMatcher.isStable(still, still))
    }
    @Test fun detectsBottomWithoutDuplicatingContent() {
        val image = frame(document(), 0)
        Assert.assertEquals(OverlapMatcher.Result.Unchanged, OverlapMatcher.match(image, image))
    }
    @Test fun rejectsUpwardMovement() {
        val data = document()
        Assert.assertEquals(OverlapMatcher.Result.Uncertain, OverlapMatcher.match(frame(data, 80), frame(data, 0)))
    }
    @Test fun rejectsPageJumpWithoutOverlap() {
        val data = document()
        Assert.assertEquals(OverlapMatcher.Result.Uncertain, OverlapMatcher.match(frame(data, 0), frame(data, 300)))
    }
    @Test fun rejectsAmbiguousRepeatedRows() {
        val data = IntArray(width * 700) { i -> ((i / width % 20) * 47 + i % width * 71) % 256 }
        Assert.assertEquals(OverlapMatcher.Result.Uncertain, OverlapMatcher.match(frame(data, 0), frame(data, 7)))
    }
    @Test fun rejectsBlankBackgroundChange() {
        val a = OverlapMatcher.Frame(width, height, IntArray(width * height) { 255 })
        val b = OverlapMatcher.Frame(width, height, IntArray(width * height) { 245 })
        Assert.assertEquals(OverlapMatcher.Result.Uncertain, OverlapMatcher.match(a, b))
    }
}
