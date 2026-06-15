package com.ytdl.android.model

/**
 * InnerTube Client Configurations
 *
 * الوضع الحالي في يونيو 2026:
 *
 * ❌ ANDROID_TESTSUITE (1.9) — مات. YouTube بدأ يُطبّق PO Token gating عليه
 *    منذ مايو 2026 — هذا هو سبب خطأ "no streamingData" الذي تراه
 *
 * ✅ TV (TVHTML5) — الأقوى حالياً. لا يحتاج PO Token ولا signatureCipher
 *    yt-dlp يستخدمه كأول اختيار في 2026.06.x
 *    client ID: 7، version: 2.0
 *
 * ✅ ANDROID_VR — لا يزال يعمل للفيديوهات العادية
 *    تم تخفيض إصدار User-Agent في yt-dlp 2026.03.10 لتجنب مشكلة 360p
 *
 * ✅ IOS — يعمل مع PO Token، بدونه قد يُرجع 403 على بعض الفيديوهات
 *    xt-dlp الافتراضي الحالي: tv,ios,web
 *
 * ✅ MWEB — موبايل ويب، يعمل مع PO Token
 *
 * ❌ WEB — SABR-only في معظم الحالات، يُعيد URLs فارغة
 *
 * مرجع: yt-dlp 2026.06.09، issues #15712، #15780، #16150
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
    val isMinimalContext: Boolean = false
) {

    /**
     * TV (TVHTML5) — الأقوى في 2026.06
     *
     * لماذا ينجح:
     * - YouTube يعامله كـ Smart TV app → لا botGuard ولا PO Token
     * - يُعطي direct URLs مباشرة بدون signatureCipher
     * - yt-dlp يستخدمه كأول عميل في default chain منذ 2026.03
     *
     * yt-dlp client ID: 7
     */
    TV(
        clientName        = "TVHTML5",
        clientVersion     = "2.0",
        androidSdkVersion = null,
        osVersion         = null,
        platform          = "TV",
        userAgent         = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/6.0 TV Safari/538.1",
        requiresSigCipher = false,   // TV client يُرجع direct URLs
        requiresPoToken   = false,
        apiKey            = null
    ),

    /**
     * ANDROID_VR — موثوق للفيديوهات العادية
     *
     * تم تخفيض إصدار User-Agent في yt-dlp 2026.03.10 من 1.60.19 إلى 1.56.21
     * لتجنب مشكلة إرجاع 360p فقط (issue #16150)
     *
     * yt-dlp client ID: 28
     */
    ANDROID_VR(
        clientName        = "ANDROID_VR",
        clientVersion     = "1.56.21",   // مخفّض من 1.60.19 — fix yt-dlp #16150
        androidSdkVersion = 29,           // Android 10 بدلاً من 11
        osVersion         = "10",
        platform          = "MOBILE",
        userAgent         = "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 10; Build/QQ3A.200805.001) gzip",
        requiresSigCipher = false,
        requiresPoToken   = false,
        apiKey            = null
    ),

    /**
     * IOS — يعمل بشكل جيد في معظم الحالات
     *
     * version محدثة لـ 2026.06.09 من yt-dlp
     * yt-dlp client ID: 5
     */
    IOS(
        clientName        = "IOS",
        clientVersion     = "19.45.4",
        androidSdkVersion = null,
        osVersion         = "17.7.2.21H221",
        platform          = "MOBILE",
        userAgent         = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 17_7_2 like Mac OS X)",
        requiresSigCipher = false,
        requiresPoToken   = false,   // بدون cookies يعمل في الغالب
        apiKey            = null
    ),

    /**
     * ANDROID — fallback
     *
     * version محدثة 2026.06.09
     * قد يُرجع SABR formats أحياناً لكن لا يزال يعمل
     * yt-dlp client ID: 3
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
     * ANDROID_TESTSUITE — ميت في 2026.06
     *
     * كان يعمل حتى مايو 2026، لكن YouTube أضاف PO Token gating عليه.
     * المبقية هنا فقط كـ last-resort — قد يعود للعمل مستقبلاً.
     * yt-dlp client ID: 30
     */
    ANDROID_TESTSUITE(
        clientName        = "ANDROID_TESTSUITE",
        clientVersion     = "1.9",
        androidSdkVersion = null,
        osVersion         = null,
        platform          = "MOBILE",
        userAgent         = "com.google.android.youtube/1.9 (Linux; U; Android 6.0; Nexus 5 Build/MRA58N) gzip",
        requiresSigCipher = false,
        requiresPoToken   = false,
        apiKey            = null,
        isMinimalContext  = true
    ),

    /**
     * TV_EMBEDDED — للمحتوى المقيد بالعمر (age-gated bypass)
     *
     * يحتاج signatureCipher ولكنه يتجاوز قيود العمر
     * yt-dlp client ID: 85
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
     * WEB — last resort فقط
     *
     * معظم formats تُرجع SABR (بدون URL مباشر) في 2026.
     * نُضيفه كـ fallback أخير لكنه نادراً ما ينجح بدون PO Token.
     * yt-dlp client ID: 1
     */
    WEB(
        clientName        = "WEB",
        clientVersion     = "2.20250101.00.00",
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
         * Fallback chain — يونيو 2026
         *
         * الترتيب مبني على yt-dlp 2026.06.x default chain:
         *   tv, ios, web (الافتراضي الحالي)
         *
         * لكننا نُضيف android_vr قبل ios لأنه لا يحتاج JS execution،
         * وnبدّل ANDROID_TESTSUITE إلى آخر الترتيب لأنه مات.
         *
         * TV → ANDROID_VR → IOS → ANDROID → TV_EMBEDDED → ANDROID_TESTSUITE → WEB
         */
        val FALLBACK_CHAIN: List<InnerTubeClient> = listOf(
            TV,
            ANDROID_VR,
            IOS,
            ANDROID,
            TV_EMBEDDED,
            ANDROID_TESTSUITE,  // آخر الترتيب — PO Token gating
            WEB                 // SABR-only في الغالب
        )
    }
}
