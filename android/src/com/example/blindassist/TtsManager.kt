package com.example.blindassist

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsManager(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var initialized = false

    override fun onInit(status: Int) {
        initialized = status == TextToSpeech.SUCCESS
        if (!initialized) return
        var langOk = tts.setLanguage(Locale("ru", "RU"))
        if (langOk == TextToSpeech.LANG_MISSING_DATA || langOk == TextToSpeech.LANG_NOT_SUPPORTED) {
            langOk = tts.setLanguage(Locale.forLanguageTag("ru"))
        }
        if (langOk == TextToSpeech.LANG_MISSING_DATA || langOk == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.language = Locale.getDefault()
        }
        tts.setSpeechRate(1.25f)
    }

    fun isReady(): Boolean = initialized

    fun speak(text: String) {
        if (!initialized || text.isBlank()) return
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "u1")
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "u1")
    }

    fun speakEnqueue(text: String) {
        if (!initialized || text.isBlank()) return
        val params = Bundle()
        val id = "q_${System.nanoTime()}"
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        tts.speak(text, TextToSpeech.QUEUE_ADD, params, id)
    }

    fun pause() {
        if (!initialized) return
        tts.stop()
    }

    fun resume() {
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        initialized = false
    }
}
