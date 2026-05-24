package com.example.blindassist

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class VibrationManager(context: Context) {
    private val appContext = context.applicationContext
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val thread = HandlerThread("VibrationThread").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var running = false
    private val amplitudeControl: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        vibrator.cancel()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return

            val p = nativeGetVibrationParams()
            if (p != null && p.size >= 3) {
                val active = p[0] != 0
                val amp = p[1].coerceIn(1, 255)
                val interval = p[2].coerceIn(50, 1000)

                if (active) {
                    val onMs = (interval / 3).coerceIn(20, 120)
                    val offMs = (interval - onMs).coerceAtLeast(20)

                    val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val amps = if (amplitudeControl) intArrayOf(0, amp, 0) else intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0)
                        VibrationEffect.createWaveform(
                            longArrayOf(0L, onMs.toLong(), offMs.toLong()),
                            amps,
                            -1
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        VibrationEffect.createOneShot(onMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
                    }

                    vibrator.vibrate(effect)
                    handler.postDelayed(this, interval.toLong())
                    return
                } else {
                    vibrator.cancel()
                }
            }

            handler.postDelayed(this, 150L)
        }
    }

    private external fun nativeGetVibrationParams(): IntArray?
}

