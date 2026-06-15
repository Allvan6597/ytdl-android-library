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

/**
 * InnerTube API Service
 *
 * الإصلاح الجذري:
 *  - ANDROID_TESTSUITE يستخدم context بسيط جداً (isMinimalContext=true)
 *  - العملاء الأخرى تستخدم context كامل مطابق لـ yt-dlp
 *  - executeRequestSuspend: coroutine-safe عبر enqueue()
 */
internal class InnerTubeService(private val config: YTDLConfig) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.connectTimeoutSec, TimeUnit.SECONDS)
        .readTimeout(config.readTimeoutSec, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .apply {
            if (config.enableLogging) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                })
            }
        }
        .build()

    suspend fun fetchPlayerResponse(
        videoId: String,
        client: InnerTubeClient
    ): JsonObject? = withContext(Dispatchers.IO) {

        val body = buildPlayerRequestBody(videoId, client)

        val request = Request.Builder()
            .url(PLAYER_URL)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .apply { addHeaders(client) }
            .build()

        val responseStr = executeRequestSuspend(request) ?: return@withContext null

        runCatching {
            json.parseToJsonElement(responseStr).jsonObject
        }.getOrNull()
    }

    private fun buildPlayerRequestBody(videoId: String, client: InnerTubeClient): JsonObject {
        return buildJsonObject {
            put("videoId", videoId)
            put("context", buildContext(client))
            put("contentCheckOk", true)
            put("racyCheckOk", true)

            if (client.requiresSigCipher) {
                put("playbackContext", buildJsonObject {
                    put("contentPlaybackContext", buildJsonObject {
                        put("html5Preference", "HTML5_PREF_WANTS")
                        put("signatureTimestamp", SIG_TIMESTAMP)
                    })
                })
            }
        }
    }

    /**
     * buildContext — منطق مختلف لكل نوع عميل
     *
     * ANDROID_TESTSUITE: context بسيط جداً — clientName + clientVersion فقط
     *   هذا هو سبب نجاحه في تجاوز PO Token: YouTube لا يعرّفه كعميل حقيقي
     *   يعامله كـ test client → لا يُطبّق عليه botGuard
     *
     * العملاء الأخرى: context كامل مطابق لـ yt-dlp
     */
    private fun buildContext(client: InnerTubeClient): JsonObject {
        return buildJsonObject {
            put("client", buildJsonObject {
                put("clientName", client.clientName)
                put("clientVersion", client.clientVersion)

                // ANDROID_TESTSUITE: توقف هنا — لا تُضف أي شيء آخر
                if (client.isMinimalContext) return@buildJsonObject

                // Android clients
                if (client.androidSdkVersion != null) {
                    put("androidSdkVersion", client.androidSdkVersion)
                    put("osName", "Android")
                    put("osVersion", client.osVersion ?: "11")
                    put("deviceMake", "Google")
                    put("deviceModel", "Pixel 7")
                }

                // iOS
                if (client == InnerTubeClient.IOS) {
                    put("osName", "iPhone")
                    put("osVersion", client.osVersion ?: "17.7.2.21H221")
                    put("deviceMake", "Apple")
                    put("deviceModel", "iPhone16,2")
                }

                put("platform", client.platform)
                put("userAgent", client.userAgent)
            })

            // thirdParty للعملاء المدمجة
            if (client == InnerTubeClient.ANDROID_EMBEDDED ||
                client == InnerTubeClient.TV_EMBEDDED) {
                put("thirdParty", buildJsonObject {
                    put("embedUrl", "https://www.youtube.com/")
                })
            }
        }
    }

    private fun Request.Builder.addHeaders(client: InnerTubeClient): Request.Builder {
        addHeader("User-Agent", config.customUserAgent ?: client.userAgent)
        addHeader("Content-Type", "application/json")
        addHeader("Accept", "*/*")
        addHeader("Accept-Language", "en-US,en;q=0.9")
        addHeader("Origin", "https://www.youtube.com")
        addHeader("X-YouTube-Client-Name", getClientId(client))
        addHeader("X-YouTube-Client-Version", client.clientVersion)
        if (client.platform == "DESKTOP" || client.platform == "TV") {
            addHeader("Referer", "https://www.youtube.com/")
        }
        return this
    }

    private fun getClientId(client: InnerTubeClient): String = when (client) {
        InnerTubeClient.ANDROID_TESTSUITE -> "30"
        InnerTubeClient.ANDROID           -> "3"
        InnerTubeClient.ANDROID_EMBEDDED  -> "55"
        InnerTubeClient.ANDROID_VR        -> "28"
        InnerTubeClient.IOS               -> "5"
        InnerTubeClient.TV_EMBEDDED       -> "85"
    }

    /** coroutine-safe HTTP — enqueue بدلاً من execute */
    private suspend fun executeRequestSuspend(request: Request): String? {
        return suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(request)
            cont.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!cont.isActive) { response.close(); return }
                    try {
                        val body = if (response.isSuccessful) {
                            response.body?.string()
                        } else {
                            if (config.enableLogging) {
                                android.util.Log.w(TAG,
                                    "HTTP ${response.code} — client ${request.header("X-YouTube-Client-Name")}")
                            }
                            null
                        }
                        response.close()
                        cont.resume(body)
                    } catch (e: Exception) {
                        response.close()
                        cont.resume(null)
                    }
                }
            })
        }
    }

    companion object {
        private const val PLAYER_URL    = "https://www.youtube.com/youtubei/v1/player"
        private const val SIG_TIMESTAMP = 20111
        private const val TAG           = "YTDLAndroid"
        private val JSON_TYPE           = "application/json".toMediaType()
    }
}
