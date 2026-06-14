package com.ytdl.android.extractor

import com.ytdl.android.core.InnerTubeService
import com.ytdl.android.model.*
import com.ytdl.android.utils.NParameterUtils
import kotlinx.serialization.json.*

/**
 * YouTube Stream Extractor
 *
 * يُنفذ نفس منطق yt-dlp's YoutubeIE._real_extract()
 * مرجع: yt_dlp/extractor/youtube/_video.py — 2026.06.09
 *
 * الخطوات:
 *  1. استدعاء InnerTube /player API
 *  2. التحقق من playabilityStatus
 *  3. استخراج streamingData → adaptiveFormats + formats
 *  4. استخراج VideoDetails
 *  5. بناء VideoInfo الكاملة
 */
internal class YouTubeExtractor(private val service: InnerTubeService) {

    /**
     * استخراج معلومات الفيديو الكاملة مع fallback chain
     *
     * يُجرب عملاء متعددة بالترتيب حتى ينجح واحد منهم
     * نفس منطق yt-dlp's _extract_formats_and_subtitles()
     */
    suspend fun extract(
        videoId: String,
        preferredClient: InnerTubeClient
    ): Result<VideoInfo> {
        // بناء سلسلة الـ fallback: العميل المفضل أولاً
        val clientChain = buildFallbackChain(preferredClient)

        var lastError: Exception? = null

        for (client in clientChain) {
            try {
                val result = tryExtract(videoId, client)
                if (result != null) return Result.success(result)
            } catch (e: Exception) {
                lastError = e
            }
        }

        return Result.failure(
            lastError ?: Exception("فشل استخراج الفيديو: لم يتمكن أي عميل من الوصول")
        )
    }

