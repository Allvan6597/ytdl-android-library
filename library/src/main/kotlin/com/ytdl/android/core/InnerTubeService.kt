package com.ytdl.android.core

import com.ytdl.android.model.InnerTubeClient
import com.ytdl.android.model.YTDLConfig
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * InnerTube API Service
 *
 * يُنفذ نفس منطق HTTP calls التي تقوم بها yt-dlp عند استدعاء:
 *   self._download_json(self._INNERTUBE_API_URL, ...)
 *
 * مرجع yt-dlp 2026.06.09:
 *   yt_dlp/extractor/youtube/_base.py — _call_api()
 */
internal class InnerTubeService(private val config: YTDLConfig) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient: OkHttpClient = buildClient()

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(config.readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)

        if (config.enableLogging) {
            val logging = HttpLoggingInterceptor()
            logging.level = HttpLoggingInterceptor.Level.BASIC
            builder.addInterceptor(logging)
        }

        return builder.build()
    }

    /**
     * استدعاء InnerTube /player endpoint
     *
     * يُرسل نفس JSON body الذي ترسله yt-dlp:
     * {
     *   "videoId": "...",
     *   "context": { "client": { ... } },
     *   "playbackContext": { ... }
     * }
     */
    suspend fun fetchPlayerResponse(
        videoId: String,
        client: InnerTubeClient
    ): JsonObject? {
        val requestBody = buildPlayerRequestBody(videoId, client)

        val request = Request.Builder()
            .url(buildPlayerUrl(client))
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .apply { addInnerTubeHeaders(client) }
            .build()

        return executeRequest(request)?.let { responseStr ->
            json.parseToJsonElement(responseStr).jsonObject
        }
    }

    /**
     * بناء URL الـ player API
     * مرجع: yt-dlp _INNERTUBE_API_URL
     */
    private fun buildPlayerUrl(client: InnerTubeClient): String {
        return "https://www.youtube.com/youtubei/v1/player"
    }

    /**
     * بناء request body لـ InnerTube API
     *
     * مرجع yt-dlp: _build_innertube_request() في _base.py
     */
    private fun buildPlayerRequestBody(
        videoId: String,
        client: InnerTubeClient
    ): JsonObject {
        val contextObj = buildContext(client)

        return buildJsonObject {
            put("videoId", videoId)
            put("context", contextObj)

            // playbackContext — لتحسين جودة الستريم
            put("playbackContext", buildJsonObject {
                put("contentPlaybackContext", buildJsonObject {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    put("signatureTimestamp", getSignatureTimestamp())
                })
            })

            // contentCheckOk — لتجاوز بعض قيود المحتوى
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
    }

    /**
     * بناء InnerTube context object
     * مرجع: yt-dlp _generate_api_headers() → context
     */
    private fun buildContext(client: InnerTubeClient): JsonObject {
        return buildJsonObject {
            put("client", buildJsonObject {
                put("clientName", client.clientName)
                put("clientVersion", client.clientVersion)
                put("hl", "ar")          // اللغة
                put("gl", "SA")          // المنطقة
                put("utcOffsetMinutes", 180)

                // Android-specific fields
                if (client.androidSdkVersion != null) {
                    put("androidSdkVersion", client.androidSdkVersion)
                }

                // OS Version
                client.osVersion?.let { put("osVersion", it) }

                // Platform
                put("platform", client.platform)

                // User-Agent داخل context
                put("userAgent", client.userAgent)

                // deviceMake / deviceModel للـ Android client
                if (client == InnerTubeClient.ANDROID ||
                    client == InnerTubeClient.ANDROID_EMBEDDED) {
                    put("deviceMake", "Google")
                    put("deviceModel", "Pixel 8")
                    put("osName", "Android")
                }
            })
        }
    }

    /**
     * إضافة HTTP headers الخاصة بـ InnerTube
     * مرجع: yt-dlp _generate_api_headers()
     */
    private fun Request.Builder.addInnerTubeHeaders(client: InnerTubeClient): Request.Builder {
        addHeader("User-Agent", config.customUserAgent ?: client.userAgent)
        addHeader("Content-Type", "application/json")
        addHeader("Accept", "*/*")
        addHeader("Accept-Language", "ar-SA,ar;q=0.9,en;q=0.8")
        addHeader("Origin", "https://www.youtube.com")
        addHeader("Referer", "https://www.youtube.com/")

        // X-YouTube-Client headers
        addHeader("X-YouTube-Client-Name", getClientId(client))
        addHeader("X-YouTube-Client-Version", client.clientVersion)

        return this
    }

    /**
     * رقم client ID المقابل لاسم العميل
     * مرجع: yt-dlp _YT_CLIENTS dict
     */
    private fun getClientId(client: InnerTubeClient): String = when (client) {
        InnerTubeClient.ANDROID -> "3"
        InnerTubeClient.ANDROID_EMBEDDED -> "55"
        InnerTubeClient.IOS -> "5"
        InnerTubeClient.TV_EMBEDDED -> "85"
        InnerTubeClient.WEB -> "1"
    }

    /**
     * signature timestamp — yt-dlp يجلبه من player JS
     * نستخدم قيمة ثابتة مناسبة كـ fallback
     */
    private fun getSignatureTimestamp(): Int {
        // في التطبيق الكامل: يُستخرج من player JS
        // القيمة الحالية تعمل مع عملاء ANDROID/IOS
        return 20111
    }

    private fun executeRequest(request: Request): String? {
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else {
                    null
                }
            }
        } catch (e: IOException) {
            null
        }
    }
}
