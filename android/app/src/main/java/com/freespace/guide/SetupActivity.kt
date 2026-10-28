package com.freespace.guide

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.freespace.guide.databinding.ActivitySetupBinding
import com.freespace.guide.feedback.Speech
import com.freespace.guide.feedback.headsetConnected

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var speech: Speech
    private var voiceEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        speech = Speech(this)
        voiceEnabled = preferences().getBoolean(KEY_VOICE, true)
        showVoiceState()

        binding.btnYes.setOnClickListener {
            val isHeadset = headsetConnected(this)
            launch(spatial = isHeadset)
        }
        binding.btnNo.setOnClickListener { launch(spatial = false) }
        binding.btnVoice.setOnClickListener { toggleVoice() }

        askAloud()
    }

    private fun askAloud() {
        val question = getString(R.string.say_setup_question)
        val detected =
            if (headsetConnected(this)) " " + getString(R.string.say_setup_detected) else ""
        speech.announce(question + detected)
    }

    private fun toggleVoice() {
        voiceEnabled = !voiceEnabled
        preferences().edit().putBoolean(KEY_VOICE, voiceEnabled).apply()
        showVoiceState()
        speech.announce(
            getString(if (voiceEnabled) R.string.say_voice_on else R.string.say_voice_off)
        )
    }

    private fun showVoiceState() {
        val label =
            getString(if (voiceEnabled) R.string.setup_voice_on else R.string.setup_voice_off)
        binding.btnVoice.text = label
        binding.btnVoice.contentDescription = getString(R.string.cd_setup_voice, label)
        val bgColor = ContextCompat.getColor(
            this,
            if (voiceEnabled) R.color.setup_voice_active else R.color.setup_voice
        )
        val textColor = ContextCompat.getColor(
            this,
            if (voiceEnabled) android.R.color.black else android.R.color.white
        )
        binding.btnVoice.setBackgroundColor(bgColor)
        binding.btnVoice.setTextColor(textColor)
    }

    private fun launch(spatial: Boolean) {
        startActivity(
            Intent(this, MainActivity::class.java).putExtra(EXTRA_SPATIAL, spatial)
        )

        finish()
    }

    private fun preferences() = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun onDestroy() {
        super.onDestroy()
        speech.shutdown()
    }

    companion object {
        const val EXTRA_SPATIAL = "com.floornet.audio.SPATIAL"
        const val PREFERENCES = "guida"
        const val KEY_VOICE = "voce"
    }
}
