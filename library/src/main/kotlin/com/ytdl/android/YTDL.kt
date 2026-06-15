package com.ytdl.android

import com.ytdl.android.core.InnerTubeService
import com.ytdl.android.downloader.StreamDownloader
import com.ytdl.android.extractor.YouTubeExtractor
import com.ytdl.android.model.*
import com.ytdl.android.utils.YouTubeUrlUtils
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * ╔═══════════════════════════════════════════════════════════════╗
 * ║              YTDLAndroid — Public API                        ║
 * ║                                                              ║
 * ║  مكتبة Kotlin لاستخراج وتحميل فيديوهات YouTube             ║
 * ║  مبنية على نفس منطق yt-dlp 2026.06.09                      ║
 * ║                                                              ║
 * ║  المميزات:                                                   ║
 * ║  • InnerTube API (ANDROID client — أفضل استقرار)           ║
 * ║  • Fallback chain: ANDROID → IOS → TV_EMBEDDED → WEB       ║
 * ║  • دعم جميع أشكال روابط YouTube                            ║
 * ║  • استخراج DASH adaptive formats                            ║
 * ║  • تحميل مجزّأ مع استئناف                                   ║
 * ║  • Coroutine-native API                                      ║
 * ╚═══════════════════════════════════════════════════════════════╝
 *
 * الاستخدام الأساسي:
 * ```kotlin
 * val ytdl = YTDL.Builder().build()
 *
 * // استخراج معلومات الفيديو
 * val info = ytdl.extract("https://youtube.com/watch?v=dQw4w9WgXcQ")
 * println(info.getOrNull()?.title)
 *
 * // تحميل أفضل جودة
 * ytdl.download(info.getOrThrow(), destDir)
 *     .collect { result ->
 *         when (result) {
 *             is DownloadResult.Progress -> println("${result.percentage}%")
 *             is DownloadResult.Success -> println("تم: ${result.filePath}")
 *             is DownloadResult.Error -> println("خطأ: ${result.message}")
 *         }
 *     }
 * ```
 */
