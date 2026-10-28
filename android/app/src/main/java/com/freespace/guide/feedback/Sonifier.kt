package com.freespace.guide.feedback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.freespace.guide.nav.Alert
import com.freespace.guide.nav.FreeSpaceAnalyzer
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

class Sonifier(val spatial: Boolean) {

    @Volatile private var published: AudioScene? = null
    @Volatile private var running = false

    val isRunning: Boolean get() = running
    val isActive: Boolean get() = spatial && running

    private var track: AudioTrack? = null
    private var worker: Thread? = null

    private val woodblock = buildWoodblock()

    private val stopPulse = buildStopPulse()

    private val groundSignal = buildGroundSignal()

    private val alarmPlace = Binaural()

    private var clickPhase = ALARM_IDLE
    private var stopPhase = Int.MAX_VALUE / 2
    private var groundPhase = Int.MAX_VALUE / 2

    fun start() {
        if (!spatial || running) return
        val minimum = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferBytes = maxOf(minimum, BLOCK_FRAMES * 2 * BYTES_PER_SAMPLE * 4)

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }

        running = true
        worker = thread(name = "sonifier", priority = Thread.MAX_PRIORITY) { render() }
    }

    fun stop() {
        if (!running) return
        running = false
        worker?.join(500)
        worker = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        published = null
        clickPhase = ALARM_IDLE
        stopPhase = Int.MAX_VALUE / 2
        groundPhase = Int.MAX_VALUE / 2
        alarmPlace.reset()
    }

    fun submit(scene: AudioScene) {
        if (!spatial || !running) return
        published = scene
    }

    private fun render() {
        val block = ShortArray(BLOCK_FRAMES * 2)
        val stopInterval = (STOP_PERIOD_MS * SAMPLE_RATE / 1000f).toInt().coerceAtLeast(BLOCK_FRAMES)

        while (running) {
            val scene = published
            val silent = scene == null || scene.silent
            val alert = scene?.alert ?: Alert.CLEAR
            val corridorRange = scene?.corridorRange ?: FreeSpaceAnalyzer.MAX_RANGE
            val corridorBearing = scene?.corridorBearing ?: 0f

            val bearingFactor = cos(corridorBearing.coerceIn(-PI.toFloat() / 2f, PI.toFloat() / 2f))
            val lateralWeight = (bearingFactor * bearingFactor).coerceIn(MIN_LATERAL_WEIGHT, 1.0f)

            val effectiveDetectionRange = STOP_RANGE + (DETECTION_RANGE - STOP_RANGE) * bearingFactor
            val proximity = ((effectiveDetectionRange - corridorRange) / (effectiveDetectionRange - STOP_RANGE))
                .coerceIn(0f, 1f)
            val urgency = proximity * proximity

            val alarming = !silent && alert != Alert.NO_GROUND && corridorRange < effectiveDetectionRange
            val steady = alarming && alert == Alert.STOP
            val clicking = alarming && !steady

            val periodMs = FAR_PERIOD_MS + (NEAR_PERIOD_MS - FAR_PERIOD_MS) * urgency
            val interval = (periodMs * SAMPLE_RATE / 1000f).toInt().coerceAtLeast(BLOCK_FRAMES)

            alarmPlace.aim(corridorBearing, corridorRange)

            val lost = !silent && alert == Alert.NO_GROUND

            for (frame in 0 until BLOCK_FRAMES) {
                var left = 0f
                var right = 0f

                if (clicking && clickPhase >= interval) {
                    clickPhase = 0
                }
                var alarmLeft = tap(woodblock, clickPhase - alarmPlace.delayLeft) * lateralWeight
                var alarmRight = tap(woodblock, clickPhase - alarmPlace.delayRight) * lateralWeight
                if (clicking || clickPhase < ALARM_IDLE) clickPhase++

                if (steady) {
                    if (stopPhase >= stopInterval) {
                        stopPhase = 0
                    }
                    alarmLeft += tap(stopPulse, stopPhase - alarmPlace.delayLeft) * lateralWeight
                    alarmRight += tap(stopPulse, stopPhase - alarmPlace.delayRight) * lateralWeight
                    stopPhase++
                } else {
                    stopPhase = Int.MAX_VALUE / 2
                }

                left += alarmPlace.renderLeft(alarmLeft)
                right += alarmPlace.renderRight(alarmRight)

                if (lost) {
                    if (groundPhase >= GROUND_PERIOD) groundPhase = 0
                    if (groundPhase < groundSignal.size) {
                        val note = groundSignal[groundPhase] * GROUND_LEVEL
                        left += note
                        right += note
                    }
                    groundPhase++
                } else {
                    groundPhase = Int.MAX_VALUE / 2
                }

                block[frame * 2] = toPcm(left)
                block[frame * 2 + 1] = toPcm(right)
            }

            val written = track?.write(block, 0, block.size) ?: -1
            if (written < 0) break
        }
    }

    private fun tap(buffer: FloatArray, position: Float): Float {
        if (position < 0f || buffer.isEmpty()) return 0f
        val index = position.toInt()
        if (index >= buffer.size - 1) return 0f
        val fraction = position - index
        return buffer[index] + fraction * (buffer[index + 1] - buffer[index])
    }

    private fun buildWoodblock(): FloatArray {
        val samples = (WOODBLOCK_MS * SAMPLE_RATE / 1000f).toInt()
        val attack = (WOODBLOCK_ATTACK_MS * SAMPLE_RATE / 1000f).toInt().coerceAtLeast(1)
        val decaySamples = samples / 3.2f
        val buffer = FloatArray(samples)

        for (index in 0 until samples) {
            val t = index.toFloat() / SAMPLE_RATE
            val attackEnvelope = minOf(1f, index.toFloat() / attack)
            val decayEnvelope = exp(-index / decaySamples) * (1f - index.toFloat() / samples)
            val envelope = attackEnvelope * decayEnvelope

            val fundamental = sin(2.0 * PI * WOODBLOCK_FREQ_HZ * t).toFloat()
            val overtone = 0.30f * sin(2.0 * PI * (WOODBLOCK_FREQ_HZ * 2.0) * t).toFloat()

            buffer[index] = (fundamental + overtone) * envelope * AMPLITUDE
        }
        return buffer
    }

    private fun buildStopPulse(): FloatArray {
        val samples = (STOP_PULSE_MS * SAMPLE_RATE / 1000f).toInt()
        val attack = (STOP_ATTACK_MS * SAMPLE_RATE / 1000f).toInt().coerceAtLeast(1)
        val release = (STOP_RELEASE_MS * SAMPLE_RATE / 1000f).toInt().coerceAtLeast(1)
        val buffer = FloatArray(samples)

        for (index in 0 until samples) {
            val t = index.toFloat() / SAMPLE_RATE
            val attackEnv = if (index < attack) 0.5f * (1f - cos(PI * index / attack).toFloat()) else 1f
            val releaseEnv = if (index >= samples - release) {
                0.5f * (1f + cos(PI * (index - (samples - release)) / release).toFloat())
            } else 1f
            val envelope = attackEnv * releaseEnv

            val tone = sin(2.0 * PI * STOP_TONE_HZ * t).toFloat()
            buffer[index] = tone * envelope * STOP_AMPLITUDE
        }
        return buffer
    }

    private fun buildGroundSignal(): FloatArray {
        val note = (GROUND_NOTE_MS * SAMPLE_RATE / 1000f).toInt()
        val gap = (GROUND_GAP_MS * SAMPLE_RATE / 1000f).toInt()
        val buffer = FloatArray(note * 2 + gap)
        writeNote(buffer, 0, note, GROUND_HIGH_HZ)
        writeNote(buffer, note + gap, note, GROUND_LOW_HZ)
        return buffer
    }

    private fun writeNote(buffer: FloatArray, offset: Int, length: Int, frequency: Float) {
        val attack = (GROUND_ATTACK_MS * SAMPLE_RATE / 1000f).toInt().coerceAtLeast(1)
        for (index in 0 until length) {
            val progress = index.toFloat() / length
            val envelope = minOf(1f, index.toFloat() / attack) *
                (0.5f * (1f + cos(PI * progress).toFloat()))
            buffer[offset + index] =
                sin(2.0 * PI * frequency * index / SAMPLE_RATE).toFloat() * envelope
        }
    }

    private fun toPcm(sample: Float): Short {
        val shaped = if (sample > -SOFT_KNEE && sample < SOFT_KNEE) {
            sample
        } else {
            val sign = if (sample < 0f) -1f else 1f
            val excess = abs(sample) - SOFT_KNEE
            sign * (SOFT_KNEE + (1f - SOFT_KNEE) * tanh(excess / (1f - SOFT_KNEE)))
        }
        return (shaped * Short.MAX_VALUE).toInt().toShort()
    }

    private companion object {
        const val TWO_PI = 2.0 * PI

        const val BYTES_PER_SAMPLE = 2
        const val BLOCK_FRAMES = 512

        const val SOFT_KNEE = 0.7f

        const val WOODBLOCK_MS = 28f
        const val WOODBLOCK_ATTACK_MS = 3.5f
        const val WOODBLOCK_FREQ_HZ = 580f
        const val AMPLITUDE = 0.22f

        const val MIN_LATERAL_WEIGHT = 0.35f

        const val DETECTION_RANGE = 2.5f
        const val STOP_RANGE = 0.8f

        const val FAR_PERIOD_MS = 850f
        const val NEAR_PERIOD_MS = 140f

        const val STOP_PULSE_MS = 110f
        const val STOP_PERIOD_MS = 330f
        const val STOP_TONE_HZ = 400f
        const val STOP_AMPLITUDE = 0.16f
        const val STOP_ATTACK_MS = 15f
        const val STOP_RELEASE_MS = 25f

        const val ALARM_IDLE = 4096

        const val GROUND_NOTE_MS = 140f
        const val GROUND_GAP_MS = 60f
        const val GROUND_ATTACK_MS = 8f
        const val GROUND_HIGH_HZ = 300f
        const val GROUND_LOW_HZ = 220f
        const val GROUND_LEVEL = 0.5f
        val GROUND_PERIOD = 1500 * SAMPLE_RATE / 1000
    }
}
