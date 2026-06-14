package com.ytdl.android.downloader

import com.ytdl.android.model.DownloadResult
import com.ytdl.android.model.StreamFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Stream Downloader
 *
 * يُنفذ نفس منطق yt-dlp's FileDownloader:
 * - تحميل مجزّأ (chunked) بحجم 10MB لكل جزء
 * - دعم استئناف التحميل (resume) عبر Range headers
 * - إصدار progress events مستمرة
 * - حساب سرعة التحميل
 *
 * مرجع: yt-dlp/yt_dlp/downloader/http.py
 */
class StreamDownloader(
    connectTimeoutSec: Long = 30L,
    readTimeoutSec: Long = 120L
) {

    companion object {
        private const val CHUNK_SIZE = 1024 * 1024 * 10L  // 10 MB
        private const val BUFFER_SIZE = 8192               // 8 KB buffer
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * تحميل ستريم واحد مع تتبع التقدم
     *
     * @param format  تنسيق الستريم المراد تحميله
     * @param destFile مسار الملف الوجهة
     * @param resume   محاولة استئناف التحميل إذا وُجد الملف
     * @return Flow من DownloadResult — Progress أو Success أو Error
     */
    fun download(
        format: StreamFormat,
        destFile: File,
        resume: Boolean = true
    ): Flow<DownloadResult> = flow {

        try {
            // ---- 1. الحصول على حجم الملف ----
            val totalSize = format.fileSizeBytes
                ?: getContentLength(format.url)
                ?: -1L

            // ---- 2. تحديد نقطة الاستئناف ----
            val startByte = if (resume && destFile.exists()) {
                destFile.length()
            } else {
                destFile.parentFile?.mkdirs()
                0L
            }

            // اكتمل التحميل مسبقاً
            if (totalSize > 0 && startByte >= totalSize) {
                emit(DownloadResult.Success(destFile.absolutePath))
                return@flow
            }

            // ---- 3. التحميل المجزّأ ----
            var downloadedBytes = startByte
            val startTime = System.currentTimeMillis()

            FileOutputStream(destFile, resume && startByte > 0).use { fos ->

                // yt-dlp يستخدم chunked download لتجنب memory overflow
                var rangeStart = startByte
                while (totalSize < 0 || rangeStart < totalSize) {
                    val rangeEnd = if (totalSize > 0) {
                        minOf(rangeStart + CHUNK_SIZE - 1, totalSize - 1)
                    } else {
                        rangeStart + CHUNK_SIZE - 1
                    }

                    val request = Request.Builder()
                        .url(format.url)
                        .addHeader("Range", "bytes=$rangeStart-$rangeEnd")
                        .addHeader("User-Agent", "okhttp/4.12.0")
                        .build()

                    val response = httpClient.newCall(request).execute()

                    if (!response.isSuccessful && response.code != 206) {
                        // 206 = Partial Content — متوقع
                        if (response.code == 404) {
                            emit(DownloadResult.Error("رابط الستريم منتهي الصلاحية (404)"))
                            return@flow
                        }
                        emit(DownloadResult.Error("خطأ HTTP: ${response.code}"))
                        return@flow
                    }

                    val body = response.body ?: break
                    val buffer = ByteArray(BUFFER_SIZE)
                    var chunkEnd = false

                    body.source().inputStream().use { input ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            fos.write(buffer, 0, read)
                            downloadedBytes += read
                            rangeStart += read

                            // حساب السرعة
                            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                            val speedBps = if (elapsed > 0) {
                                ((downloadedBytes - startByte) / elapsed).toLong()
                            } else 0L

                            val percentage = if (totalSize > 0) {
                                (downloadedBytes.toFloat() / totalSize * 100f).coerceIn(0f, 100f)
                            } else 0f

                            emit(
                                DownloadResult.Progress(
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalSize,
                                    speedBps = speedBps,
                                    percentage = percentage
                                )
                            )
                        }
                        chunkEnd = true
                    }

                    // إذا انتهت البيانات قبل نهاية النطاق — اكتمل التحميل
                    if (chunkEnd && (totalSize < 0 || rangeStart >= totalSize)) break
                    if (!chunkEnd) break
                }
            }

            emit(DownloadResult.Success(destFile.absolutePath))

        } catch (e: Exception) {
            emit(DownloadResult.Error("خطأ في التحميل: ${e::class.simpleName}: ${e.message}", e))
        }
    }

    /**
     * الحصول على حجم الملف عبر HEAD request
     * مرجع: yt-dlp _request_dump في http.py
     */
    private fun getContentLength(url: String): Long? {
        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .build()

            httpClient.newCall(request).execute().use { response ->
                response.header("Content-Length")?.toLongOrNull()
                    ?: response.header("content-length")?.toLongOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * تحميل DASH: دمج مسار الفيديو مع مسار الصوت
     *
     * yt-dlp يدمج باستخدام ffmpeg — هنا نوفر التحميل فقط
     * الدمج يتم في التطبيق باستخدام MediaMuxer أو mp4parser
     *
     * @return Pair(videoFile, audioFile) — يجب دمجهما
     */
    fun downloadDash(
        videoFormat: StreamFormat,
        audioFormat: StreamFormat,
        videoFile: File,
        audioFile: File
    ): Pair<Flow<DownloadResult>, Flow<DownloadResult>> {
        return Pair(
            download(videoFormat, videoFile),
            download(audioFormat, audioFile)
        )
    }
}
