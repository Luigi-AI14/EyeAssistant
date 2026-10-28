package com.freespace.guide.feedback

import android.content.Context
import com.freespace.guide.R
import com.freespace.guide.nav.Alert
import com.freespace.guide.nav.Cue
import com.freespace.guide.nav.SceneReading

class FeedbackDirector(
    private val context: Context,
    private val sonifier: Sonifier,
    private val speech: Speech,
) {

    var halfSpanRadians = 0.36f

    var cameraHeight = 1.2f

    @Volatile
    var muted = false

    private var buffers: Array<AudioScene>? = null
    private var next = 0

    fun start() = sonifier.start()

    fun stop() = sonifier.stop()

    fun pauseSonifier() {
        muted = true
        if (sonifier.isActive) {
            val scene = sceneBuffer()
            scene.silent = true
            sonifier.submit(scene)
        }
    }

    fun resumeSonifier() {
        muted = false
    }

    fun onCue(cue: Cue, reading: SceneReading) {
        if (sonifier.isActive) {
            val scene = sceneBuffer()
            scene.silent = muted
            scene.alert = cue.alert
            scene.corridorRange = cue.frontRange
            scene.corridorBearing = cue.corridorBearing
            scene.halfSpan = halfSpanRadians
            sonifier.submit(scene)
        }

        if (!muted && cue.changed) speakFor(cue)
    }

    private fun sceneBuffer(): AudioScene {
        val existing = buffers ?: Array(2) { AudioScene() }.also { buffers = it }
        val scene = existing[next]
        next = 1 - next
        return scene
    }

    private fun speakFor(cue: Cue) {
        val phrase = when {
            cue.alert == Alert.NO_GROUND -> context.getString(R.string.say_no_ground)
            cue.alert == Alert.STOP -> context.getString(R.string.say_stop)
            else -> return
        }
        speech.say(phrase, urgent = cue.alert >= Alert.WARNING)
    }
}
