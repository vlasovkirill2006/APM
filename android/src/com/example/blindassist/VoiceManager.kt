package com.example.blindassist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.SystemClock
import android.util.Log

class VoiceManager(
    context: Context,
    private val onCaptureRequested: () -> Unit,
    private val onDistanceSpeechVoiceToggle: (Boolean) -> Unit,
    private val onVoiceHelpRequested: () -> Unit,
    private val onPressCommandResult: ((String?) -> Unit)? = null,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var running = false
    @Volatile private var pressListenMode = false
    @Volatile private var lastCapturePhraseAtMs = 0L
    @Volatile private var lastToggleAtMs = 0L
    @Volatile private var lastVoiceHelpAtMs = 0L

    private fun createRecognizer(): SpeechRecognizer {
        val r = SpeechRecognizer.createSpeechRecognizer(appContext)
        r.setRecognitionListener(listener)
        return r
    }

    fun start() {
        if (running) return
        running = true

        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            Log.w(TAG, "SpeechRecognizer not available")
            return
        }

        recognizer = createRecognizer()

        startListening()
    }

    fun stop() {
        running = false
        pressListenMode = false
        recognizer?.stopListening()
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    fun startPressListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) return
        pressListenMode = true
        recognizer?.stopListening()
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = createRecognizer()
        startListening()
    }

    fun cancelPressListening() {
        pressListenMode = false
        if (running) {
            recognizer?.stopListening()
            recognizer?.cancel()
            startListening()
        }
    }

    private fun startListening() {
        if (!running && !pressListenMode) return
        val r = recognizer ?: return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700L)
        }

        try {
            r.startListening(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "startListening failed", t)
        }
    }

    private fun handleFinalResults(bundle: Bundle?) {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val confidences = bundle.getFloatArray(RecognizerIntent.EXTRA_CONFIDENCE_SCORES)

        if (pressListenMode) {
            pressListenMode = false
            val text = list.firstOrNull()?.lowercase()?.trim() ?: ""
            Log.i(TAG, "press command: \"$text\"")
            mainHandler.post { onPressCommandResult?.invoke(text) }
            return
        }

        for (i in list.indices) {
            val text = list[i].lowercase().trim()
            if (text.length < MIN_COMMAND_CHARS) {
                Log.d(TAG, "ignore short[$i]: \"$text\"")
                continue
            }
            val conf = confidences?.getOrNull(i)
            if (conf != null && conf < MIN_CONFIDENCE) {
                Log.d(TAG, "ignore low conf=$conf[$i] text=\"$text\"")
                continue
            }
            if (tryHandleDistanceSpeechToggle(text)) return
            if (tryHandleVoiceHelp(text)) return
            if (matchesCaptureCommand(text)) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastCapturePhraseAtMs < CAPTURE_DEBOUNCE_MS) return
                lastCapturePhraseAtMs = now
                Log.i(TAG, "capture command[$i]: \"$text\"")
                onCaptureRequested()
                return
            }
        }
    }

    private fun tryHandleDistanceSpeechToggle(text: String): Boolean {
        val wantOff = matchesToggleOff(text)
        val wantOn = matchesToggleOn(text)
        if (wantOff && wantOn) {
            return false
        }
        val enable = when {
            wantOff -> false
            wantOn -> true
            else -> return false
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastToggleAtMs < TOGGLE_DEBOUNCE_MS) {
            Log.d(TAG, "ignore toggle debounce")
            return true
        }
        lastToggleAtMs = now
        Log.i(TAG, "distance speech toggle (voice): $enable")
        mainHandler.post { onDistanceSpeechVoiceToggle(enable) }
        return true
    }

    private fun tryHandleVoiceHelp(text: String): Boolean {
        if (!matchesVoiceHelp(text)) {
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastVoiceHelpAtMs < VOICE_HELP_DEBOUNCE_MS) {
            return true
        }
        lastVoiceHelpAtMs = now
        Log.i(TAG, "voice help: \"$text\"")
        mainHandler.post { onVoiceHelpRequested() }
        return true
    }

    private fun matchesVoiceHelp(text: String): Boolean {
        return text.contains("команд") ||
            text.contains("помощь")
    }

    private fun matchesToggleOn(text: String): Boolean {
        return text.contains("включи дистанц") ||
            text.contains("озвучивай")
    }

    private fun matchesToggleOff(text: String): Boolean {
        return text.contains("выключи дистанц") ||
            text.contains("замолчи")
    }

    private fun matchesCaptureCommand(text: String): Boolean {
        return text.contains("видишь") ||
            text.contains("опиши") ||
            text.contains("вокруг")
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            if (pressListenMode) {
                pressListenMode = false
                mainHandler.post { onPressCommandResult?.invoke(null) }
            }
            if (!running) return
            recognizer?.destroy()
            recognizer = createRecognizer()
            mainHandler.postDelayed({ startListening() }, 300L)
        }

        override fun onResults(results: Bundle?) {
            val wasPressMode = pressListenMode
            handleFinalResults(results)
            if (!running) return
            if (wasPressMode) {
                recognizer?.destroy()
                recognizer = createRecognizer()
            }
            startListening()
        }

        override fun onPartialResults(partialResults: Bundle?) {
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private companion object {
        private const val TAG = "VoiceManager"
        private const val CAPTURE_DEBOUNCE_MS = 4000L
        private const val TOGGLE_DEBOUNCE_MS = 2500L
        private const val VOICE_HELP_DEBOUNCE_MS = 3500L
        private const val MIN_CONFIDENCE = 0.30f
        private const val MIN_COMMAND_CHARS = 5
    }
}
