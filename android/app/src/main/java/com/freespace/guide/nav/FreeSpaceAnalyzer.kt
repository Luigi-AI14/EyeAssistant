package com.freespace.guide.nav

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToInt

class SceneReading(sectorCount: Int, columnCount: Int) {

    val ranges = FloatArray(sectorCount) { FreeSpaceAnalyzer.MAX_RANGE }

    val bearings = FloatArray(sectorCount)

    val contactRows = IntArray(columnCount) { -1 }

    var corridorRange = FreeSpaceAnalyzer.MAX_RANGE
        internal set

    var corridorBearing = 0f
        internal set

    var freeRatio = 0f
        internal set

    var groundVisible = true
        internal set

    var horizonRow: Float? = null
        internal set
}

class FreeSpaceAnalyzer(
    private val maskWidth: Int,
    private val maskHeight: Int,
    private val outputStride: Int,
    private val projection: GroundProjection,
    private val focalPixels: Float,
    private val sectorCount: Int = DEFAULT_SECTORS,
) {

    private val reading = SceneReading(sectorCount, maskWidth)
    private val raw = FloatArray(sectorCount)
    private val smoothed = FloatArray(sectorCount) { MAX_RANGE }
    private val filled = BooleanArray(sectorCount)
    private var smoothedCorridor = MAX_RANGE
    private var smoothedBearing = 0f
    private var initialised = false

    private val imageWidth = maskWidth * outputStride

    private val halfSpan: Float = atan((imageWidth / 2f) / focalPixels)

    fun analyse(
        mask: ByteBuffer,
        up: FloatArray,
        cameraHeight: Float,
        freeThreshold: Float,
    ): SceneReading {
        val cutoff = (freeThreshold * 255f).roundToInt().coerceIn(1, 255)

        reading.horizonRow = projection.horizonRow(up)
        reading.groundVisible = isGroundInView(up, cameraHeight)

        java.util.Arrays.fill(raw, MAX_RANGE)
        java.util.Arrays.fill(filled, false)

        var freeCells = 0
        var corridor = MAX_RANGE
        var corridorBearing = 0f
        for (column in 0 until maskWidth) {
            var row = maskHeight - 1
            var blocked = 0
            var contact = -1

            while (row >= 0) {
                val free = (mask.get(row * maskWidth + column).toInt() and 0xFF) >= cutoff
                if (free) {
                    freeCells++
                    blocked = 0
                } else {
                    blocked++

                    if (blocked >= MIN_BLOCKED_RUN) {
                        contact = row + MIN_BLOCKED_RUN - 1
                        break
                    }
                }
                row--
            }
            reading.contactRows[column] = contact

            val u = (column + 0.5f) * outputStride
            val ground = if (contact >= 0) {
                val v = (contact + 1).toFloat() * outputStride
                projection.project(u, v, up, cameraHeight)
            } else {
                null
            }

            val range = ground?.range?.coerceAtMost(MAX_RANGE) ?: MAX_RANGE

            val bearing = ground?.bearing ?: atan((u - imageWidth / 2f) / focalPixels)

            if (ground != null && abs(ground.lateral) <= BODY_HALF_WIDTH &&
                ground.forward < corridor
            ) {
                corridor = ground.forward
                corridorBearing = ground.bearing
            }

            val sector = sectorOf(bearing)
            if (range < raw[sector]) raw[sector] = range
            filled[sector] = true
        }

        reading.corridorRange = smoothCorridor(corridor.coerceIn(0f, MAX_RANGE))

        reading.corridorBearing =
            smoothBearing(if (corridor >= MAX_RANGE) 0f else corridorBearing)
        reading.freeRatio = freeCells.toFloat() / (maskWidth * maskHeight)
        fillGaps()
        smooth()

        for (sector in 0 until sectorCount) {
            reading.bearings[sector] = sectorBearing(sector)
            reading.ranges[sector] = smoothed[sector]
        }
        return reading
    }

    private fun isGroundInView(up: FloatArray, cameraHeight: Float): Boolean {
        val bottom = projection.project(
            imageWidth / 2f, (maskHeight * outputStride).toFloat(), up, cameraHeight
        )
        return bottom != null && bottom.range <= NEAR_FIELD_RANGE
    }

    private fun sectorOf(bearing: Float): Int {
        val normalised = (bearing + halfSpan) / (2f * halfSpan)
        return (normalised * sectorCount).toInt().coerceIn(0, sectorCount - 1)
    }

    private fun sectorBearing(sector: Int): Float =
        -halfSpan + (sector + 0.5f) * (2f * halfSpan / sectorCount)

    private fun fillGaps() {
        for (sector in 0 until sectorCount) {
            if (filled[sector]) continue
            var left = sector - 1
            while (left >= 0 && !filled[left]) left--
            var right = sector + 1
            while (right < sectorCount && !filled[right]) right++
            val fromLeft = if (left >= 0) raw[left] else MAX_RANGE
            val fromRight = if (right < sectorCount) raw[right] else MAX_RANGE
            raw[sector] = minOf(fromLeft, fromRight)
        }
    }

    private fun smoothCorridor(value: Float): Float {
        if (!initialised) return value.also { smoothedCorridor = it }
        val alpha = if (value < smoothedCorridor) APPROACHING_ALPHA else RECEDING_ALPHA
        smoothedCorridor += alpha * (value - smoothedCorridor)
        return smoothedCorridor
    }

    private fun smoothBearing(value: Float): Float {
        if (!initialised) return value.also { smoothedBearing = it }
        smoothedBearing += BEARING_ALPHA * (value - smoothedBearing)
        return smoothedBearing
    }

    private fun smooth() {
        if (!initialised) {
            raw.copyInto(smoothed)
            initialised = true
            return
        }
        for (sector in 0 until sectorCount) {
            val alpha = if (raw[sector] < smoothed[sector]) APPROACHING_ALPHA else RECEDING_ALPHA
            smoothed[sector] += alpha * (raw[sector] - smoothed[sector])
        }
    }

    companion object {

        const val MAX_RANGE = 5f

        const val DEFAULT_SECTORS = 9

        const val NEAR_FIELD_RANGE = 3f

        const val BODY_HALF_WIDTH = 0.24f

        private const val MIN_BLOCKED_RUN = 2
        private const val BEARING_ALPHA = 0.35f
        private const val APPROACHING_ALPHA = 0.5f
        private const val RECEDING_ALPHA = 0.15f
    }
}
