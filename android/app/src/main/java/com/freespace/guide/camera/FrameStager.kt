package com.freespace.guide.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FrameStager(private val targetWidth: Int, private val targetHeight: Int) {

    private val bitmap: Bitmap =
        Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val destination = RectF()

    val buffer: ByteBuffer = ByteBuffer
        .allocateDirect(targetWidth * targetHeight * BYTES_PER_PIXEL)
        .order(ByteOrder.nativeOrder())

    private var camera: Bitmap? = null
    private var padding: ByteBuffer? = null

    var pixelScale = 1f
        private set
    var sourceLongSide = 0
        private set

    fun stage(image: ImageProxy): ByteBuffer? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride

        if (width <= 0 || height <= 0 || rowStride < width * BYTES_PER_PIXEL) {
            Log.e(TAG, "Geometria inattesa ${width}x$height stride=$rowStride: " +
                "ImageAnalysis e' impostata su OUTPUT_IMAGE_FORMAT_RGBA_8888?")
            return null
        }

        val wrapper = wrapPlane(plane.buffer, rowStride, height) ?: return null
        blit(wrapper, width, height, image.imageInfo.rotationDegrees)

        buffer.rewind()
        bitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()
        return buffer
    }

    private fun wrapPlane(plane: ByteBuffer, rowStride: Int, height: Int): Bitmap? {

        val stridedWidth = rowStride / BYTES_PER_PIXEL
        var wrapper = camera
        if (wrapper == null || wrapper.width != stridedWidth || wrapper.height != height) {
            wrapper?.recycle()
            wrapper = Bitmap.createBitmap(stridedWidth, height, Bitmap.Config.ARGB_8888)
            camera = wrapper
            padding = null
        }

        plane.rewind()
        val needed = wrapper.byteCount
        if (plane.remaining() >= needed) {
            wrapper.copyPixelsFromBuffer(plane)
        } else {

            val staging = padding ?: ByteBuffer.allocateDirect(needed)
                .order(ByteOrder.nativeOrder()).also { padding = it }
            staging.clear()
            staging.put(plane)
            staging.position(0)
            staging.limit(needed)
            wrapper.copyPixelsFromBuffer(staging)
        }
        return wrapper
    }

    private fun blit(wrapper: Bitmap, width: Int, height: Int, rotationDegrees: Int) {

        val quarterTurn = rotationDegrees % 180 != 0
        val visibleWidth = if (quarterTurn) height else width
        val visibleHeight = if (quarterTurn) width else height

        val targetRatio = targetWidth.toFloat() / targetHeight

        val scale = if (visibleWidth.toFloat() / visibleHeight > targetRatio) {
            targetHeight.toFloat() / visibleHeight
        } else {
            targetWidth.toFloat() / visibleWidth
        }
        pixelScale = scale
        sourceLongSide = maxOf(width, height)

        canvas.save()
        canvas.translate(targetWidth / 2f, targetHeight / 2f)
        canvas.rotate(rotationDegrees.toFloat())
        canvas.scale(scale, scale)
        canvas.translate(-width / 2f, -height / 2f)

        source.set(0, 0, width, height)
        destination.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(wrapper, source, destination, paint)
        canvas.restore()
    }

    fun close() {
        camera?.takeIf { !it.isRecycled }?.recycle()
        camera = null
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private companion object {
        const val TAG = "Guida"
        const val BYTES_PER_PIXEL = 4
    }
}
