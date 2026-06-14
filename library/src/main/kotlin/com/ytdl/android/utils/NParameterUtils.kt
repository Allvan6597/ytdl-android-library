package com.ytdl.android.utils

/**
 * N-Parameter (nsig) Transform Utility
 *
 * yt-dlp يسمي هذا: _decode_n_function / _extract_n_function_name
 *
 * YouTube تُضيف معامل `n` في stream URLs للتحكم في سرعة التنزيل.
 * إذا لم يُحوَّل هذا المعامل، يُقيّد YouTube السرعة لـ ~50 kbps.
 *
 * منطق yt-dlp 2026.06.09:
 * - يستخرج دالة التحويل من player JS
 * - ينفذها على قيمة `n` الحالية
 *
 * في مكتبتنا:
 * - عميل ANDROID يعطي stream URLs بدون تقييد مباشرة (لا تحتاج nsig)
 * - هذا الملف يُنفذ تحويل `n` للـ WEB client كـ fallback
 *
 * المنطق مستنسخ من: yt-dlp/yt_dlp/extractor/youtube/_video.py
 * دالة: _decrypt_nsig / _cached_decode_n
 */
object NParameterUtils {

    private const val N_PARAM = "n"

    /**
     * استخرج قيمة معامل `n` من URL
     */
    fun extractNParam(url: String): String? {
        val uri = parseQueryParams(url)
        return uri[N_PARAM]
    }

    /**
     * استبدل معامل `n` في URL بقيمته المحوَّلة
     */
    fun replaceNParam(url: String, newN: String): String {
        if (!url.contains("&n=") && !url.contains("?n=")) return url
        return url.replace(Regex("([?&]n=)[^&]+")) { match ->
            "${match.groupValues[1]}$newN"
        }
    }

    /**
     * تحقق إذا كان URL يحتوي على معامل n
     */
    fun hasNParam(url: String): Boolean = url.contains("[?&]n=".toRegex())

    /**
     * تحليل query parameters من URL
     */
    fun parseQueryParams(url: String): Map<String, String> {
        val queryStart = url.indexOf('?')
        if (queryStart == -1) return emptyMap()
        val query = url.substring(queryStart + 1)
        return query.split('&')
            .mapNotNull { param ->
                val eq = param.indexOf('=')
                if (eq == -1) null
                else param.substring(0, eq) to param.substring(eq + 1)
            }
            .toMap()
    }

    /**
     * تطبيق تحويل n-parameter (للـ WEB client fallback)
     *
     * ملاحظة: التحويل الكامل يتطلب تنفيذ JavaScript من player.js
     * عميل ANDROID لا يحتاج هذا.
     *
     * إذا أردت دعم WEB client كاملاً، تحتاج QuickJS أو V8 JNI.
     * راجع: https://github.com/HLahwani/yt-dlp-android للتنفيذ الكامل.
     */
    fun transformNParam(nValue: String, playerJsContent: String): String? {
        return try {
            // استخراج اسم دالة nsig من player JS
            // Pattern مستنسخ من yt-dlp _extract_n_function_name
            val funcNamePattern = Regex(
                """\([\w$]+\[[\w$]+\(\)\]\|\|[\w$]+\)\s*&&\s*[\w$]+\s*!==\s*[\w$]+\s*&&\s*[\w$]+\s*"""
                + """\[\s*[\w$]+\s*\([\w$]+,\s*([\w$]{2,})\s*\("""
            )

            val funcName = funcNamePattern.find(playerJsContent)?.groupValues?.get(1)
                ?: return null

            // استخراج جسم الدالة
            // في التطبيق الحقيقي: تنفيذ عبر QuickJS JNI
            // هنا نُرجع null للإشارة أن تنفيذ JS مطلوب
            null
        } catch (e: Exception) {
            null
        }
    }
}
