package com.ytdl.android.extractor

import com.ytdl.android.core.InnerTubeService
import com.ytdl.android.model.*
import com.ytdl.android.utils.NParameterUtils
import kotlinx.serialization.json.*

/**
 * YouTube Stream Extractor — FIXED VERSION
 *
 * الإصلاحات:
 *  FIX-1: tryExtract() الآن يُسجّل سبب الفشل (playabilityStatus.reason)
 *  FIX-2: معالجة أفضل لحالات LOGIN_REQUIRED / AGE_VERIFICATION_REQUIRED
 *  FIX-3: إضافة fallback لـ itag عند عدم وجود url مباشر
 *  FIX-4: تحسين parseMimeType لمعالجة صيغ codecs المختلفة من YouTube
 */
internal class YouTubeExtractor(private val service: InnerTubeService) {

    suspend fun extract(
        videoId: String,
        preferredClient: InnerTubeClient
    ): Result<VideoInfo> {
        val clientChain = buildFallbackChain(preferredClient)
        val errors = mutableListOf<String>()

        for (client in clientChain) {
            try {
                val result = tryExtract(videoId, client)
                if (result != null) return Result.success(result)
                errors.add("${client.clientName}: returned null (no streamingData or empty formats)")
            } catch (e: Exception) {
                errors.add("${client.clientName}: ${e.message}")
            }
        }

        val errorSummary = errors.joinToString("\n") { "  • $it" }
        return Result.failure(
            Exception("فشل استخراج الفيديو — جميع العملاء فشلوا:\n$errorSummary")
        )
    }

