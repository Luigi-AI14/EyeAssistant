package com.freespace.guide.nav

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class GroundProjection(
    private val focalPixels: Float,
    private val centerX: Float,
    private val centerY: Float,
) {

    data class Ground(val forward: Float, val lateral: Float) {
        val range: Float get() = sqrt(forward * forward + lateral * lateral)
        val bearing: Float get() = atan2(lateral, forward)
    }

    fun project(u: Float, v: Float, up: FloatArray, cameraHeight: Float): Ground? {

        val rx = (u - centerX) / focalPixels
        val ry = -(v - centerY) / focalPixels
        val rz = -1f

        val length = sqrt(rx * rx + ry * ry + rz * rz)
        val dx = rx / length
        val dy = ry / length
        val dz = rz / length

        val descent = -(dx * up[0] + dy * up[1] + dz * up[2])
        if (descent <= MIN_DESCENT) return null

        val distance = cameraHeight / descent
        val px = dx * distance
        val py = dy * distance
        val pz = dz * distance

        val axisDotUp = -up[2]
        var fx = -up[0] * axisDotUp
        var fy = -up[1] * axisDotUp
        var fz = -1f - up[2] * axisDotUp
        val forwardNorm = sqrt(fx * fx + fy * fy + fz * fz)
        if (forwardNorm < 1e-4f) return null
        fx /= forwardNorm; fy /= forwardNorm; fz /= forwardNorm

        val sx = fy * up[2] - fz * up[1]
        val sy = fz * up[0] - fx * up[2]
        val sz = fx * up[1] - fy * up[0]

        val forward = px * fx + py * fy + pz * fz
        val lateral = px * sx + py * sy + pz * sz
        if (forward <= 0f) return null

        return Ground(forward, lateral)
    }

    fun horizonRow(up: FloatArray): Float? {

        if (abs(up[1]) < 1e-4f) return null
        val ry = up[2] / up[1]
        return centerY - ry * focalPixels
    }

    companion object {

        private const val MIN_DESCENT = 0.02f

        const val DEFAULT_HORIZONTAL_FOV = 1.169f

        fun focalPixels(sourceLongSide: Int, horizontalFov: Float, pixelScale: Float): Float {
            val half = (horizontalFov / 2f).coerceIn(0.05f, 1.5f)
            return (sourceLongSide / 2f) / kotlin.math.tan(half) * pixelScale
        }
    }
}
