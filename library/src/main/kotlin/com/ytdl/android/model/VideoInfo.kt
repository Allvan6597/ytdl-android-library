package com.ytdl.android.model

/**
 * معلومات الفيديو الكاملة المستخرجة من YouTube
 * مماثل لـ info_dict في yt-dlp
 */
data class VideoInfo(
    val id: String,
    val title: String,
    val description: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
    val viewCount: Long?,
    val uploadDate: String?,
    val channelId: String?,
    val channelName: String?,
    val isLive: Boolean,
    val formats: List<StreamFormat>,
    val subtitles: Map<String, String> = emptyMap()
) {
    /** أفضل جودة فيديو مدمج (صوت + فيديو) */
    fun bestVideo(): StreamFormat? =
        formats.filter { it.hasVideo && it.hasAudio }
               .maxByOrNull { it.height ?: 0 }

    /** أفضل جودة صوت فقط */
    fun bestAudio(): StreamFormat? =
        formats.filter { it.hasAudio && !it.hasVideo }
               .maxByOrNull { it.audioBitrate ?: 0 }

    /** جميع تنسيقات الفيديو المدمجة مرتبة تنازلياً */
    fun videoFormats(): List<StreamFormat> =
        formats.filter { it.hasVideo && it.hasAudio }
               .sortedByDescending { it.height ?: 0 }

    /** جميع تنسيقات الفيديو التكيفية (DASH) */
    fun adaptiveVideoFormats(): List<StreamFormat> =
        formats.filter { it.hasVideo && !it.hasAudio }
               .sortedByDescending { it.height ?: 0 }
}

/**
 * تنسيق ستريم واحد — مماثل لـ format dict في yt-dlp
 */
data class StreamFormat(
    val formatId: String,
    val url: String,
    val mimeType: String,
    val ext: String,
    val width: Int?,
    val height: Int?,
    val fps: Double?,
    val vcodec: String?,
    val acodec: String?,
    val audioBitrate: Int?,       // kbps
    val videoBitrate: Int?,       // kbps
    val totalBitrate: Int?,       // kbps
    val fileSizeBytes: Long?,
    val quality: StreamQuality,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val isAdaptive: Boolean,      // DASH adaptive format
    val initRange: ByteRange? = null,
    val indexRange: ByteRange? = null,
    val signatureCipher: String? = null,   // للعملاء WEB
    val isThrottled: Boolean = false
) {
    fun qualityLabel(): String = when {
        height != null -> "${height}p${fps?.let { if (it >= 50) "60" else "" } ?: ""}"
        audioBitrate != null -> "${audioBitrate}kbps audio"
        else -> quality.label
    }
}

data class ByteRange(val start: Long, val end: Long)

enum class StreamQuality(val label: String) {
    TINY("144p"),
    SMALL("240p"),
    MEDIUM("360p"),
    LARGE("480p"),
    HD720("720p"),
    HD1080("1080p"),
    HD1440("1440p"),
    HD2160("4K"),
    UNKNOWN("unknown");

    companion object {
        fun fromHeight(height: Int?): StreamQuality = when {
            height == null -> UNKNOWN
            height <= 144 -> TINY
            height <= 240 -> SMALL
            height <= 360 -> MEDIUM
            height <= 480 -> LARGE
            height <= 720 -> HD720
            height <= 1080 -> HD1080
            height <= 1440 -> HD1440
            else -> HD2160
        }
    }
}

/**
 * نتيجة التحميل
 */
sealed class DownloadResult {
    data class Progress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBps: Long,
        val percentage: Float
    ) : DownloadResult()

    data class Success(val filePath: String) : DownloadResult()
    data class Error(val message: String, val cause: Throwable? = null) : DownloadResult()
}

/**
 * إعدادات المكتبة
 */
data class YTDLConfig(
    /** عميل InnerTube المفضل — ANDROID أكثر استقراراً بدون PO Token */
    val preferredClient: InnerTubeClient = InnerTubeClient.ANDROID,
    /** تفعيل Logging للـ HTTP requests */
    val enableLogging: Boolean = false,
    /** مهلة الاتصال بالثواني */
    val connectTimeoutSec: Long = 30L,
    /** مهلة القراءة بالثواني */
    val readTimeoutSec: Long = 60L,
    /** User-Agent مخصص */
    val customUserAgent: String? = null,
    /** تفعيل cache للـ player JS */
    val enablePlayerJsCache: Boolean = true
)
