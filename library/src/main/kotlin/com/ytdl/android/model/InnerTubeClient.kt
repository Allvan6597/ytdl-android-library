package com.ytdl.android.model

/**
 * InnerTube Client Configurations
 *
 * مستنسخة من yt-dlp/yt_dlp/extractor/youtube/_base.py
 * مرجع: INNERTUBE_CLIENTS dict — yt-dlp 2026.06.09
 *
 * الأولوية الموصى بها:
 *  1. ANDROID      — لا يحتاج PO Token، يعطي stream URLs مباشرة
 *  2. IOS          — بديل جيد، أكثر استقراراً من WEB
 *  3. TV_EMBEDDED  — تجاوز قيود العمر في بعض الحالات
 *  4. WEB          — يحتاج signature cipher فك تشفير + PO Token
 */
enum class InnerTubeClient(
    val clientName: String,
    val clientVersion: String,
    val androidSdkVersion: Int?,
    val osVersion: String?,
    val platform: String,
    val userAgent: String,
    val requiresSigCipher: Boolean,
    val requiresPoToken: Boolean,
    val apiKey: String?
) {

    /**
     * ANDROID client — الأفضل لـ Android:
     * - لا يحتاج فك تشفير signature cipher
     * - لا يحتاج PO Token في معظم الحالات
     * - يعطي stream URLs مباشرة صالحة
     */
    ANDROID(
        clientName = "ANDROID",
        clientVersion = "19.44.38",
        androidSdkVersion = 30,
        osVersion = "11",
        platform = "MOBILE",
        userAgent = "com.google.android.youtube/19.44.38 (Linux; U; Android 11) gzip",
        requiresSigCipher = false,
        requiresPoToken = false,
        apiKey = null  // yt-dlp 2026: لم تعد API key مطلوبة
    ),

    /**
     * ANDROID_EMBEDDED_PLAYER — لتجاوز بعض القيود
     */
    ANDROID_EMBEDDED(
        clientName = "ANDROID_EMBEDDED_PLAYER",
        clientVersion = "19.44.38",
        androidSdkVersion = 30,
        osVersion = "11",
        platform = "MOBILE",
        userAgent = "com.google.android.youtube/19.44.38 (Linux; U; Android 11) gzip",
        requiresSigCipher = false,
        requiresPoToken = false,
        apiKey = null
    ),

    /**
     * IOS client — بديل موثوق للـ ANDROID
     */
    IOS(
        clientName = "IOS",
        clientVersion = "19.45.4",
        androidSdkVersion = null,
        osVersion = "17.7.2",
        platform = "MOBILE",
        userAgent = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 17_7_2 like Mac OS X)",
        requiresSigCipher = false,
        requiresPoToken = false,
        apiKey = null
    ),

    /**
     * TV_EMBEDDED — للمحتوى المقيد بالعمر (age-gated)
     */
    TV_EMBEDDED(
        clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        clientVersion = "2.0",
        androidSdkVersion = null,
        osVersion = null,
        platform = "TV",
        userAgent = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 5.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/5.0 TV Safari/538.1",
        requiresSigCipher = true,
        requiresPoToken = false,
        apiKey = null
    ),

    /**
     * WEB client — يحتاج signature cipher + PO Token
     * استخدمه كـ fallback أخير فقط
     */
    WEB(
        clientName = "WEB",
        clientVersion = "2.20241126.01.00",
        androidSdkVersion = null,
        osVersion = null,
        platform = "DESKTOP",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        requiresSigCipher = true,
        requiresPoToken = true,
        apiKey = null
    );

    companion object {
        /**
         * سلسلة الـ fallback — نفس منطق yt-dlp
         */
        val FALLBACK_CHAIN: List<InnerTubeClient> = listOf(
            ANDROID,
            IOS,
            ANDROID_EMBEDDED,
            TV_EMBEDDED
        )
    }
}
