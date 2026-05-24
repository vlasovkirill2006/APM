package com.example.blindassist

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.content.Context
import android.util.Base64

class ServerAnalyzer(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    var serverUrl: String = "http://10.179.1.65:5000"

    private val openRouterUrl = "https://openrouter.ai/api/v1/chat/completions"
    private val openRouterKey = "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"
    private val openRouterModel = "google/gemini-2.0-flash-lite-001"

    enum class Mode { CAPTION, OCR }

    fun analyze(
        snap: YuvSnapshot,
        mode: Mode,
        sensorAngle: Int = 0,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        executor.submit {
            try {
                val jpeg = yuvToJpeg(snap)
                val result = if (hasInternet()) {
                    try {
                        sendToOpenRouter(jpeg, mode)
                    } catch (t: Throwable) {
                        Log.w(TAG, "OpenRouter failed (${t.message}), falling back to local server", t)
                        try {
                            sendToServer(jpeg, mode, sensorAngle)
                        } catch (t2: Throwable) {
                            throw Exception("OpenRouter: ${t.message}. Сервер: ${t2.message}")
                        }
                    }
                } else {
                    sendToServer(jpeg, mode, sensorAngle)
                }
                mainHandler.post { onResult(result) }
            } catch (t: Throwable) {
                Log.e(TAG, "Server analysis failed", t)
                mainHandler.post { onError(t.message ?: t.javaClass.simpleName) }
            }
        }
    }

    private fun hasInternet(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun sendToOpenRouter(jpeg: ByteArray, mode: Mode): String {
        val imgB64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)

        val prompt = if (mode == Mode.OCR) {
            "Прочитай весь текст на изображении. Выведи ТОЛЬКО текст, без пояснений. Если текста нет — ответь: Текст не найден."
        } else {
            "Перечисли крупные объекты на фото и где они находятся относительно человека который держит телефон. " +
            "твоя задача сказать что передо мной без больших подробностей говори самые главные вещи на фотке если ты не мельком увидешь обьект тебе не надо думать что это ведь это не важно" +
            "Без звёздочек, без тире, без markdown, только простой текст."
        }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", prompt)
                    })
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$imgB64")
                        })
                    })
                })
            })
        }

        val body = JSONObject().apply {
            put("model", openRouterModel)
            put("messages", messages)
        }.toString()

        val request = Request.Builder()
            .url(openRouterUrl)
            .addHeader("Authorization", "Bearer $openRouterKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: throw Exception("Пустой ответ от OpenRouter")
            if (!response.isSuccessful) {
                throw Exception("OpenRouter HTTP ${response.code}: $responseBody")
            }
            val json = JSONObject(responseBody)
            return json
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        }
    }

    private fun yuvToJpeg(snap: YuvSnapshot): ByteArray {
        val nv21 = ByteArray(snap.width * snap.height + snap.width * snap.height / 2)
        System.arraycopy(snap.y, 0, nv21, 0, snap.y.size)
        val cw = snap.width / 2
        val ch = snap.height / 2
        var dst = snap.y.size
        for (i in 0 until cw * ch) {
            nv21[dst++] = snap.v[i]
            nv21[dst++] = snap.u[i]
        }
        val yuv = YuvImage(nv21, ImageFormat.NV21, snap.width, snap.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, snap.width, snap.height), 95, out)
        return out.toByteArray()
    }

    private fun sendToServer(jpeg: ByteArray, mode: Mode, sensorAngle: Int): String {
        val modeStr = if (mode == Mode.OCR) "ocr" else "caption"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "img_file", "frame.jpg",
                jpeg.toRequestBody("image/jpeg".toMediaType())
            )
            .addFormDataPart("mode", modeStr)
            .addFormDataPart("param_lang", "English")
            .addFormDataPart("param_process", "phoneCam")
            .addFormDataPart("SensorAngle", sensorAngle.toString())
            .build()

        val request = Request.Builder()
            .url("$serverUrl/hello")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            return response.body?.string()?.trim() ?: ""
        }
    }

    fun shutdown() {
        executor.shutdown()
        client.dispatcher.executorService.shutdown()
    }

    private companion object {
        private const val TAG = "ServerAnalyzer"
    }
}
