package com.example.blindassist

import android.app.Activity
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.FrameLayout

import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.*

import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10


class ARCoreManager : GLSurfaceView.Renderer {
    private var activity: Activity? = null
    private var glView: GLSurfaceView? = null
    private var session: Session? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraTextureId: Int = 0
    private var textureBoundToSession: Boolean = false
    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0
    private var displayRotation: Int = 0
    private var lastTrackingHintMs: Long = 0L
    @Volatile private var captureRequested: Boolean = false
    private var captureNotReadyRetries = 0

    private var lastDepthProcessAtMs: Long = 0L

    @Volatile private var restartRequested: Boolean = false
    private var lastRestartAtMs: Long = 0L
    private var restartAttemptsInWindow: Int = 0
    private var restartWindowStartMs: Long = 0L
    private val restartRunnable = Runnable { performRestartIfNeeded() }

    private var distanceSpeechListener: ((Float) -> Unit)? = null
    private var lastSnapshot: YuvSnapshot? = null

    fun getLastSnapshot(): YuvSnapshot? = lastSnapshot

    private var lastDistSpeechMeters: Float = -1f
    private var lastDistSpeechAtMs: Long = 0L

    fun setDistanceSpeechListener(cb: ((Float) -> Unit)?) {
        distanceSpeechListener = cb
    }

    private fun maybePostDistanceSpeech(meters: Float) {
        val now = SystemClock.elapsedRealtime()
        if (lastDistSpeechMeters < 0f) {
            lastDistSpeechMeters = meters
            lastDistSpeechAtMs = now
            mainHandler.post { distanceSpeechListener?.invoke(meters) }
            return
        }
        val delta = kotlin.math.abs(meters - lastDistSpeechMeters)
        val elapsed = now - lastDistSpeechAtMs
        if (elapsed < 6500 && delta < 0.42f) return
        lastDistSpeechMeters = meters
        lastDistSpeechAtMs = now
        mainHandler.post { distanceSpeechListener?.invoke(meters) }
    }

    fun attach(activity: Activity) {
        this.activity = activity
        glView = GLSurfaceView(activity).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setRenderer(this@ARCoreManager)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setZOrderOnTop(false)
            setZOrderMediaOverlay(false)
        }