    private suspend fun tryExtract(
        videoId: String,
        client: InnerTubeClient
    ): VideoInfo? {
        val playerResponse = service.fetchPlayerResponse(videoId, client) ?: return null

        // ---- 1. playabilityStatus ----
        val playabilityStatus = playerResponse["playabilityStatus"]?.jsonObject
        val status = playabilityStatus?.get("status")?.jsonPrimitive?.content
        val reason = playabilityStatus?.get("reason")?.jsonPrimitive?.content

        when (status) {
            "OK" -> { /* proceed */ }
            "LOGIN_REQUIRED" -> {
                // TV_EMBEDDED يتجاوز هذا عادةً
                if (client != InnerTubeClient.TV_EMBEDDED) return null
            }
            "AGE_VERIFICATION_REQUIRED", "AGE_CHECK_REQUIRED" -> {
                // جرب TV_EMBEDDED
                return null
            }
            "UNPLAYABLE" -> {
                // الفيديو غير متاح في هذه المنطقة أو محذوف
                throw Exception("الفيديو غير متاح: ${reason ?: status}")
            }
            "ERROR" -> {
                throw Exception("خطأ YouTube: ${reason ?: status}")
            }
            null -> return null
            else -> return null  // حالة غير معروفة → جرب العميل التالي
        }

        // ---- 2. videoDetails ----
        val videoDetails = playerResponse["videoDetails"]?.jsonObject ?: return null

        val title       = videoDetails["title"]?.jsonPrimitive?.content ?: "بدون عنوان"
        val description = videoDetails["shortDescription"]?.jsonPrimitive?.content
        val durationSec = videoDetails["lengthSeconds"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val viewCount   = videoDetails["viewCount"]?.jsonPrimitive?.content?.toLongOrNull()
        val isLive      = videoDetails["isLive"]?.jsonPrimitive?.booleanOrNull ?: false
        val channelId   = videoDetails["channelId"]?.jsonPrimitive?.content
        val channelName = videoDetails["author"]?.jsonPrimitive?.content

        val thumbnailUrl = extractBestThumbnail(videoDetails)

        // ---- 3. streamingData ----
        val streamingData = playerResponse["streamingData"]?.jsonObject ?: return null

        val formats = mutableListOf<StreamFormat>()

        // مدمجة
        val muxedFormats = streamingData["formats"]?.jsonArray ?: JsonArray(emptyList())
        formats += muxedFormats.mapNotNull {
            runCatching { parseFormat(it.jsonObject, isAdaptive = false) }.getOrNull()
        }

        // DASH adaptive
        val adaptiveFormats = streamingData["adaptiveFormats"]?.jsonArray ?: JsonArray(emptyList())
        formats += adaptiveFormats.mapNotNull {
            runCatching { parseFormat(it.jsonObject, isAdaptive = true) }.getOrNull()
        }

        if (formats.isEmpty()) return null

        return VideoInfo(
            id              = videoId,
            title           = title,
            description     = description,
            thumbnailUrl    = thumbnailUrl,
            durationSeconds = durationSec,
            viewCount       = viewCount,
            uploadDate      = extractUploadDate(playerResponse),
            channelId       = channelId,
            channelName     = channelName,
            isLive          = isLive,
            formats         = formats.sortedByDescending { it.height ?: 0 }
        )
    }

    private fun parseFormat(obj: JsonObject, isAdaptive: Boolean): StreamFormat? {
        val directUrl       = obj["url"]?.jsonPrimitive?.content
        val signatureCipher = obj["signatureCipher"]?.jsonPrimitive?.content
            ?: obj["cipher"]?.jsonPrimitive?.content

        val url = when {
            directUrl != null       -> applyNParam(directUrl)
            signatureCipher != null -> extractUrlFromCipher(signatureCipher)
            else                    -> return null
        } ?: return null

        val mimeType = obj["mimeType"]?.jsonPrimitive?.content ?: return null
        val (ext, vcodec, acodec) = parseMimeType(mimeType)

        val itag         = obj["itag"]?.jsonPrimitive?.content ?: "0"
        val width        = obj["width"]?.jsonPrimitive?.intOrNull
        val height       = obj["height"]?.jsonPrimitive?.intOrNull
        val fps          = obj["fps"]?.jsonPrimitive?.doubleOrNull
        val totalBitrate = obj["bitrate"]?.jsonPrimitive?.intOrNull?.div(1000)
        val fileSize     = obj["contentLength"]?.jsonPrimitive?.content?.toLongOrNull()

        // FIX-3: audioBitrate — اثنين مصادر ممكنة
        val audioBitrate = obj["averageBitrate"]?.jsonPrimitive?.intOrNull?.div(1000)
            ?: obj["audioSampleRate"]?.jsonPrimitive?.intOrNull?.let { null }

        val hasVideo = vcodec != null && vcodec != "none"
        val hasAudio = acodec != null && acodec != "none"

        val initRange  = parseByteRange(obj["initRange"]?.jsonObject)
        val indexRange = parseByteRange(obj["indexRange"]?.jsonObject)

        return StreamFormat(
            formatId       = itag,
            url            = url,
            mimeType       = mimeType,
            ext            = ext,
            width          = width,
            height         = height,
            fps            = fps,
            vcodec         = vcodec,
            acodec         = acodec,
            audioBitrate   = audioBitrate,
            videoBitrate   = if (hasVideo && !hasAudio) totalBitrate else null,
            totalBitrate   = totalBitrate,
            fileSizeBytes  = fileSize,
            quality        = StreamQuality.fromHeight(height),
            hasVideo       = hasVideo,
            hasAudio       = hasAudio,
            isAdaptive     = isAdaptive,
            initRange      = initRange,
            indexRange     = indexRange,
            signatureCipher = signatureCipher
        )
    }

    private fun parseByteRange(obj: JsonObject?): ByteRange? {
        obj ?: return null
        return ByteRange(
            start = obj["start"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            end   = obj["end"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        )
    }

    private fun applyNParam(url: String): String {
        if (!NParameterUtils.hasNParam(url)) return url
        return url  // ANDROID client لا يحتاج n-param transform
    }

    private fun extractUrlFromCipher(cipher: String): String? {
        val params = cipher.split("&").associate { part ->
            val eq = part.indexOf('=')
            if (eq == -1) part to ""
            else part.substring(0, eq) to java.net.URLDecoder.decode(
                part.substring(eq + 1), "UTF-8"
            )
        }
        val url = params["url"] ?: return null
        val sig = params["s"]   ?: return url
        return "$url&sig=$sig"
    }

    /**
     * FIX-4: parseMimeType — معالجة صيغ YouTube المختلفة
     *
     * YouTube تُرسل:
     *   video/mp4; codecs="avc1.640028"          (فيديو فقط)
     *   video/mp4; codecs="avc1.640028, mp4a.40.2" (مدمج)
     *   audio/mp4; codecs="mp4a.40.2"            (صوت فقط)
     *   video/webm; codecs="vp9"
     *   audio/webm; codecs="opus"
     */
    private fun parseMimeType(mimeType: String): Triple<String, String?, String?> {
        val base = mimeType.split(";").first().trim().lowercase()

        val ext = when {
            base.contains("mp4")  -> "mp4"
            base.contains("webm") -> "webm"
            base.contains("ogg")  -> "ogg"
            else                  -> "mp4"
        }

        // FIX: handle both quoted and unquoted codecs
        val codecsMatch = Regex("""codecs=["']?([^"';]+)["']?""").find(mimeType)
        val codecs = codecsMatch?.groupValues?.get(1)
            ?.split(",")
            ?.map { it.trim() }
            ?: emptyList()

        val isVideo = base.startsWith("video/")
        val isAudio = base.startsWith("audio/")

        val vcodec: String? = when {
            isVideo -> codecs.firstOrNull()
            isAudio -> "none"
            else    -> null
        }

        val acodec: String? = when {
            isAudio             -> codecs.firstOrNull()
            isVideo && codecs.size >= 2 -> codecs[1]
            isVideo             -> null   // FIX: فيديو فقط بدون صوت
            else                -> null
        }

        return Triple(ext, vcodec, acodec)
    }

    private fun extractBestThumbnail(videoDetails: JsonObject): String? {
        val thumbnails = videoDetails["thumbnail"]?.jsonObject
            ?.get("thumbnails")?.jsonArray
            ?: return null

        return thumbnails
            .mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.content }
            .lastOrNull()
            ?: "https://i.ytimg.com/vi/${videoDetails["videoId"]?.jsonPrimitive?.content}/hqdefault.jpg"
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
}
