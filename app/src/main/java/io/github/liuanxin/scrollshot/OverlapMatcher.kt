package io.github.liuanxin.scrollshot

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 只接受向下滚动后的唯一重叠. 不猜测未捕获的内容. 输入为等比例缩小后的灰度图. */
object OverlapMatcher {
    data class Frame(val width: Int, val height: Int, val pixels: IntArray) {
        init { require(width > 0 && height > 0 && pixels.size == width * height) }
    }
    sealed class Result {
        data class Match(val shift: Int, val error: Double) : Result()
        data object Unchanged : Result()
        data object Uncertain : Result()
    }

    fun difference(a: Frame, b: Frame): Double {
        if (a.width != b.width || a.height != b.height) { return 255.0 }
        var sum = 0L
        for (i in a.pixels.indices) { sum += abs(a.pixels[i] - b.pixels[i]) }
        return sum.toDouble() / a.pixels.size
    }

    fun isStable(a: Frame, b: Frame): Boolean {
        if (a.width != b.width || a.height != b.height || difference(a, b) >= 1.2) { return false }
        // 分块检测局部动画, 避免小视频被大面积静止背景稀释.
        for (top in 0 until a.height step maxOf(1, a.height / 12)) {
            for (left in 0 until a.width step maxOf(1, a.width / 6)) {
                var error = 0L
                var count = 0
                for (y in top until min(a.height, top + maxOf(1, a.height / 12))) {
                    for (x in left until min(a.width, left + maxOf(1, a.width / 6))) {
                        error += abs(a.pixels[y * a.width + x] - b.pixels[y * a.width + x])
                        count++
                    }
                }
                if (count > 0 && error.toDouble() / count > 4.0) { return false }
            }
        }
        return true
    }

    fun match(previous: Frame, current: Frame): Result {
        if (previous.width != current.width || previous.height != current.height || previous.height < 40) { return Result.Uncertain }
        if (difference(previous, current) < 0.7) { return Result.Unchanged }
        val w = previous.width
        val h = previous.height
        val maxShift = h * 3 / 4
        val scores = DoubleArray(maxShift + 1) { 255.0 }
        var bestShift = 0
        var best = 255.0
        for (shift in 2..maxShift) {
            var total = 0.0
            var rows = 0
            var good = 0
            for (y in 2 until h - shift - 2 step 3) {
                var low = 255
                var high = 0
                var rowError = 0
                var samples = 0
                for (x in w / 12 until w - w / 12 step 2) {
                    val old = previous.pixels[(y + shift) * w + x]
                    val now = current.pixels[y * w + x]
                    low = min(low, old)
                    high = max(high, old)
                    rowError += abs(old - now)
                    samples++
                }
                // 空白背景没有定位信息, 不计入匹配可信度.
                if (high - low > 22 && samples > 0) {
                    val error = rowError.toDouble() / samples
                    total += min(error, 45.0)
                    rows++
                    if (error < 12.0) { good++ }
                }
            }
            if (rows >= max(10, (h - shift) / 18) && good.toDouble() / rows >= 0.78) {
                val score = total / rows
                scores[shift] = score
                if (score < best) { best = score; bestShift = shift }
            }
        }
        if (bestShift == 0 || best > 10.0) { return Result.Uncertain }
        for (shift in 2..maxShift) {
            if (abs(shift - bestShift) > 4 && scores[shift] < best + max(0.15, best * 0.5)) { return Result.Uncertain }
        }
        return Result.Match(bestShift, best)
    }
}
