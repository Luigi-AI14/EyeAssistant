package com.freespace.guide

import android.util.Log
import androidx.camera.core.ImageProxy
import com.freespace.guide.camera.FrameStager
import com.freespace.guide.ml.FloorSegmenter
import com.freespace.guide.nav.Cue
import com.freespace.guide.nav.FreeSpaceAnalyzer
import com.freespace.guide.nav.GroundProjection
import com.freespace.guide.nav.Guidance
import com.freespace.guide.nav.SceneReading
import com.freespace.guide.sensors.TiltTracker
import java.nio.ByteBuffer
import kotlin.math.atan

class NavigationPipeline(
    private val segmenter: FloorSegmenter,
    private val tilt: TiltTracker,
) {

    class Frame(val mask: ByteBuffer, val reading: SceneReading, val cue: Cue)

    private val stager = FrameStager(segmenter.inputWidth, segmenter.inputHeight)
    private val guidance = Guidance()
    private var analyzer: FreeSpaceAnalyzer? = null
    private var configuredScale = 0f

    val outputStride: Int = segmenter.inputHeight / segmenter.maskHeight

    var horizontalFov: Float = GroundProjection.DEFAULT_HORIZONTAL_FOV

    @Volatile
    var cameraHeight: Float = DEFAULT_CAMERA_HEIGHT

    @Volatile
    var freeThreshold: Float = DEFAULT_FREE_THRESHOLD

    var halfSpanRadians: Float = 0.36f
        private set

    @Volatile
    var isPaused: Boolean = false

    val thresholdByte: Int get() = (freeThreshold * 255f).toInt().coerceIn(1, 255)
    val maskWidth: Int get() = segmenter.maskWidth
    val maskHeight: Int get() = segmenter.maskHeight
    val inferenceMs: Long get() = segmenter.lastInferenceMs

    fun process(image: ImageProxy): Frame? {
        if (isPaused) return null
        val startedAt = System.nanoTime()
        val staged = stager.stage(image) ?: return null
        val stagedAt = System.nanoTime()

        val analyzer = ensureAnalyzer() ?: return null
        val mask = segmenter.run(staged) ?: return null
        val inferredAt = System.nanoTime()

        val reading = analyzer.analyse(mask, tilt.up, cameraHeight, freeThreshold)
        val cue = guidance.evaluate(reading)
        record(startedAt, stagedAt, inferredAt, System.nanoTime())
        return Frame(mask, reading, cue)
    }

    private fun record(started: Long, staged: Long, inferred: Long, finished: Long) {
        stageNs += staged - started
        inferNs += inferred - staged
        analyseNs += finished - inferred
        if (lastFrameEndNs != 0L) gapNs += started - lastFrameEndNs
        lastFrameEndNs = finished

        if (++framesLogged < LOG_EVERY) return
        val n = framesLogged.toDouble()
        Log.i(TAG, "media su $framesLogged frame (${segmenter.threads} thread) -- "
            + "preparazione %.1f ms, inferenza %.1f ms, "
            .format(stageNs / n / 1e6, inferNs / n / 1e6) +
            "analisi %.1f ms, attesa fra un frame e l'altro %.1f ms"
                .format(analyseNs / n / 1e6, gapNs / n / 1e6))
        framesLogged = 0
        stageNs = 0; inferNs = 0; analyseNs = 0; gapNs = 0
    }

    private var framesLogged = 0
    private var stageNs = 0L
    private var inferNs = 0L
    private var analyseNs = 0L
    private var gapNs = 0L
    private var lastFrameEndNs = 0L

    private fun ensureAnalyzer(): FreeSpaceAnalyzer? {
        val current = analyzer
        if (current != null && stager.pixelScale == configuredScale) return current
        if (stager.sourceLongSide == 0) return null

        val focal = GroundProjection.focalPixels(
            stager.sourceLongSide, horizontalFov, stager.pixelScale
        )
        val projection = GroundProjection(
            focalPixels = focal,
            centerX = segmenter.inputWidth / 2f,
            centerY = segmenter.inputHeight / 2f,
        )
        halfSpanRadians = atan((segmenter.inputWidth / 2f) / focal)

        return FreeSpaceAnalyzer(
            maskWidth = segmenter.maskWidth,
            maskHeight = segmenter.maskHeight,
            outputStride = outputStride,
            projection = projection,
            focalPixels = focal,
        ).also {
            analyzer = it
            configuredScale = stager.pixelScale
        }
    }

    fun close() {
        stager.close()
        segmenter.close()
    }

    companion object {
        private const val TAG = "Guida"
        private const val LOG_EVERY = 45

        const val DEFAULT_CAMERA_HEIGHT = 1.3f
        const val DEFAULT_FREE_THRESHOLD = 0.55f

        val CAMERA_HEIGHTS = floatArrayOf(0.9f, 1.2f, 1.5f)
    }
}
