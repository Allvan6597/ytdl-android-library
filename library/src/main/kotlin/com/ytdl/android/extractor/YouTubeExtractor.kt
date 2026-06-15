package com.ytdl.android.extractor

import android.util.Log
import com.ytdl.android.core.InnerTubeService
import com.ytdl.android.model.*
import com.ytdl.android.utils.NParameterUtils
import kotlinx.serialization.json.*

/**
 * YouTube Stream Extractor — مُصلَح 2026.06
 *
 * الإصلاحات:
 *
 * 1. TV client: يُرجع "LOGIN_REQUIRED" أحياناً حتى للفيديوهات العامة
 *    (في الإصدار القديم كان يكسر الـ fallback chain)
 *    الحل: تجاهل LOGIN_REQUIRED فقط إذا كان العميل غير TV_EMBEDDED
 *    وإعادة المحاولة مع TV_EMBEDDED
 *
 * 2. SABR Detection: إذا كانت formats موجودة لكن جميعها بدون URL
 *    (YouTube يُرجع SABR formats مع URLs فارغة)
 *    الحل: تخطي هذا العميل بدلاً من الفشل الصامت
 *
 * 3. شرط الفشل: كان "This video is unavailable" يوقف الـ chain كلها
 *    بعض العملاء تُرجع هذا لكن عميل آخر ينجح (حجب جغرافي)
 *    الحل: وقف الـ chain فقط عند "Private video" أو "removed"
 *
 * 4. extractUrlFromCipher: كان يُضيف sig مباشرة بدون URL encoding
 *    بعض signatures تحتوي على أحرف خاصة تحتاج encoding
 */