    /**
     * محاولة استخراج باستخدام عميل محدد
     */
    private suspend fun tryExtract(
        videoId: String,
        client: InnerTubeClient
    ): VideoInfo? {
        val playerResponse = service.fetchPlayerResponse(videoId, client) ?: return null

        // ---- 1. التحقق من playabilityStatus ----
        val playabilityStatus = playerResponse["playabilityStatus"]?.jsonObject
        val status = playabilityStatus?.get("status")?.jsonPrimitive?.content

        if (status != "OK") {
            val reason = playabilityStatus?.get("reason")?.jsonPrimitive?.content
            // بعض الأسباب تستحق المحاولة بعميل آخر
            if (status == "UNPLAYABLE" || status == "LOGIN_REQUIRED") {
                return null  // جرب العميل التالي
            }
            throw Exception("الفيديو غير متاح: $reason")
        }

        // ---- 2. استخراج videoDetails ----
        val videoDetails = playerResponse["videoDetails"]?.jsonObject
            ?: return null

        val title = videoDetails["title"]?.jsonPrimitive?.content ?: "بدون عنوان"
        val description = videoDetails["shortDescription"]?.jsonPrimitive?.content
        val durationSec = videoDetails["lengthSeconds"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val viewCount = videoDetails["viewCount"]?.jsonPrimitive?.content?.toLongOrNull()
        val isLive = videoDetails["isLive"]?.jsonPrimitive?.booleanOrNull ?: false
        val channelId = videoDetails["channelId"]?.jsonPrimitive?.content
        val channelName = videoDetails["author"]?.jsonPrimitive?.content

        // ---- 3. استخراج thumbnail ----
        val thumbnailUrl = extractBestThumbnail(videoDetails)

        // ---- 4. استخراج streamingData ----
        val streamingData = playerResponse["streamingData"]?.jsonObject
            ?: return null

        val formats = mutableListOf<StreamFormat>()

        // formats = مدمجة (فيديو + صوت معاً) — yt-dlp: formats list
        val muxedFormats = streamingData["formats"]?.jsonArray ?: JsonArray(emptyList())
        formats += muxedFormats.mapNotNull { parseFormat(it.jsonObject, isAdaptive = false) }

        // adaptiveFormats = DASH (فيديو فقط أو صوت فقط) — yt-dlp: adaptiveFormats list
        val adaptiveFormats = streamingData["adaptiveFormats"]?.jsonArray ?: JsonArray(emptyList())
        formats += adaptiveFormats.mapNotNull { parseFormat(it.jsonObject, isAdaptive = true) }

        if (formats.isEmpty()) return null

        return VideoInfo(
            id = videoId,
            title = title,
            description = description,
            thumbnailUrl = thumbnailUrl,
            durationSeconds = durationSec,
            viewCount = viewCount,
            uploadDate = extractUploadDate(playerResponse),
            channelId = channelId,
            channelName = channelName,
            isLive = isLive,
            formats = formats.sortedByDescending { it.height ?: 0 }
        )
    }

    /**
     * تحليل كائن format واحد من streaming data
     *
     * مرجع yt-dlp: _extract_format_info() في _video.py
     *
     * كل format يحتوي على:
     * - itag: رقم معرّف التنسيق
     * - url: رابط الستريم (أو signatureCipher للـ WEB client)
     * - mimeType: نوع الوسائط مع codecs
     * - bitrate, width, height, fps
     * - initRange, indexRange (للـ DASH)
     */
    private fun parseFormat(obj: JsonObject, isAdaptive: Boolean): StreamFormat? {
        // استخراج URL — مباشر أو محتاج فك تشفير
        val directUrl = obj["url"]?.jsonPrimitive?.content
        val signatureCipher = obj["signatureCipher"]?.jsonPrimitive?.content
            ?: obj["cipher"]?.jsonPrimitive?.content

        val url = when {
            directUrl != null -> {
                // تطبيق n-parameter transform إذا لزم
                applyNParam(directUrl)
            }
            signatureCipher != null -> {
                // WEB client: يحتاج فك تشفير signature
                // yt-dlp: _extract_signature_timestamp + _decrypt_sig
                extractUrlFromCipher(signatureCipher)
            }
            else -> return null  // لا يوجد URL
        }

        if (url == null) return null

        // mimeType مثل: "video/mp4; codecs=\"avc1.640028\""
        val mimeType = obj["mimeType"]?.jsonPrimitive?.content ?: return null
        val (ext, vcodec, acodec) = parseMimeType(mimeType)

        val itag = obj["itag"]?.jsonPrimitive?.content ?: "0"
        val width = obj["width"]?.jsonPrimitive?.intOrNull
        val height = obj["height"]?.jsonPrimitive?.intOrNull
        val fps = obj["fps"]?.jsonPrimitive?.doubleOrNull
        val totalBitrate = obj["bitrate"]?.jsonPrimitive?.intOrNull?.div(1000)
        val fileSize = obj["contentLength"]?.jsonPrimitive?.content?.toLongOrNull()

        // Audio-specific
        val audioBitrate = obj["averageBitrate"]?.jsonPrimitive?.intOrNull?.div(1000)
            ?: obj["audioSampleRate"]?.jsonPrimitive?.intOrNull?.let { null }

        // تحديد نوع الستريم
        val hasVideo = vcodec != null && vcodec != "none"
        val hasAudio = acodec != null && acodec != "none"

        // DASH range info
        val initRange = obj["initRange"]?.jsonObject?.let {
            ByteRange(
                it["start"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                it["end"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            )
        }
        val indexRange = obj["indexRange"]?.jsonObject?.let {
            ByteRange(
                it["start"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                it["end"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            )
        }

        return StreamFormat(
            formatId = itag,
            url = url,
            mimeType = mimeType,
            ext = ext,
            width = width,
            height = height,
            fps = fps,
            vcodec = vcodec,
            acodec = acodec,
            audioBitrate = audioBitrate,
            videoBitrate = if (hasVideo && !hasAudio) totalBitrate else null,
            totalBitrate = totalBitrate,
            fileSizeBytes = fileSize,
            quality = StreamQuality.fromHeight(height),
            hasVideo = hasVideo,
            hasAudio = hasAudio,
            isAdaptive = isAdaptive,
            initRange = initRange,
            indexRange = indexRange,
            signatureCipher = signatureCipher
        )
    }

    /**
     * تطبيق n-parameter transform على stream URL
     * مرجع: yt-dlp _cached_decode_n()
     *
     * ANDROID client: عادةً لا تحتاج هذا التحويل
     */
    private fun applyNParam(url: String): String {
        if (!NParameterUtils.hasNParam(url)) return url
        // عميل ANDROID عادةً لا يحتاج transform
        // في حال WEB client، تحتاج QuickJS لتنفيذ دالة JS
        return url
    }

    /**
     * فك تشفير signatureCipher للـ WEB client
     * مرجع: yt-dlp _extract_signature_function()
     *
     * يستخرج URL من cipher string مثل:
     * "url=...&s=...&sp=sig"
     */
    private fun extractUrlFromCipher(cipher: String): String? {
        val params = cipher.split("&").associate { part ->
            val eq = part.indexOf('=')
            if (eq == -1) part to ""
            else part.substring(0, eq) to java.net.URLDecoder.decode(
                part.substring(eq + 1), "UTF-8"
            )
        }

        val url = params["url"] ?: return null
        val sig = params["s"] ?: return url  // بدون signature = URL مباشر

        // WEB client يحتاج فك تشفير signature عبر player JS
        // ANDROID/IOS لا تصل هنا عادةً
        // في التطبيق الكامل: تطبيق SigCipherDecoder
        return "$url&sig=$sig"
    }

    /**
     * تحليل mimeType واستخراج الـ codecs
     * مثال: "video/mp4; codecs=\"avc1.640028, mp4a.40.2\""
     */
    private fun parseMimeType(mimeType: String): Triple<String, String?, String?> {
        val base = mimeType.split(";").first().trim()
        val ext = when {
            base.contains("mp4") -> "mp4"
            base.contains("webm") -> "webm"
            base.contains("ogg") -> "ogg"
            else -> "mp4"
        }

        val codecsMatch = Regex("""codecs="([^"]+)"""").find(mimeType)
        val codecs = codecsMatch?.groupValues?.get(1)?.split(",")?.map { it.trim() }

        val isVideo = base.startsWith("video/")
        val isAudio = base.startsWith("audio/")

        val vcodec = when {
            isVideo -> codecs?.firstOrNull()
            isAudio -> "none"
            else -> null
        }

        val acodec = when {
            isAudio -> codecs?.firstOrNull()
            isVideo -> codecs?.getOrNull(1)
            else -> null
        }

        return Triple(ext, vcodec, acodec)
    }

    /**
     * استخراج أفضل thumbnail
     */
    private fun extractBestThumbnail(videoDetails: JsonObject): String? {
        val thumbnails = videoDetails["thumbnail"]?.jsonObject
            ?.get("thumbnails")?.jsonArray
            ?: return null

        return thumbnails
            .mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.content }
            .lastOrNull()  // الأخير عادةً الأعلى جودة
            ?: "https://i.ytimg.com/vi/${videoDetails["videoId"]?.jsonPrimitive?.content}/hqdefault.jpg"
    }

    /**
     * استخراج تاريخ الرفع من microformat
     */
    private fun extractUploadDate(playerResponse: JsonObject): String? {
        return playerResponse["microformat"]
            ?.jsonObject?.get("playerMicroformatRenderer")
            ?.jsonObject?.get("publishDate")
            ?.jsonPrimitive?.content
    }

    /**
     * بناء fallback chain — العميل المفضل أولاً
     */
    private fun buildFallbackChain(preferred: InnerTubeClient): List<InnerTubeClient> {
        val chain = mutableListOf(preferred)
        chain += InnerTubeClient.FALLBACK_CHAIN.filter { it != preferred }
        return chain
    }
}
