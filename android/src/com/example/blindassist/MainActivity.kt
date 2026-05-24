package com.example.blindassist

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.qtproject.qt.android.bindings.QtActivity
import kotlin.math.abs

class MainActivity : QtActivity() {
    private val ar = ARCoreManager()
    private lateinit var vib: VibrationManager
    private lateinit var voice: VoiceManager
    private lateinit var tts: TtsManager
    private val server by lazy { ServerAnalyzer(this) }

    private val distanceTtsHandler = Handler(Looper.getMainLooper())
    private val distanceSpeechEnqueueRunnable = Runnable {
        val p = pendingDistancePhrase ?: return@Runnable
        pendingDistancePhrase = null
        if (!distanceSpeechVoiceEnabled) return@Runnable
        if (!tts.isReady()) return@Runnable
        tts.speakEnqueue(p)
    }
    private var pendingDistancePhrase: String? = null
    private val openingHintRunnable = Runnable { maybeSpeakOpeningHint() }

    @Volatile private var distanceSpeechVoiceEnabled: Boolean = true
    private var openingHintsSpoken: Boolean = false
    private var openingHintRetryCount: Int = 0

    // --- Свайп-меню при зажиме ---
    @Volatile private var pressActive: Boolean = false
    private val longPressRunnable = Runnable { onLongPressActivated() }
    private var pressStartY: Float = 0f
    private var lastSwipeY: Float = 0f
    private var currentMenuIndex: Int = 0
    private var lastAnnouncedMenuIndex: Int = -1

    private val menuItems: List<Pair<String, () -> Unit>> by lazy {
        listOf(
            "Отмена" to { onMenuCancel() },
            "Анализ сцены" to { doAnalyze() },
            "Читать текст" to { doOcr() },
            "Включить дистанцию" to { applyDistanceSpeechVoiceEnabled(true, speakConfirm = true); bindDistanceSpeech() },
            "Выключить дистанцию" to { applyDistanceSpeechVoiceEnabled(false, speakConfirm = true); bindDistanceSpeech() },
        )
    }

    private val swipeStepPx: Float by lazy { resources.displayMetrics.density * 60f }

    // --- Тройной тап ---
    private var tapCount: Int = 0
    private var lastTapAtMs: Long = 0L
    private val resetTapCountRunnable = Runnable { tapCount = 0 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            window?.decorView?.isSoundEffectsEnabled = false
            window?.decorView?.setSoundEffectsEnabled(false)
            val content = findViewById<View?>(android.R.id.content)
            content?.isSoundEffectsEnabled = false
            content?.setSoundEffectsEnabled(false)
        } catch (_: Throwable) {}