internal class YouTubeExtractor(
    private val service: InnerTubeService,
    private val enableLogging: Boolean = false
) {

    suspend fun extract(
        videoId: String,
        preferredClient: InnerTubeClient
    ): Result<VideoInfo> {
        val clientChain = buildFallbackChain(preferredClient)
        val errors = mutableListOf<String>()

        for (client in clientChain) {
            try {
                val result = tryExtract(videoId, client)
                if (result != null) {
                    if (enableLogging) Log.d(TAG, "SUCCESS with ${client.clientName}")
                    return Result.success(result)
                }
                errors.add("${client.clientName}: no streamingData (PO Token gating or unavailable)")
            } catch (e: SabrOnlyException) {
                // إصلاح #2: SABR client — تخطى بصمت، جرّب التالي
                errors.add("${client.clientName}: SABR-only (no direct URLs)")
                if (enableLogging) Log.w(TAG, "${client.clientName}: SABR-only formats detected, skipping")
            } catch (e: Exception) {
                val msg = e.message ?: "unknown error"
                errors.add("${client.clientName}: $msg")

                // إصلاح #3: وقف الـ chain فقط عند "Private video" أو "removed"
                // "video unavailable" قد يكون حجب جغرافي فقط — عميل آخر قد ينجح
                if (msg.contains("Private video", ignoreCase = true) ||
                    msg.contains("This video has been removed", ignoreCase = true) ||
                    msg.contains("video deleted", ignoreCase = true)) {
                    break  // فيديو محذوف أو خاص — لا جدوى من المحاولة
                }
                // في جميع الحالات الأخرى: استمر إلى العميل التالي
            }
        }

        val errorSummary = errors.joinToString("\n") { "  • $it" }
        return Result.failure(
            Exception("فشل استخراج الفيديو:\n$errorSummary")
        )
    }

    private suspend fun tryExtract(videoId: String, client: InnerTubeClient): VideoInfo? {
        val playerResponse = service.fetchPlayerResponse(videoId, client) ?: run {
            if (enableLogging) Log.w(TAG, "${client.clientName}: HTTP request returned null")
            return null
        }

        // ---- playabilityStatus ----
        val playabilityStatus = playerResponse["playabilityStatus"]?.jsonObject
        val status = playabilityStatus?.get("status")?.jsonPrimitive?.content
        val reason = playabilityStatus?.get("reason")?.jsonPrimitive?.content

        if (enableLogging) Log.d(TAG, "${client.clientName}: status=$status reason=$reason")

        when (status) {
            "OK"  -> { /* proceed */ }
            "LOGIN_REQUIRED" -> {
                // إصلاح #1: TV_EMBEDDED فقط يمكنه تجاوز age-gating
                // TV (العادي) قد يُرجع LOGIN_REQUIRED خطأً أحياناً → جرّب عميل آخر
                if (client == InnerTubeClient.TV_EMBEDDED) {
                    // TV_EMBEDDED مع LOGIN_REQUIRED → محتوى محمي ولا يمكن تجاوزه بدون تسجيل دخول
                    throw Exception("المحتوى يتطلب تسجيل دخول: ${reason ?: "LOGIN_REQUIRED"}")
                }
                // بقية العملاء → جرّب التالي في الـ chain
                return null
            }
            "AGE_VERIFICATION_REQUIRED", "AGE_CHECK_REQUIRED" -> {
                // هذه الحالة يتجاوزها TV_EMBEDDED — إذا وصلنا هنا من عميل آخر فجرّب التالي
                if (enableLogging) Log.d(TAG, "${client.clientName}: age restriction, next client may bypass")
                return null
            }
            "UNPLAYABLE" -> throw Exception("الفيديو غير متاح: ${reason ?: "UNPLAYABLE"}")
            "ERROR"      -> throw Exception("خطأ YouTube: ${reason ?: "ERROR"}")
            null         -> return null
            else         -> {
                if (enableLogging) Log.w(TAG, "${client.clientName}: unknown status=$status")
                return null
            }
        }

        // ---- videoDetails ----
        val videoDetails = playerResponse["videoDetails"]?.jsonObject ?: run {
            if (enableLogging) Log.w(TAG, "${client.clientName}: no videoDetails")
            return null
        }

        // ---- streamingData ----
        val streamingData = playerResponse["streamingData"]?.jsonObject ?: run {
            if (enableLogging) Log.w(TAG,
                "${client.clientName}: playabilityStatus=OK but streamingData ABSENT — PO Token gating")
            return null
        }

        // ---- parse formats ----
        val formats = mutableListOf<StreamFormat>()

        val muxedFormats = streamingData["formats"]?.jsonArray ?: JsonArray(emptyList())
        formats += muxedFormats.mapNotNull {
            runCatching { parseFormat(it.jsonObject, isAdaptive = false) }.getOrNull()
        }

        val adaptiveFormats = streamingData["adaptiveFormats"]?.jsonArray ?: JsonArray(emptyList())
        formats += adaptiveFormats.mapNotNull {
            runCatching { parseFormat(it.jsonObject, isAdaptive = true) }.getOrNull()
        }

        // إصلاح #2: SABR detection
        // إذا كانت streamingData موجودة لكن جميع formats بدون URL
        // هذا يعني YouTube يُرجع SABR formats (لا تعمل مع تنزيل مباشر)
        if (formats.isEmpty()) {
            val totalFormatCount = muxedFormats.size + adaptiveFormats.size
            if (totalFormatCount > 0) {
                // كانت هناك formats لكن جميعها فشلت في الـ parse (SABR أو encrypted)
                if (enableLogging) Log.w(TAG,
                    "${client.clientName}: $totalFormatCount formats found but all lack direct URLs (SABR?)")
                throw SabrOnlyException("${client.clientName}: $totalFormatCount SABR/encrypted formats, no direct URLs")
            }
            if (enableLogging) Log.w(TAG, "${client.clientName}: streamingData present but formats array empty")
            return null
        }

        // ---- build VideoInfo ----
        if (enableLogging) Log.d(TAG, "${client.clientName}: extracted ${formats.size} usable formats")

        return VideoInfo(
            id              = videoId,
            title           = videoDetails["title"]?.jsonPrimitive?.content ?: "بدون عنوان",
            description     = videoDetails["shortDescription"]?.jsonPrimitive?.content,
            thumbnailUrl    = extractBestThumbnail(videoDetails),
            durationSeconds = videoDetails["lengthSeconds"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            viewCount       = videoDetails["viewCount"]?.jsonPrimitive?.content?.toLongOrNull(),
            uploadDate      = extractUploadDate(playerResponse),
            channelId       = videoDetails["channelId"]?.jsonPrimitive?.content,
            channelName     = videoDetails["author"]?.jsonPrimitive?.content,
            isLive          = videoDetails["isLive"]?.jsonPrimitive?.booleanOrNull ?: false,
            formats         = formats.sortedByDescending { it.height ?: 0 }
        )
    }

    private fun parseFormat(obj: JsonObject, isAdaptive: Boolean): StreamFormat? {
        val directUrl       = obj["url"]?.jsonPrimitive?.content
        val signatureCipher = obj["signatureCipher"]?.jsonPrimitive?.content
            ?: obj["cipher"]?.jsonPrimitive?.content

        val url = when {
            directUrl != null -> {
                // تحقق أن الـ URL صالح وليس SABR placeholder
                if (directUrl.isBlank() || directUrl.startsWith("sabr://")) return null
                applyNParam(directUrl)
            }
            signatureCipher != null -> extractUrlFromCipher(signatureCipher)
            else -> return null  // لا URL ولا cipher → SABR format
        } ?: return null

        val mimeType = obj["mimeType"]?.jsonPrimitive?.content ?: return null
        val (ext, vcodec, acodec) = parseMimeType(mimeType)

        val height   = obj["height"]?.jsonPrimitive?.intOrNull
        val hasVideo = vcodec != null && vcodec != "none"
        val hasAudio = acodec != null && acodec != "none"

        val totalBitrateBps  = obj["bitrate"]?.jsonPrimitive?.intOrNull
        val totalBitrateKbps = totalBitrateBps?.div(1000)

        val audioBitrate = if (!hasVideo || hasAudio) {
            obj["averageBitrate"]?.jsonPrimitive?.intOrNull?.div(1000)
                ?: totalBitrateKbps
        } else null

        return StreamFormat(
            formatId       = obj["itag"]?.jsonPrimitive?.content ?: "0",
            url            = url,
            mimeType       = mimeType,
            ext            = ext,
            width          = obj["width"]?.jsonPrimitive?.intOrNull,
            height         = height,
            fps            = obj["fps"]?.jsonPrimitive?.doubleOrNull,
            vcodec         = vcodec,
            acodec         = acodec,
            audioBitrate   = audioBitrate,
            videoBitrate   = if (hasVideo && !hasAudio) totalBitrateKbps else null,
            totalBitrate   = totalBitrateKbps,
            fileSizeBytes  = obj["contentLength"]?.jsonPrimitive?.content?.toLongOrNull(),
            quality        = StreamQuality.fromHeight(height),
            hasVideo       = hasVideo,
            hasAudio       = hasAudio,
            isAdaptive     = isAdaptive,
            initRange      = parseByteRange(obj["initRange"]?.jsonObject),
            indexRange     = parseByteRange(obj["indexRange"]?.jsonObject),
            signatureCipher = signatureCipher
        )
    }

    private fun applyNParam(url: String): String {
        // N-parameter transform — TV و ANDROID_VR لا يحتاجانه
        // yt-dlp ينفذه عبر JavaScript (QuickJS/deno/node)
        // بدون تحويل nsig: السرعة محدودة إلى ~50kbps من YouTube
        // TV client يُرجع URLs بدون قيود nsig في الغالب
        if (!NParameterUtils.hasNParam(url)) return url
        return url  // إرجاع URL كما هو — nsig transform يتطلب JS engine
    }

    /**
     * إصلاح #4: extractUrlFromCipher مع URL encoding صحيح للـ signature
     *
     * YouTube يُرجع signature محتوية على أحرف مثل = و + و /
     * يجب re-encoding هذه الأحرف قبل إضافتها إلى URL
     */
    private fun extractUrlFromCipher(cipher: String): String? {
        val params = cipher.split("&").associate { part ->
            val eq = part.indexOf('=')
            if (eq == -1) part to ""
            else part.substring(0, eq) to
                    runCatching {
                        java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8")
                    }.getOrDefault(part.substring(eq + 1))
        }

        val url = params["url"] ?: return null
        val sig = params["s"]   ?: return url  // بعض العملاء تُرجع URL مباشر

        // إصلاح #4: re-encode الـ signature لأنها قد تحتوي أحرف خاصة
        val encodedSig = try {
            java.net.URLEncoder.encode(sig, "UTF-8")
                .replace("+", "%20")  // URL encoding standards: spaces as %20
        } catch (e: Exception) {
            sig  // fallback للـ raw value
        }

        // TV_EMBEDDED يستخدم sp parameter لاسم الـ signature parameter
        val sigParamName = params["sp"] ?: "sig"

        return "$url&$sigParamName=$encodedSig"
    }

    private fun parseMimeType(mimeType: String): Triple<String, String?, String?> {
        val base = mimeType.split(";").first().trim().lowercase()

        val ext = when {
            base.contains("mp4")  -> "mp4"
            base.contains("webm") -> "webm"
            base.contains("ogg")  -> "ogg"
            else                  -> "mp4"
        }

        val codecsStr = Regex("""codecs=["']?([^"';,\s][^"';]*)["']?""")
            .find(mimeType)?.groupValues?.get(1)

        val codecs = codecsStr
            ?.split(",")
            ?.map { it.trim().trim('"').trim('\'') }
            ?: emptyList()

        val isVideo = base.startsWith("video/")
        val isAudio = base.startsWith("audio/")

        val vcodec: String? = when {
            isVideo -> codecs.firstOrNull()
            isAudio -> "none"
            else    -> null
        }

        val acodec: String? = when {
            isAudio                     -> codecs.firstOrNull()
            isVideo && codecs.size >= 2 -> codecs[1]
            isVideo                     -> null
            else                        -> null
        }

        return Triple(ext, vcodec, acodec)
    }

    private fun parseByteRange(obj: JsonObject?): ByteRange? {
        obj ?: return null
        return ByteRange(
            start = obj["start"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            end   = obj["end"]?.jsonPrimitive?.content?.toLongOrNull()   ?: 0L
        )
    }

    private fun extractBestThumbnail(videoDetails: JsonObject): String? {
        return videoDetails["thumbnail"]?.jsonObject
            ?.get("thumbnails")?.jsonArray
            ?.mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.content }
            ?.lastOrNull()
            ?: videoDetails["videoId"]?.jsonPrimitive?.content
                ?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
    }

    private fun extractUploadDate(playerResponse: JsonObject): String? {
        return playerResponse["microformat"]
            ?.jsonObject?.get("playerMicroformatRenderer")
            ?.jsonObject?.get("publishDate")
            ?.jsonPrimitive?.content
    }

    private fun buildFallbackChain(preferred: InnerTubeClient): List<InnerTubeClient> {
        val chain = mutableListOf(preferred)
        chain += InnerTubeClient.FALLBACK_CHAIN.filter { it != preferred }
        return chain
    }

    /** استثناء خاص لـ SABR-only formats — يُميّزها عن الأخطاء الحقيقية */
    private class SabrOnlyException(message: String) : Exception(message)

    companion object {
        private const val TAG = "YTDLAndroid"
    }
}
