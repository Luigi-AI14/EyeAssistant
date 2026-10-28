package com.freespace.guide.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.freespace.guide.nav.Alert
import com.freespace.guide.nav.FreeSpaceAnalyzer
import java.nio.ByteBuffer

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    var showMask: Boolean = true

    var showContactLine: Boolean = true

    var showSectorBars: Boolean = false

    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 214, 0)
        strokeWidth = 4f
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 0, 0)
    }

    private val lock = Any()
    private var front: Bitmap? = null
    private var back: Bitmap? = null
    private var pixels = IntArray(0)

    private var contactRows: IntArray? = null
    private var ranges: FloatArray? = null
    private var alert = Alert.CLEAR
    private var maskWidth = 0
    private var maskHeight = 0

    private val sourceRect = Rect()
    private val destinationRect = RectF()

    fun submit(
        mask: ByteBuffer,
        width: Int,
        height: Int,
        threshold: Int,
        contactRows: IntArray,
        ranges: FloatArray,
        alert: Alert,
    ) {
        synchronized(lock) {
            if (maskWidth != width || maskHeight != height) {
                front?.recycle()
                back?.recycle()
                front = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                back = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                pixels = IntArray(width * height)
                maskWidth = width
                maskHeight = height
            }

            val cells = width * height
            for (index in 0 until cells) {
                val probability = mask.get(index).toInt() and 0xFF

                pixels[index] = if (probability >= threshold) {
                    val alpha = 70 + (probability - threshold) * 85 / (256 - threshold)
                    Color.argb(alpha.coerceIn(70, 155), 0, 230, 118)
                } else {
                    Color.TRANSPARENT
                }
            }

            val target = back ?: return
            target.setPixels(pixels, 0, width, 0, 0, width, height)
            back = front
            front = target

            this.contactRows = contactRows.copyOf()
            this.ranges = ranges.copyOf()
            this.alert = alert
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap: Bitmap
        val rows: IntArray?
        val distances: FloatArray?
        val level: Alert
        synchronized(lock) {
            bitmap = front ?: return
            rows = contactRows
            distances = ranges
            level = alert
        }

        sourceRect.set(0, 0, bitmap.width, bitmap.height)
        destinationRect.set(0f, 0f, width.toFloat(), height.toFloat())
        if (showMask) {
            canvas.drawBitmap(bitmap, sourceRect, destinationRect, maskPaint)
        }

        if (showContactLine) {
            rows?.let { drawContactLine(canvas, it, bitmap.height) }
        }
        if (showSectorBars) {
            distances?.let { drawSectorBars(canvas, it, level) }
        }
    }

    private fun drawContactLine(canvas: Canvas, rows: IntArray, maskRows: Int) {
        if (rows.isEmpty() || maskRows == 0) return
        val columnWidth = width.toFloat() / rows.size
        val rowHeight = height.toFloat() / maskRows

        val maxJump = maskRows * 0.12f * rowHeight

        var previousX = 0f
        var previousY = Float.NaN
        for (column in rows.indices) {
            val contact = rows[column]
            val x = (column + 0.5f) * columnWidth
            if (contact < 0) {
                previousY = Float.NaN
                continue
            }
            val y = (contact + 1) * rowHeight
            if (!previousY.isNaN() && kotlin.math.abs(y - previousY) <= maxJump) {
                canvas.drawLine(previousX, previousY, x, y, linePaint)
            } else {

                canvas.drawLine(x - columnWidth / 2f, y, x + columnWidth / 2f, y, linePaint)
            }
            previousX = x
            previousY = y
        }
    }

    private fun drawSectorBars(canvas: Canvas, distances: FloatArray, level: Alert) {
        if (distances.isEmpty()) return
        val slot = width.toFloat() / distances.size
        val maximum = height * 0.18f
        val baseline = height - 24f

        for (sector in distances.indices) {
            val fraction = (distances[sector] / FreeSpaceAnalyzer.MAX_RANGE).coerceIn(0f, 1f)
            val left = sector * slot + slot * 0.15f
            val right = (sector + 1) * slot - slot * 0.15f

            canvas.drawRect(left, baseline - maximum, right, baseline, barBackground)
            barPaint.color = colourFor(fraction, level)
            canvas.drawRect(left, baseline - maximum * fraction, right, baseline, barPaint)
        }
    }

    private fun colourFor(fraction: Float, level: Alert): Int = when {
        level == Alert.NO_GROUND -> Color.argb(200, 130, 130, 130)
        fraction > 0.6f -> Color.argb(210, 0, 230, 118)
        fraction > 0.32f -> Color.argb(210, 255, 196, 0)
        else -> Color.argb(230, 244, 67, 54)
    }

    fun release() {
        synchronized(lock) {
            front?.recycle()
            back?.recycle()
            front = null
            back = null
        }
    }
}
