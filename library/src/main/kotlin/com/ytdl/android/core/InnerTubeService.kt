package com.ytdl.android.core

import com.ytdl.android.model.InnerTubeClient
import com.ytdl.android.model.YTDLConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * InnerTube API Service — FIXED VERSION
 *
 * الإصلاحات:
 *  FIX-1: executeRequest() الآن coroutine-safe عبر suspendCancellableCoroutine + enqueue()
 *          (الإصدار القديم كان يستدعي .execute() الـ blocking داخل suspend function)
 *  FIX-2: تحسين buildContext() لتطابق yt-dlp 2026.06.09 بدقة
 *  FIX-3: إضافة thirdParty.embedUrl لعميل ANDROID_EMBEDDED
 *  FIX-4: إضافة client headers صحيحة لكل عميل
 *  FIX-5: تحسين معالجة الأخطاء — تسجيل HTTP status codes
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
            logging.level = HttpLoggingInterceptor.Level.BODY
            builder.addInterceptor(logging)
        }

        return builder.build()
    }

    /**
     * FIX-1: استدعاء InnerTube /player — الآن coroutine-safe
     */
    suspend fun fetchPlayerResponse(
        videoId: String,
        client: InnerTubeClient
    ): JsonObject? = withContext(Dispatchers.IO) {

        val requestBody = buildPlayerRequestBody(videoId, client)

        val request = Request.Builder()
            .url(PLAYER_URL)
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .apply { addInnerTubeHeaders(client) }
            .build()

        val responseStr = executeRequestSuspend(request) ?: return@withContext null

        runCatching {
            json.parseToJsonElement(responseStr).jsonObject
        }.getOrNull()
    }

    /**
     * FIX-2: buildPlayerRequestBody — aligned with yt-dlp 2026.06.09 exactly
     *
     * مرجع: yt-dlp _build_innertube_request() — _base.py
     */
    private fun buildPlayerRequestBody(
        videoId: String,
        client: InnerTubeClient
    ): JsonObject {
        return buildJsonObject {
            put("videoId", videoId)
            put("context", buildContext(client))
            put("contentCheckOk", true)
            put("racyCheckOk", true)

            // playbackContext فقط للعملاء التي تحتاج signature
            if (client.requiresSigCipher) {
                put("playbackContext", buildJsonObject {
                    put("contentPlaybackContext", buildJsonObject {
                        put("html5Preference", "HTML5_PREF_WANTS")
                        put("signatureTimestamp", getSignatureTimestamp())
                    })
                })
            }
        }
    }

    /**
     * FIX-3: buildContext — مطابق لـ yt-dlp 2026.06.09 بدقة
     *
     * مرجع: yt-dlp _generate_api_headers() context object
     */
    private fun buildContext(client: InnerTubeClient): JsonObject {
        return buildJsonObject {
            put("client", buildJsonObject {
                put("clientName", client.clientName)
                put("clientVersion", client.clientVersion)

                // Android-specific
                if (client.androidSdkVersion != null) {
                    put("androidSdkVersion", client.androidSdkVersion)
                    put("osName", "Android")
                    put("osVersion", client.osVersion ?: "11")
                    put("deviceMake", "Google")
                    put("deviceModel", "Pixel 7")
                }

                // iOS-specific
                if (client == InnerTubeClient.IOS) {
                    put("osName", "iPhone")
                    put("osVersion", client.osVersion ?: "17.7.2.21H221")
                    put("deviceMake", "Apple")
                    put("deviceModel", "iPhone16,2")
                }

                put("platform", client.platform)
                put("userAgent", client.userAgent)
            })

            // FIX-4: thirdParty context للـ ANDROID_EMBEDDED (مطلوب!)
            if (client == InnerTubeClient.ANDROID_EMBEDDED ||
                client == InnerTubeClient.TV_EMBEDDED) {
                put("thirdParty", buildJsonObject {
                    put("embedUrl", "https://www.youtube.com/")
                })
            }
        }
    }

    /**
     * FIX-5: HTTP headers مطابقة لـ yt-dlp
     */
    private fun Request.Builder.addInnerTubeHeaders(client: InnerTubeClient): Request.Builder {
        addHeader("User-Agent", config.customUserAgent ?: client.userAgent)
        addHeader("Content-Type", "application/json")
        addHeader("Accept", "*/*")
        addHeader("Accept-Language", "en-US,en;q=0.9")  // FIX: en ليس ar (YouTube يرفض بعض المناطق)
        addHeader("Origin", "https://www.youtube.com")
        addHeader("X-YouTube-Client-Name", getClientId(client))
        addHeader("X-YouTube-Client-Version", client.clientVersion)
        // FIX: Referer غير مطلوب للـ mobile clients وقد يسبب رفض
        if (client.platform == "DESKTOP" || client.platform == "TV") {
            addHeader("Referer", "https://www.youtube.com/")
        }
        return this
    }

    private fun getClientId(client: InnerTubeClient): String = when (client) {
        InnerTubeClient.ANDROID          -> "3"
        InnerTubeClient.ANDROID_EMBEDDED -> "55"
        InnerTubeClient.ANDROID_VR       -> "28"
        InnerTubeClient.IOS              -> "5"
        InnerTubeClient.TV_EMBEDDED      -> "85"
        InnerTubeClient.WEB              -> "1"
    }

    private fun getSignatureTimestamp(): Int = 20111

    /**
     * FIX-1 CORE: coroutine-safe HTTP request
     *
     * الإصدار القديم كان يستخدم .execute() الـ blocking داخل suspend function.
     * هذا يسبب NetworkOnMainThreadException أو deadlock على Android.
     * الحل: suspendCancellableCoroutine + OkHttp .enqueue()
     */
    private suspend fun executeRequestSuspend(request: Request): String? {
        return suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)

            continuation.invokeOnCancellation {
                call.cancel()
            }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resume(null)  // network error → null → try next client
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    try {
                        val body = if (response.isSuccessful) {
                            response.body?.string()
                        } else {
                            // FIX: log status code for debugging
                            if (config.enableLogging) {
                                android.util.Log.w(
                                    "YTDLAndroid",
                                    "HTTP ${response.code} for client ${request.header("X-YouTube-Client-Name")}"
                                )
                            }
                            null
                        }
                        response.close()
                        continuation.resume(body)
                    } catch (e: Exception) {
                        response.close()
                        continuation.resume(null)
                    }
                }
            })
        }
    }

    companion object {
        private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
