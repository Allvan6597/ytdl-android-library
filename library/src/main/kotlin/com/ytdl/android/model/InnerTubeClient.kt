package com.ytdl.android.model

/**
 * InnerTube Client Configurations
 *
 * مستنسخة من yt-dlp/yt_dlp/extractor/youtube/_base.py
 * مرجع: INNERTUBE_CLIENTS dict — yt-dlp 2026.06.09
 *
 * FIX: تحديث client versions لتتطابق مع yt-dlp 2026.06.09 الفعلي
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
     * ANDROID client
     * FIX: version corrected to 19.44.38 per yt-dlp 2026.06.09
     */
    ANDROID(
        clientName        = "ANDROID",
        clientVersion     = "19.44.38",
        androidSdkVersion = 30,
        osVersion         = "11",
        platform          = "MOBILE",
        userAgent         = "com.google.android.youtube/19.44.38 (Linux; U; Android 11) gzip",
        requiresSigCipher = false,
        requiresPoToken   = false,
        apiKey            = null
    ),

    /**
     * ANDROID_EMBEDDED_PLAYER
     * FIX: version aligned with ANDROID + thirdParty context added in service
     */
    ANDROID_EMBEDDED(
        clientName        = "ANDROID_EMBEDDED_PLAYER",
        clientVersion     = "19.44.38",
        androidSdkVersion = 30,
        osVersion         = "11",
        platform          = "MOBILE",
        userAgent         = "com.google.android.youtube/19.44.38 (Linux; U; Android 11) gzip",
        requiresSigCipher = false,
        requiresPoToken   = false,
        apiKey            = null
    ),

    /**
     * IOS client
     * FIX: version corrected to 19.45.4 per yt-dlp 2026.06.09
     */
    IOS(
        clientName        = "IOS",
        clientVersion     = "19.45.4",
        androidSdkVersion = null,
        osVersion         = "17.7.2.21H221",
        platform          = "MOBILE",
        userAgent         = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 17_7_2 like Mac OS X)",
        requiresSigCipher = false,
        requiresPoToken   = false,
        apiKey            = null
    ),

    /**
     * ANDROID_VR — FIX: added as primary bypass client (used by yt-dlp 2026)
     * Does NOT need sig cipher, most reliable in 2026
     */
    ANDROID_VR(
        clientName        = "ANDROID_VR",
        clientVersion     = "1.60.19",
        androidSdkVersion = 30,
        osVersion         = "11",
        platform          = "MOBILE",
        userAgent         = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 11) gzip",
        requiresSigCipher = false,
        requiresPoToken   = false,
        apiKey            = null
    ),

    /**
     * TV_EMBEDDED — للمحتوى المقيد بالعمر
     */
    TV_EMBEDDED(
        clientName        = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        clientVersion     = "2.0",
        androidSdkVersion = null,
        osVersion         = null,
        platform          = "TV",
        userAgent         = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 5.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/5.0 TV Safari/538.1",
        requiresSigCipher = true,
        requiresPoToken   = false,
        apiKey            = null
    ),

    /**
     * WEB client — fallback أخير فقط
     */
    WEB(
        clientName        = "WEB",
        clientVersion     = "2.20260114.08.00",
        androidSdkVersion = null,
        osVersion         = null,
        platform          = "DESKTOP",
        userAgent         = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        requiresSigCipher = true,
        requiresPoToken   = true,
        apiKey            = null
    );

    companion object {
        /**
         * FIX: ANDROID_VR added as second priority — أكثر استقراراً في 2026
         */
        val FALLBACK_CHAIN: List<InnerTubeClient> = listOf(
            ANDROID,
            ANDROID_VR,
            IOS,
            ANDROID_EMBEDDED,
            TV_EMBEDDED
        )
    }
}