        activity.addContentView(
            glView,
            FrameLayout.LayoutParams(4, 4).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
            }
        )
        glView?.isClickable = false
        glView?.isFocusable = false
    }

    fun onResume() {
        val act = activity ?: return
        val installStatus = ArCoreApk.getInstance().requestInstall(act, /*userRequestedInstall=*/true)
        if (installStatus != ArCoreApk.InstallStatus.INSTALLED) {
            setStatus("Запрошена установка ARCore…")
            return
        }

        if (session == null) {
            try {
                session = Session(act)
            } catch (e: UnavailableArcoreNotInstalledException) {
                setStatus("ARCore не установлен")
                return
            } catch (e: UnavailableApkTooOldException) {
                setStatus("Слишком старая версия ARCore")
                return
            } catch (e: UnavailableSdkTooOldException) {
                setStatus("Слишком старый SDK ARCore")
                return
            } catch (e: UnavailableDeviceNotCompatibleException) {
                setStatus("Устройство не поддерживает ARCore")
                return
            } catch (e: Exception) {
                setStatus("Ошибка сессии ARCore: ${e.javaClass.simpleName}")
                return
            }

            val s = session ?: return
            val config = Config(s).apply {
                depthMode = Config.DepthMode.AUTOMATIC
                planeFindingMode = Config.PlaneFindingMode.DISABLED
            }

            val supported = s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            if (!supported) {
                setStatus("Глубина на этом устройстве недоступна")
            } else {
                setStatus("Режим глубины включён")
            }

            try {
                s.configure(config)
            } catch (e: Exception) {
                setStatus("Ошибка настройки ARCore: ${e.javaClass.simpleName}")
            }

            textureBoundToSession = false
        }

        try {
            session?.resume()
            glView?.onResume()
            lastDepthProcessAtMs = 0L
            restartRequested = false
        } catch (e: CameraNotAvailableException) {
            setStatus("Камера недоступна")
            requestRestart("CameraNotAvailableException")
        }
    }

    fun onPause() {
        glView?.onPause()
        session?.pause()
        mainHandler.removeCallbacks(restartRunnable)
    }

    fun setStatus(text: String) {
        mainHandler.post { nativeOnStatus(text) }
    }

    fun requestCapture() {
        captureNotReadyRetries = 0
        captureRequested = true
        setStatus("Запрошен захват кадра…")
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        textureBoundToSession = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        val act = activity
        displayRotation = if (act != null) {
            @Suppress("DEPRECATION")
            act.windowManager.defaultDisplay.rotation
        } else {
            0
        }
        try {
            session?.setDisplayGeometry(displayRotation, width, height)
        } catch (_: Throwable) {
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        val s = session ?: return
        try {
            if (!textureBoundToSession && cameraTextureId != 0) {
                s.setCameraTextureName(cameraTextureId)
                textureBoundToSession = true
            }
            activity?.let { act ->
                @Suppress("DEPRECATION")
                displayRotation = act.windowManager.defaultDisplay.rotation
            }
            if (viewportWidth > 0 && viewportHeight > 0) {
                s.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
            }
            val frame: Frame = s.update()
            val cam = frame.camera
            if (cam.trackingState != TrackingState.TRACKING) {
                val now = System.currentTimeMillis()
                if (now - lastTrackingHintMs > 2500L) {
                    lastTrackingHintMs = now
                    setStatus("Медленно ведите устройство — строится карта (${trackingStateRu(cam.trackingState)})")
                }
            }

            val nowMs = SystemClock.elapsedRealtime()
            val minIntervalMs = PROCESS_INTERVAL_MS
            val shouldProcessDepth = captureRequested || (nowMs - lastDepthProcessAtMs >= minIntervalMs)
            if (shouldProcessDepth) {
                lastDepthProcessAtMs = nowMs
                val distance = DepthDistanceEstimator.estimateMeters(frame)
                if (distance != null) {
                    nativeOnDistance(distance)
                    maybePostDistanceSpeech(distance)
                }
            }

            if (captureRequested && cam.trackingState == TrackingState.TRACKING) {
                captureRequested = false
                try {
                    val img = frame.acquireCameraImage()
                    img.use { yuv ->
                        if (yuv.planes.size >= 3) {
                            setStatus("Кадр захвачен ${yuv.width}×${yuv.height}")
                            lastSnapshot = YuvSnapshot.fromImage(yuv)
                        } else {
                            setStatus("Некорректный формат изображения камеры")
                        }
                        captureNotReadyRetries = 0
                    }
                } catch (_: NotYetAvailableException) {
                    if (captureNotReadyRetries < MAX_CAMERA_IMAGE_RETRIES) {
                        captureNotReadyRetries++
                        setStatus("Кадр камеры ещё не готов, повтор ${captureNotReadyRetries}/${MAX_CAMERA_IMAGE_RETRIES}")
                        captureRequested = true
                    } else {
                        captureNotReadyRetries = 0
                        setStatus("Кадр с камеры не получен. Скажите команду захвата ещё раз.")
                    }
                } catch (t: Throwable) {
                    captureNotReadyRetries = 0
                    Log.e(TAG, "Capture failed", t)
                    setStatus("Ошибка захвата: ${t.javaClass.simpleName}")
                }
            }
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
            setStatus("Камера недоступна")
            requestRestart("CameraNotAvailableException")
        } catch (t: Throwable) {
            Log.e(TAG, "ARCore update error", t)
            requestRestart(t.javaClass.simpleName ?: "Throwable")
        }
    }

    private fun requestRestart(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRestartAtMs < RESTART_COOLDOWN_MS) return
        restartRequested = true
        setStatus("ARCore перезапуск… ($reason)")
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.post(restartRunnable)
    }

    private fun performRestartIfNeeded() {
        if (!restartRequested) return
        val act = activity ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRestartAtMs < RESTART_COOLDOWN_MS) return

        if (restartWindowStartMs == 0L || now - restartWindowStartMs > RESTART_WINDOW_MS) {
            restartWindowStartMs = now
            restartAttemptsInWindow = 0
        }
        if (restartAttemptsInWindow >= MAX_RESTARTS_PER_WINDOW) {
            restartRequested = false
            setStatus("ARCore нестабилен. Перезапуски остановлены на ${RESTART_WINDOW_MS / 1000} секунд.")
            return
        }
        restartAttemptsInWindow++
        lastRestartAtMs = now
        restartRequested = false

        try {
            try {
                session?.pause()
            } catch (_: Throwable) {
            }
            try {
                session?.close()
            } catch (_: Throwable) {
            }
            session = null
            textureBoundToSession = false
            lastDepthProcessAtMs = 0L

            onResume()
            setStatus("ARCore восстановлен")
        } catch (t: Throwable) {
            Log.e(TAG, "Restart failed", t)
            setStatus("Не удалось перезапустить ARCore: ${t.javaClass.simpleName}")
        }
    }

    fun syncDistanceSpeechModelEnabled(enabled: Boolean) {
        nativeSetDistanceSpeechEnabled(enabled)
    }

    private fun trackingStateRu(ts: TrackingState): String = when (ts) {
        TrackingState.TRACKING -> "отслеживание"
        TrackingState.PAUSED -> "пауза"
        TrackingState.STOPPED -> "остановлено"
        else -> ts.name
    }

    private external fun nativeSetDistanceSpeechEnabled(enabled: Boolean)
    private external fun nativeOnDistance(meters: Float)
    private external fun nativeOnStatus(text: String)

    private companion object {
        private const val TAG = "ARCoreManager"
        private const val MAX_CAMERA_IMAGE_RETRIES = 25
        private const val PROCESS_INTERVAL_MS = 67L
        private const val RESTART_COOLDOWN_MS = 1200L
        private const val RESTART_WINDOW_MS = 10_000L
        private const val MAX_RESTARTS_PER_WINDOW = 3
    }
}