        ar.attach(this)
        vib = VibrationManager(this)
        tts = TtsManager(this)
        voice = VoiceManager(
            this,
            onCaptureRequested = { runServerAnalysis(ServerAnalyzer.Mode.CAPTION) },
            onDistanceSpeechVoiceToggle = { enable ->
                applyDistanceSpeechVoiceEnabled(enable, speakConfirm = true)
            },
            onVoiceHelpRequested = { speakVoiceCommandsHelp() },
            onPressCommandResult = null,
        )
        distanceSpeechVoiceEnabled = true
        bindDistanceSpeech()
        ensureCameraPermission()
        ensureAudioPermission()
    }

    private fun applyDistanceSpeechVoiceEnabled(enabled: Boolean, speakConfirm: Boolean) {
        distanceSpeechVoiceEnabled = enabled
        try {
            ar.syncDistanceSpeechModelEnabled(enabled)
        } catch (_: Throwable) {}
        if (speakConfirm && tts.isReady()) {
            tts.speak(if (enabled) "Дистанция включена" else "Дистанция выключена")
        }
    }

    private fun bindDistanceSpeech() {
        ar.setDistanceSpeechListener { meters ->
            if (!distanceSpeechVoiceEnabled) return@setDistanceSpeechListener
            if (!tts.isReady()) return@setDistanceSpeechListener
            val phrase = distanceShortPhraseRu(meters)
            if (phrase.isNotEmpty()) scheduleDistanceSpeechEnqueue(phrase)
        }
    }

    private fun scheduleDistanceSpeechEnqueue(phrase: String) {
        pendingDistancePhrase = phrase
        distanceTtsHandler.removeCallbacks(distanceSpeechEnqueueRunnable)
        distanceTtsHandler.postDelayed(distanceSpeechEnqueueRunnable, DISTANCE_TTS_DEBOUNCE_MS)
    }

    private fun cancelPendingDistanceSpeech() {
        distanceTtsHandler.removeCallbacks(distanceSpeechEnqueueRunnable)
        pendingDistancePhrase = null
    }

    private fun maybeSpeakOpeningHint() {
        if (openingHintsSpoken) return
        if (!tts.isReady()) {
            if (openingHintRetryCount++ < 12) {
                distanceTtsHandler.removeCallbacks(openingHintRunnable)
                distanceTtsHandler.postDelayed(openingHintRunnable, 400L)
            } else {
                openingHintsSpoken = true
                openingHintRetryCount = 0
            }
            return
        }
        openingHintsSpoken = true
        openingHintRetryCount = 0
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressStartY = event.y
                distanceTtsHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                val now = System.currentTimeMillis()
                if (now - lastTapAtMs < TRIPLE_TAP_WINDOW_MS) {
                    tapCount++
                } else {
                    tapCount = 1
                }
                lastTapAtMs = now
                distanceTtsHandler.removeCallbacks(resetTapCountRunnable)
                distanceTtsHandler.postDelayed(resetTapCountRunnable, TRIPLE_TAP_WINDOW_MS)
                if (tapCount >= 3) {
                    tapCount = 0
                    distanceTtsHandler.removeCallbacks(longPressRunnable)
                    tts.pause()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressActive) onSwipeMove(event.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                distanceTtsHandler.removeCallbacks(longPressRunnable)
                if (pressActive) onScreenReleased(event.actionMasked == MotionEvent.ACTION_UP)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun onLongPressActivated() {
        pressActive = true
        currentMenuIndex = 0
        lastAnnouncedMenuIndex = -1
        lastSwipeY = pressStartY
        cancelPendingDistanceSpeech()
        ar.setDistanceSpeechListener(null)
        tts.pause()
        announceCurrentMenuItem()
    }

    private fun onSwipeMove(currentY: Float) {
        val delta = lastSwipeY - currentY
        if (abs(delta) >= swipeStepPx) {
            val steps = (delta / swipeStepPx).toInt()
            val newIndex = (currentMenuIndex + steps).coerceIn(0, menuItems.size - 1)
            lastSwipeY = currentY
            if (newIndex != currentMenuIndex) {
                currentMenuIndex = newIndex
                announceCurrentMenuItem()
            }
        }
    }

    private fun announceCurrentMenuItem() {
        if (lastAnnouncedMenuIndex == currentMenuIndex) return
        lastAnnouncedMenuIndex = currentMenuIndex
        tts.speak(menuItems[currentMenuIndex].first)
    }

    private fun onScreenReleased(execute: Boolean) {
        pressActive = false
        if (execute) {
            menuItems[currentMenuIndex].second.invoke()
        } else {
            bindDistanceSpeech()
        }
    }

    private fun onMenuCancel() {
        bindDistanceSpeech()
    }

    private fun doAnalyze() {
        if (tts.isReady()) tts.speak("Анализирую")
        runServerAnalysis(ServerAnalyzer.Mode.CAPTION)
    }

    private fun doOcr() {
        if (tts.isReady()) tts.speak("Читаю текст")
        runServerAnalysis(ServerAnalyzer.Mode.OCR)
    }

    private fun runServerAnalysis(mode: ServerAnalyzer.Mode) {
        ar.requestCapture()
        distanceTtsHandler.postDelayed({
            val snap = ar.getLastSnapshot()
            if (snap == null) {
                if (tts.isReady()) tts.speak("Не удалось получить кадр")
                bindDistanceSpeech()
                return@postDelayed
            }
            server.analyze(
                snap = snap,
                mode = mode,
                sensorAngle = 0,
                onResult = { result ->
                    if (tts.isReady()) {
                        tts.speak(if (result.isBlank()) "Сервер не вернул результат" else result)
                    }
                    bindDistanceSpeech()
                },
                onError = { err ->
                    if (tts.isReady()) tts.speak("Ошибка: $err")
                    bindDistanceSpeech()
                },
            )
        }, CAPTURE_WAIT_MS)
    }

    private fun speakVoiceCommandsHelp() {
        if (!tts.isReady()) return
        cancelPendingDistanceSpeech()
        tts.speak("Зажмите экран и проведите пальцем вверх или вниз чтобы выбрать команду. Отпустите чтобы выполнить.")
    }

    override fun onResume() {
        super.onResume()
        if (!openingHintsSpoken) {
            distanceTtsHandler.removeCallbacks(openingHintRunnable)
            distanceTtsHandler.postDelayed(openingHintRunnable, OPENING_HINT_DELAY_MS)
        }
        bindDistanceSpeech()
        vib.start()
        // Голосовые команды временно отключены — используется свайп-меню
        // if (hasAudioPermission()) voice.start()
        if (hasCameraPermission()) {
            ar.onResume()
        } else {
            ar.setStatus("Нужно разрешение на камеру")
        }
    }

    override fun onPause() {
        distanceTtsHandler.removeCallbacks(openingHintRunnable)
        distanceTtsHandler.removeCallbacks(longPressRunnable)
        pressActive = false
        cancelPendingDistanceSpeech()
        ar.setDistanceSpeechListener(null)
        ar.onPause()
        vib.stop()
        voice.stop()
        super.onPause()
    }

    override fun onDestroy() {
        distanceTtsHandler.removeCallbacks(openingHintRunnable)
        distanceTtsHandler.removeCallbacks(longPressRunnable)
        cancelPendingDistanceSpeech()
        ar.setDistanceSpeechListener(null)
        server.shutdown()
        tts.shutdown()
        super.onDestroy()
    }

    private fun ensureCameraPermission() {
        if (hasCameraPermission()) return
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
    }

    private fun ensureAudioPermission() {
        if (hasAudioPermission()) return
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ar.onResume()
            } else {
                ar.setStatus("Доступ к камере запрещён")
            }
        }
    }

    private fun distanceShortPhraseRu(meters: Float): String {
        if (!meters.isFinite() || meters < 0f) return ""
        val m = meters.toDouble().coerceAtMost(80.0)
        if (m < 1.0) return "близко"
        val k = kotlin.math.round(m * 2.0).toInt().coerceIn(2, 80)
        if (k == 3) return "полтора"
        return if (k % 2 == 0) wholeMetersShortRu(k / 2) else "${wholeMetersShortRu(k / 2)} и пять"
    }

    private fun wholeMetersShortRu(n: Int): String = when (n) {
        1 -> "метр"; 2 -> "два"; 3 -> "три"; 4 -> "четыре"; 5 -> "пять"
        6 -> "шесть"; 7 -> "семь"; 8 -> "восемь"; 9 -> "девять"; 10 -> "десять"
        11 -> "одиннадцать"; 12 -> "двенадцать"; 13 -> "тринадцать"; 14 -> "четырнадцать"
        15 -> "пятнадцать"; 16 -> "шестнадцать"; 17 -> "семнадцать"; 18 -> "восемнадцать"
        19 -> "девятнадцать"; 20 -> "двадцать"
        else -> n.toString()
    }

    private companion object {
        private const val REQ_CAMERA = 1001
        private const val REQ_AUDIO = 1002
        private const val DISTANCE_TTS_DEBOUNCE_MS = 380L
        private const val OPENING_HINT_DELAY_MS = 1400L
        private const val LONG_PRESS_MS = 600L
        private const val CAPTURE_WAIT_MS = 800L
        private const val TRIPLE_TAP_WINDOW_MS = 400L
    }
}