class YTDL private constructor(
    private val config: YTDLConfig,
    private val extractor: YouTubeExtractor,
    private val downloader: StreamDownloader
) {

    // ════════════════════════════════════════
    // Extraction API
    // ════════════════════════════════════════

    /**
     * استخراج معلومات الفيديو الكاملة
     *
     * @param urlOrId رابط YouTube أو video ID مباشرة
     * @return Result<VideoInfo> — نجاح أو فشل مع سبب
     */
    suspend fun extract(urlOrId: String): Result<VideoInfo> {
        val videoId = YouTubeUrlUtils.extractVideoId(urlOrId)
            ?: return Result.failure(
                IllegalArgumentException("رابط YouTube غير صالح: $urlOrId")
            )

        return extractor.extract(videoId, config.preferredClient)
    }

    /**
     * استخراج قائمة formats فقط (أسرع من extract الكاملة)
     */
    suspend fun getFormats(urlOrId: String): Result<List<StreamFormat>> {
        return extract(urlOrId).map { it.formats }
    }

    /**
     * الحصول على رابط الستريم المباشر لأفضل جودة
     *
     * @param urlOrId رابط YouTube
     * @param preferAdaptive تفضيل DASH adaptive (فيديو + صوت منفصلان) — أعلى جودة
     */
    suspend fun getStreamUrl(
        urlOrId: String,
        preferAdaptive: Boolean = false
    ): Result<StreamInfo> {
        val info = extract(urlOrId).getOrElse { return Result.failure(it) }

        return if (preferAdaptive && info.adaptiveVideoFormats().isNotEmpty()) {
            val video = info.adaptiveVideoFormats().first()
            val audio = info.bestAudio()
            Result.success(StreamInfo(
                videoStreamUrl = video.url,
                audioStreamUrl = audio?.url,
                format = video,
                isAdaptive = true
            ))
        } else {
            val best = info.bestVideo()
                ?: return Result.failure(Exception("لا توجد formats متاحة"))
            Result.success(StreamInfo(
                videoStreamUrl = best.url,
                audioStreamUrl = null,
                format = best,
                isAdaptive = false
            ))
        }
    }

    // ════════════════════════════════════════
    // Download API
    // ════════════════════════════════════════

    /**
     * تحميل أفضل format مدمج (فيديو + صوت)
     *
     * @param info معلومات الفيديو المستخرجة مسبقاً
     * @param destDir مجلد الوجهة
     * @param resume محاولة استئناف التحميل إذا وُجد ملف جزئي
     */
    fun download(
        info: VideoInfo,
        destDir: File,
        resume: Boolean = true
    ): Flow<DownloadResult> {
        val format = info.bestVideo()
            ?: throw IllegalStateException("لا توجد formats متاحة للتحميل")

        val filename = sanitizeFilename("${info.title}.${format.ext}")
        val destFile = File(destDir, filename)

        return downloader.download(format, destFile, resume)
    }

    /**
     * تحميل format محدد
     *
     * @param format التنسيق المختار من VideoInfo.formats
     * @param destFile ملف الوجهة
     */
    fun downloadFormat(
        format: StreamFormat,
        destFile: File,
        resume: Boolean = true
    ): Flow<DownloadResult> {
        return downloader.download(format, destFile, resume)
    }

    /**
     * تحميل DASH (فيديو وصوت منفصلان) — أعلى جودة ممكنة
     *
     * الدمج يتم في تطبيقك باستخدام MediaMuxer أو mp4parser
     *
     * @return Pair<videoFlow, audioFlow>
     */
    fun downloadDash(
        info: VideoInfo,
        destDir: File
    ): Pair<Flow<DownloadResult>, Flow<DownloadResult>>? {
        val videoFormat = info.adaptiveVideoFormats().firstOrNull() ?: return null
        val audioFormat = info.bestAudio() ?: return null

        val videoFile = File(destDir, sanitizeFilename("${info.title}_video.${videoFormat.ext}"))
        val audioFile = File(destDir, sanitizeFilename("${info.title}_audio.${audioFormat.ext}"))

        return downloader.downloadDash(videoFormat, audioFormat, videoFile, audioFile)
    }

    // ════════════════════════════════════════
    // Utilities
    // ════════════════════════════════════════

    /** التحقق من صحة الرابط */
    fun isValidUrl(url: String): Boolean = YouTubeUrlUtils.isValidYouTubeUrl(url)

    /** استخراج video ID من رابط */
    fun extractVideoId(url: String): String? = YouTubeUrlUtils.extractVideoId(url)

    /** تنظيف اسم الملف من الأحرف غير المسموح بها */
    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("""[/\\:*?"<>|]"""), "_")
            .take(200)  // Android حد أقصى اسم الملف
    }

    // ════════════════════════════════════════
    // Builder
    // ════════════════════════════════════════

    class Builder {
        private var config = YTDLConfig()

        fun config(config: YTDLConfig) = apply { this.config = config }

        /** تفضيل عميل InnerTube محدد */
        fun preferClient(client: InnerTubeClient) = apply {
            this.config = config.copy(preferredClient = client)
        }

        /** تفعيل HTTP logging */
        fun enableLogging(enable: Boolean = true) = apply {
            this.config = config.copy(enableLogging = enable)
        }

        /** تخصيص مهل الاتصال */
        fun timeouts(connectSec: Long, readSec: Long) = apply {
            this.config = config.copy(
                connectTimeoutSec = connectSec,
                readTimeoutSec = readSec
            )
        }

        fun build(): YTDL {
            val service = InnerTubeService(config)
            val extractor = YouTubeExtractor(service, config.enableLogging)
            val downloader = StreamDownloader(
                connectTimeoutSec = config.connectTimeoutSec,
                readTimeoutSec = config.readTimeoutSec
            )
            return YTDL(config, extractor, downloader)
        }
    }
}

/**
 * معلومات الستريم المباشر
 */
data class StreamInfo(
    val videoStreamUrl: String,
    val audioStreamUrl: String?,
    val format: StreamFormat,
    val isAdaptive: Boolean
) {
    fun qualityLabel(): String = format.qualityLabel()
}
