package com.ytdl.android.utils

/**
 * أدوات مساعدة لـ YouTube URLs
 *
 * يدعم كل أشكال روابط YouTube:
 * - https://www.youtube.com/watch?v=ID
 * - https://youtu.be/ID
 * - https://www.youtube.com/shorts/ID
 * - https://m.youtube.com/watch?v=ID
 * - https://music.youtube.com/watch?v=ID
 * - youtube.com/embed/ID
 * - youtube.com/v/ID
 * - bare video ID (11 chars)
 */
object YouTubeUrlUtils {

    private const val VIDEO_ID_REGEX =
        """(?:youtube(?:-nocookie)?\.com/(?:[^/]+/.+/|(?:v|e(?:mbed)?)/|shorts/|.*[?&]v=)|youtu\.be/)([a-zA-Z0-9_-]{11})"""

    private val videoIdPattern = Regex(VIDEO_ID_REGEX)
    private val bareIdPattern = Regex("""^[a-zA-Z0-9_-]{11}$""")

    /**
     * استخرج video ID من أي رابط YouTube
     * @return video ID أو null إذا لم يكن رابطاً صحيحاً
     */
    fun extractVideoId(urlOrId: String): String? {
        val trimmed = urlOrId.trim()

        // bare video ID مباشرة
        if (bareIdPattern.matches(trimmed)) return trimmed

        // استخراج من الرابط
        return videoIdPattern.find(trimmed)?.groupValues?.get(1)
    }

    /**
     * بناء InnerTube player endpoint URL
     * مرجع: yt-dlp _INNERTUBE_API_URL
     */
    fun playerApiUrl(): String =
        "https://www.youtube.com/youtubei/v1/player"

    /**
     * بناء YouTube watch URL من video ID
     */
    fun watchUrl(videoId: String): String =
        "https://www.youtube.com/watch?v=$videoId"

    /**
     * تحقق من أن الرابط صالح
     */
    fun isValidYouTubeUrl(url: String): Boolean =
        extractVideoId(url) != null

    /**
     * استخرج playlist ID إن وُجد
     */
    fun extractPlaylistId(url: String): String? {
        val pattern = Regex("""[?&]list=([a-zA-Z0-9_-]+)""")
        return pattern.find(url)?.groupValues?.get(1)
    }
}
