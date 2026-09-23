package io.github.liuanxin.scrollshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs

/** 单指拖动边框裁剪, 拖动画面平移, 双指缩放. 裁剪坐标始终使用原图像素. */
class CropView(context: Context, private val bitmap: Bitmap, private val imageWidth: Int, private val imageHeight: Int) : View(context) {
    val crop = RectF(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat())
    var onCropChanged: (() -> Unit)? = null
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var fit = 1f
    private var previousX = 0f
    private var previousY = 0f
    private var edges = 0
    private var scaling = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { scaling = true; edges = 0; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val old = zoom
            zoom = (zoom * detector.scaleFactor).coerceIn(fit * 0.25f, maxOf(fit * 30, 2f))
            offsetX = detector.focusX - (detector.focusX - offsetX) * zoom / old
            offsetY = detector.focusY - (detector.focusY - offsetY) * zoom / old
            invalidate()
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fit = minOf((w - 48f) / imageWidth, (h - 48f) / imageHeight).coerceAtLeast(0.001f)
        resetViewport()
    }
    fun reset() {
        crop.set(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat())
        resetViewport()
        onCropChanged?.invoke()
    }
    private fun resetViewport() {
        zoom = ((width - 48f) / imageWidth).coerceAtLeast(fit)
        offsetX = (width - imageWidth * zoom) / 2f
        offsetY = 24f
        invalidate()
    }
    fun cropPixels(): Rect = Rect(crop.left.toInt(), crop.top.toInt(), crop.right.toInt(), crop.bottom.toInt())

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xff17211e.toInt())
        val image = RectF(offsetX, offsetY, offsetX + imageWidth * zoom, offsetY + imageHeight * zoom)
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        canvas.drawBitmap(bitmap, null, image, paint)
        val selected = RectF(offsetX + crop.left * zoom, offsetY + crop.top * zoom, offsetX + crop.right * zoom, offsetY + crop.bottom * zoom)
        paint.color = 0xa0000000.toInt()
        canvas.drawRect(image.left, image.top, image.right, selected.top, paint)
        canvas.drawRect(image.left, selected.bottom, image.right, image.bottom, paint)
        canvas.drawRect(image.left, selected.top, selected.left, selected.bottom, paint)
        canvas.drawRect(selected.right, selected.top, image.right, selected.bottom, paint)
        paint.color = 0xff8ff2c8.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * resources.displayMetrics.density
        canvas.drawRect(selected, paint)
        paint.style = Paint.Style.FILL
        val radius = 5 * resources.displayMetrics.density
        for (point in arrayOf(selected.left to selected.top, selected.right to selected.top, selected.left to selected.bottom, selected.right to selected.bottom,
            selected.centerX() to selected.top, selected.centerX() to selected.bottom, selected.left to selected.centerY(), selected.right to selected.centerY())) {
            canvas.drawCircle(point.first, point.second, radius, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) { return true }
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scaling = false
                previousX = event.x; previousY = event.y
                val left = offsetX + crop.left * zoom
                val right = offsetX + crop.right * zoom
                val top = offsetY + crop.top * zoom
                val bottom = offsetY + crop.bottom * zoom
                val tolerance = 24 * resources.displayMetrics.density
                edges = 0
                if (event.y >= top - tolerance && event.y <= bottom + tolerance) {
                    if (abs(event.x - left) < tolerance && abs(event.x - left) <= abs(event.x - right)) { edges = edges or 1 }
                    else if (abs(event.x - right) < tolerance) { edges = edges or 2 }
                }
                if (event.x >= left - tolerance && event.x <= right + tolerance) {
                    if (abs(event.y - top) < tolerance && abs(event.y - top) <= abs(event.y - bottom)) { edges = edges or 4 }
                    else if (abs(event.y - bottom) < tolerance) { edges = edges or 8 }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaling && event.pointerCount == 1) {
                    val dx = (event.x - previousX) / zoom
                    val dy = (event.y - previousY) / zoom
                    if (edges == 0) { offsetX += event.x - previousX; offsetY += event.y - previousY }
                    else {
                        if (edges and 1 != 0) { crop.left = (crop.left + dx).coerceIn(0f, crop.right - 16) }
                        if (edges and 2 != 0) { crop.right = (crop.right + dx).coerceIn(crop.left + 16, imageWidth.toFloat()) }
                        if (edges and 4 != 0) { crop.top = (crop.top + dy).coerceIn(0f, crop.bottom - 16) }
                        if (edges and 8 != 0) { crop.bottom = (crop.bottom + dy).coerceIn(crop.top + 16, imageHeight.toFloat()) }
                        onCropChanged?.invoke()
                    }
                    invalidate()
                }
                previousX = event.x; previousY = event.y
            }
            MotionEvent.ACTION_UP -> { performClick(); edges = 0; scaling = false }
            MotionEvent.ACTION_CANCEL -> { edges = 0; scaling = false }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
