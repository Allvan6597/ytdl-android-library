package com.ytdl.android.model

/**
 * InnerTube Client Configurations
 * مرجع: yt-dlp INNERTUBE_CLIENTS — 2026.06.09
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
    val apiKey: String?,
    /** إذا كان true: لا تُرسل أي context fields إضافية */
    val isMinimalContext: Boolean = false
) {

    /**
     * ANDROID_TESTSUITE — الأول في 2026
     *
     * السبب: YouTube لا يُطبّق PO Token gating على هذا العميل
     * Context بسيط جداً — بدون osName/deviceMake/androidSdkVersion
     * مرجع yt-dlp: client ID 30, INNERTUBE_CLIENTS['ANDROID_TESTSUITE']
     */
    ANDROID_TESTSUITE(
        clientName        = "ANDROID_TESTSUITE",
        clientVersion     = "1.9",
        androidSdkVersion = null,   // مهم: لا ترسل هذا
        osVersion         = null,
        platform          = "MOBILE",
        userAgent         = "com.google.android.youtube/1.9 (Linux; U; Android 6.0; Nexus 5 Build/MRA58N) gzip",
        requiresSigCipher = false,
        requiresPoToken   = false,
        apiKey            = null,
        isMinimalContext  = true    // context بسيط فقط clientName+clientVersion
    ),

    /**
     * ANDROID — fallback موثوق
     * version 19.44.38 per yt-dlp 2026.06.09
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
     * ANDROID_VR — bypass client مثبت في yt-dlp 2026
     * client ID: 28
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
     * IOS — بديل جيد
     * version 19.45.4 per yt-dlp 2026.06.09
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
     * ANDROID_EMBEDDED — للمحتوى المقيد
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
     * WEB — آخر fallback للمحتوى المحجوب جغرافياً
     * يُرجع streamingData أحياناً عندما تفشل كل العملاء الأخرى
     * مرجع yt-dlp: client ID 1
     */
    WEB(
        clientName        = "WEB",
        clientVersion     = "2.20250101.00.00",
        androidSdkVersion = null,
        osVersion         = null,
        platform          = "DESKTOP",
        userAgent         = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        requiresSigCipher = true,
        requiresPoToken   = true,   // WEB يحتاج PO Token في الغالب — لكن نجرّبه كـ last resort
        apiKey            = null
    );

    companion object {
        /**
         * Fallback chain 2026:
         * ANDROID_TESTSUITE أولاً — يتجاوز PO Token gating
         * WEB أخيراً — last resort للمحتوى المحجوب جغرافياً
         */
        val FALLBACK_CHAIN: List<InnerTubeClient> = listOf(
            ANDROID_TESTSUITE,
            ANDROID,
            ANDROID_VR,
            IOS,
            ANDROID_EMBEDDED,
            TV_EMBEDDED,
            WEB
        )
    }
}
