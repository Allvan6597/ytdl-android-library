package com.ytdl.android.extractor

import android.util.Log
import com.ytdl.android.core.InnerTubeService
import com.ytdl.android.model.*
import com.ytdl.android.utils.NParameterUtils
import kotlinx.serialization.json.*

/**
 * YouTube Stream Extractor
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
            } catch (e: Exception) {
                val msg = e.message ?: "unknown error"
                errors.add("${client.clientName}: $msg")
                // إذا كان الفيديو UNPLAYABLE فعلاً، لا فائدة من المحاولة بعملاء أخرى
                if (msg.contains("UNPLAYABLE") || msg.contains("ERROR")) {
                    break
                }
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
                if (client != InnerTubeClient.TV_EMBEDDED) return null
            }
            "AGE_VERIFICATION_REQUIRED", "AGE_CHECK_REQUIRED" -> return null
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

        // ---- streamingData — المشكلة الأساسية ----
        val streamingData = playerResponse["streamingData"]?.jsonObject ?: run {
            if (enableLogging) Log.w(TAG,
                "${client.clientName}: playabilityStatus=OK but streamingData ABSENT — PO Token gating active")
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

        if (formats.isEmpty()) {
            if (enableLogging) Log.w(TAG, "${client.clientName}: streamingData present but all formats failed to parse")
            return null
        }

        // ---- build VideoInfo ----
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
            directUrl != null       -> applyNParam(directUrl)
            signatureCipher != null -> extractUrlFromCipher(signatureCipher)
            else                    -> return null
        } ?: return null

        val mimeType = obj["mimeType"]?.jsonPrimitive?.content ?: return null
        val (ext, vcodec, acodec) = parseMimeType(mimeType)

        val height   = obj["height"]?.jsonPrimitive?.intOrNull
        val hasVideo = vcodec != null && vcodec != "none"
        val hasAudio = acodec != null && acodec != "none"

        val totalBitrateBps = obj["bitrate"]?.jsonPrimitive?.intOrNull
        val totalBitrateKbps = totalBitrateBps?.div(1000)

        // audioBitrate: averageBitrate لملفات الصوت
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
        if (!NParameterUtils.hasNParam(url)) return url
        return url
    }

    private fun extractUrlFromCipher(cipher: String): String? {
        val params = cipher.split("&").associate { part ->
            val eq = part.indexOf('=')
            if (eq == -1) part to ""
            else part.substring(0, eq) to
                    runCatching { java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8") }
                        .getOrDefault(part.substring(eq + 1))
        }
        val url = params["url"] ?: return null
        val sig = params["s"]   ?: return url
        return "$url&sig=$sig"
    }

    private fun parseMimeType(mimeType: String): Triple<String, String?, String?> {
        val base = mimeType.split(";").first().trim().lowercase()

        val ext = when {
            base.contains("mp4")  -> "mp4"
            base.contains("webm") -> "webm"
            base.contains("ogg")  -> "ogg"
            else                  -> "mp4"
        }

        // دعم كل أشكال codecs: مع/بدون quotes
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
            isVideo                     -> null   // فيديو فقط بدون صوت (DASH)
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

    companion object {
        private const val TAG = "YTDLAndroid"
    }
}
