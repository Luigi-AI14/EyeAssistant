package com.freespace.guide.feedback

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class Speech(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val engine = TextToSpeech(appContext, this)
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()

    @Volatile
    private var ready = false

    @Volatile
    var enabled = true

    private var lastPhrase: String? = null
    private var lastSpokenAt = 0L

    @Volatile
    private var pendingAnnouncement: Pair<String, (() -> Unit)?>? = null
    @Volatile
    private var pendingDescription: Pair<String, (() -> Unit)?>? = null

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Sintesi vocale non disponibile (status: $status)")
            val pendingAnn = pendingAnnouncement
            pendingAnnouncement = null
            pendingAnn?.second?.let { callback -> mainHandler.post { callback() } }

            val pendingDesc = pendingDescription
            pendingDescription = null
            pendingDesc?.second?.let { callback -> mainHandler.post { callback() } }
            return
        }

        val result = engine.setLanguage(Locale.ITALIAN)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Voce italiana assente o non supportata, ripiego sull'inglese")
            engine.setLanguage(Locale.US)
        } else {
            configureBestNaturalVoice()
        }

        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setSpeechRate(1.05f)
        engine.setPitch(1.0f)

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                utteranceId?.let { id ->
                    callbacks.remove(id)?.let { callback ->
                        mainHandler.post { callback() }
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { id ->
                    callbacks.remove(id)?.let { callback ->
                        mainHandler.post { callback() }
                    }
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "[TTS-ERROR] Errore riproduzione utterance '$utteranceId' (code: $errorCode)")
                utteranceId?.let { id ->
                    callbacks.remove(id)?.let { callback ->
                        mainHandler.post { callback() }
                    }
                }
            }
        })

        ready = true

        pendingAnnouncement?.let { (phrase, onDone) ->
            pendingAnnouncement = null
            announce(phrase, onDone)
        }

        pendingDescription?.let { (phrase, onDone) ->
            pendingDescription = null
            speakDescription(phrase, onDone = onDone)
        }
    }

    fun speakDescription(
        phrase: String,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH,
        onDone: (() -> Unit)? = null
    ) {
        if (!ready) {
            Log.i(TAG, "[TTS-WAIT] Motore TTS non ancora pronto, accodo descrizione")
            pendingDescription = Pair(phrase, onDone)
            return
        }

        val utteranceId = "desc_${SystemClock.uptimeMillis()}"
        if (onDone != null) {
            callbacks[utteranceId] = onDone
        }
        lastPhrase = null
        lastSpokenAt = SystemClock.uptimeMillis()
        val res = engine.speak(phrase, queueMode, null, utteranceId)
        if (res != TextToSpeech.SUCCESS) {
            Log.e(TAG, "[TTS-FAIL] Chiamata speakDescription fallita con codice: $res")
            callbacks.remove(utteranceId)
            onDone?.let { mainHandler.post { it() } }
        }
    }

    fun say(phrase: String, urgent: Boolean = false, minimumGapMs: Long = DEFAULT_GAP_MS) {
        if (!ready || !enabled) return
        val now = SystemClock.uptimeMillis()
        if (phrase == lastPhrase && now - lastSpokenAt < REPEAT_GAP_MS) return
        if (!urgent && now - lastSpokenAt < minimumGapMs) return

        lastPhrase = phrase
        lastSpokenAt = now
        val mode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(phrase, mode, null, UTTERANCE_ID)
    }

    fun announce(phrase: String, onDone: (() -> Unit)? = null) {
        if (!ready) {
            pendingAnnouncement = Pair(phrase, onDone)
            return
        }
        val utteranceId = "announce_${SystemClock.uptimeMillis()}"
        if (onDone != null) {
            callbacks[utteranceId] = onDone
        }
        lastPhrase = null
        lastSpokenAt = SystemClock.uptimeMillis()
        val res = engine.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (res != TextToSpeech.SUCCESS) {
            Log.e(TAG, "[TTS-FAIL] Chiamata announce fallita con codice: $res")
            callbacks.remove(utteranceId)
            onDone?.let { mainHandler.post { it() } }
        }
    }

    private fun configureBestNaturalVoice() {
        try {
            val allVoices = engine.voices ?: return
            val italianVoices = allVoices.filter { it.locale.language == Locale.ITALIAN.language }
            if (italianVoices.isEmpty()) return
            val preferredVoice = italianVoices
                .filter { !it.name.contains("fallback", ignoreCase = true) }
                .maxByOrNull { it.quality }
                ?: italianVoices.firstOrNull()

            preferredVoice?.let {
                engine.voice = it
                Log.i(TAG, "[TTS-VOICE] Impostata voce naturale: ${it.name} (qualità: ${it.quality})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[TTS-VOICE-WARN] Impossibile configurare la voce avanzata: ${e.message}")
        }
    }

    fun stop() {
        runCatching { engine.stop() }
    }

    fun shutdown() {
        val remainingCallbacks = callbacks.values.toList()
        callbacks.clear()
        for (cb in remainingCallbacks) {
            mainHandler.post { cb() }
        }
        runCatching { engine.stop() }
        runCatching { engine.shutdown() }
    }

    private companion object {
        const val TAG = "Guida"
        const val UTTERANCE_ID = "guida"
        const val DEFAULT_GAP_MS = 2500L

        const val REPEAT_GAP_MS = 6000L
    }
}
