package com.freespace.guide.feedback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

internal const val SAMPLE_RATE = 44100

internal class OnePole {

    private var state = 0f
    private var coefficient = 1f

    fun setCutoff(hz: Float) {

        val normalised = 2.0 * PI * hz.coerceIn(200f, SAMPLE_RATE / 2.5f) / SAMPLE_RATE
        coefficient = (1.0 - exp(-normalised)).toFloat().coerceIn(0f, 1f)
    }

    fun process(input: Float): Float {
        state += coefficient * (input - state)
        return state
    }

    fun reset() {
        state = 0f
    }
}

internal class Binaural {

    var delayLeft = BASE_DELAY
        private set
    var delayRight = BASE_DELAY
        private set

    private var gainLeft = 0.707f
    private var gainRight = 0.707f
    private val shadowLeft = OnePole()
    private val shadowRight = OnePole()

    init {
        shadowLeft.setCutoff(OPEN_CUTOFF_HZ)
        shadowRight.setCutoff(OPEN_CUTOFF_HZ)
    }

    fun aim(bearing: Float, distance: Float) {
        val azimuth = (bearing * WARP).coerceIn(-MAX_AZIMUTH, MAX_AZIMUTH)

        val itd = HEAD_RADIUS / SPEED_OF_SOUND * (azimuth + sin(azimuth))
        val half = itd * SAMPLE_RATE / 2f

        delayLeft = BASE_DELAY + half
        delayRight = BASE_DELAY - half

        val position = azimuth / MAX_AZIMUTH
        val angle = ((position + 1f) * PI / 4f).toFloat()
        val attenuation = distanceGain(distance)
        gainLeft = cos(angle) * attenuation
        gainRight = sin(angle) * attenuation

        shadowLeft.setCutoff(shadowCutoff(position))
        shadowRight.setCutoff(shadowCutoff(-position))
    }

    fun renderLeft(sample: Float) = shadowLeft.process(sample) * gainLeft

    fun renderRight(sample: Float) = shadowRight.process(sample) * gainRight

    fun reset() {
        shadowLeft.reset()
        shadowRight.reset()
    }

    private fun distanceGain(distance: Float): Float =
        (REFERENCE_DISTANCE / distance.coerceAtLeast(0.3f)).coerceIn(MIN_DISTANCE_GAIN, 1f)

    private fun shadowCutoff(shade: Float): Float =
        OPEN_CUTOFF_HZ - (OPEN_CUTOFF_HZ - SHADOW_CUTOFF_HZ) * shade.coerceIn(0f, 1f)

    private companion object {
        const val HEAD_RADIUS = 0.0875f
        const val SPEED_OF_SOUND = 343f

        const val WARP = 1.2f

        val MAX_AZIMUTH = (PI / 2.0).toFloat()

        const val BASE_DELAY = 16f

        const val OPEN_CUTOFF_HZ = 16000f
        const val SHADOW_CUTOFF_HZ = 2600f

        const val REFERENCE_DISTANCE = 1f
        const val MIN_DISTANCE_GAIN = 0.35f
    }
}
