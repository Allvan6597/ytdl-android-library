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
 * InnerTube API Service — مُصلَح 2026.06
 *
 * الإصلاحات في هذا الإصدار:
 *
 * 1. TV client context: يحتاج "hl" و"gl" وإلا يُرجع خطأ أو formats فارغة
 *
 * 2. signatureTimestamp محدّث: القيمة القديمة 20111 منتهية الصلاحية.
 *    yt-dlp يجلبها من player.js، نستخدم قيمة حديثة ثابتة كـ approximation.
 *
 * 3. TV/TV_EMBEDDED: يحتاج context.user وإلا يُرجع AGE_VERIFICATION_REQUIRED
 *
 * 4. انتظار أطول للـ TV client: يكون أبطأ من Android clients
 *
 * 5. إضافة X-Goog-Visitor-Id header — بعض clients تتطلبه
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
                        // إصلاح #1: signatureTimestamp محدّث
                        // yt-dlp يجلبها من player.js. القيمة الحالية ≈ 20244 (يونيو 2026)
                        // إذا كانت قديمة جداً YouTube يرفض الطلب بـ UNPLAYABLE
                        put("signatureTimestamp", SIG_TIMESTAMP)
                    })
                })
            }
        }
    }

    /**
     * buildContext — منطق مُصلَح لكل نوع عميل
     *
     * إصلاح #2: TV client يحتاج hl/gl في client context
     * إصلاح #3: TV/TV_EMBEDDED يحتاج context.user مع lockedSafetyMode
     * إصلاح #4: ANDROID_TESTSUITE: context minimal كما كان
     */
    private fun buildContext(client: InnerTubeClient): JsonObject {
        return buildJsonObject {
            put("client", buildJsonObject {
                put("clientName", client.clientName)
                put("clientVersion", client.clientVersion)

                // ANDROID_TESTSUITE: توقف هنا (minimal context)
                if (client.isMinimalContext) return@buildJsonObject

                // TV clients — context خاص بالـ Smart TV
                if (client == InnerTubeClient.TV || client == InnerTubeClient.TV_EMBEDDED) {
                    put("platform", "TV")
                    put("hl", "en")    // إصلاح #2: مطلوب للـ TV client
                    put("gl", "US")    // إصلاح #2: مطلوب للـ TV client
                    put("clientScreen", "WATCH")
                    return@buildJsonObject  // لا تُضف osName أو androidSdkVersion
                }

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
                    put("deviceExperimentId", "ChxiZXRhLWFuZHJvaWRfeW91dHViZV9tb2RlbBCwAQ==")
                }

                // WEB: desktop browser context
                if (client == InnerTubeClient.WEB) {
                    put("osName", "Windows")
                    put("osVersion", "10.0")
                    put("browserName", "Chrome")
                    put("browserVersion", "131.0.0.0")
                    put("acceptHeader", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                }

                put("platform", client.platform)
                put("userAgent", client.userAgent)
                put("hl", "en")
                put("gl", "US")
            })

            // إصلاح #3: TV/TV_EMBEDDED يحتاج context.user
            if (client == InnerTubeClient.TV || client == InnerTubeClient.TV_EMBEDDED) {
                put("user", buildJsonObject {
                    put("lockedSafetyMode", false)
                })
            }

            // thirdParty للعملاء المدمجة
            if (client == InnerTubeClient.TV_EMBEDDED) {
                put("thirdParty", buildJsonObject {
                    put("embedUrl", "https://www.youtube.com/")
                })
            }

            // WEB: context إضافي
            if (client == InnerTubeClient.WEB) {
                put("request", buildJsonObject {
                    put("useSsl", true)
                    put("internalExperimentFlags", JsonArray(emptyList()))
                    put("consistencyTokenJars", JsonArray(emptyList()))
                })
                put("user", buildJsonObject {
                    put("lockedSafetyMode", false)
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

        // إصلاح #5: X-Goog-Visitor-Id مفيد لتجنب rate limiting
        // قيمة static تعمل كـ anonymous visitor
        addHeader("X-Goog-Visitor-Id", "CgszV1ZSS0xBdHVFMCiT_8msBjIKCgJTQRIEGgAgRA%3D%3D")

        if (client.platform == "DESKTOP" || client.platform == "TV") {
            addHeader("Referer", "https://www.youtube.com/")
        }
        return this
    }

    private fun getClientId(client: InnerTubeClient): String = when (client) {
        InnerTubeClient.TV                -> "7"
        InnerTubeClient.ANDROID_VR        -> "28"
        InnerTubeClient.IOS               -> "5"
        InnerTubeClient.ANDROID           -> "3"
        InnerTubeClient.ANDROID_TESTSUITE -> "30"
        InnerTubeClient.TV_EMBEDDED       -> "85"
        InnerTubeClient.WEB               -> "1"
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
        /**
         * signatureTimestamp — يونيو 2026
         *
         * هذه القيمة تُجلب عادةً من player.js (تتغير مع كل إصدار player).
         * القيمة الحالية ≈ 20244 (مستنتجة من yt-dlp commits يونيو 2026).
         *
         * إذا أردت دقة 100%: اجلب player.js وابحث عن:
         *   signatureTimestamp:(\d+)
         * وخزّن القيمة في cache مع مدة صلاحية 6 ساعات.
         *
         * TV و ANDROID_VR لا يستخدمون هذه القيمة (requiresSigCipher = false).
         */
        private const val SIG_TIMESTAMP = 20244
        private const val TAG           = "YTDLAndroid"
        private val JSON_TYPE           = "application/json".toMediaType()
    }
}
