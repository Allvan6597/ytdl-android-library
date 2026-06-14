package com.ytdl.sample

import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ytdl.android.YTDL
import com.ytdl.android.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * مثال استخدام كامل لمكتبة YTDLAndroid
 *
 * هذا ملف Sample — يُظهر كيفية دمج المكتبة في تطبيقك
 */
class MainActivity : AppCompatActivity() {

    // إنشاء instance واحد من YTDL
    private val ytdl = YTDL.Builder()
        .preferClient(InnerTubeClient.ANDROID)  // الأفضل لـ Android
        .enableLogging(true)                    // logging للـ debug
        .timeouts(connectSec = 30L, readSec = 60L)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── مثال 1: استخراج معلومات فيديو ──
        lifecycleScope.launch {
            extractVideoInfo("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        }

        // ── مثال 2: الحصول على stream URL للتشغيل المباشر ──
        lifecycleScope.launch {
            getStreamUrlForPlayer("https://youtu.be/dQw4w9WgXcQ")
        }

        // ── مثال 3: تحميل الفيديو ──
        lifecycleScope.launch {
            downloadVideo("https://www.youtube.com/shorts/abc123")
        }
    }

    // ═══════════════════════════════════════════
    // مثال 1: استخراج معلومات
    // ═══════════════════════════════════════════
    private suspend fun extractVideoInfo(url: String) {
        val result = withContext(Dispatchers.IO) {
            ytdl.extract(url)
        }

        result.onSuccess { info ->
            println("═══ معلومات الفيديو ═══")
            println("العنوان: ${info.title}")
            println("القناة: ${info.channelName}")
            println("المدة: ${formatDuration(info.durationSeconds)}")
            println("المشاهدات: ${info.viewCount?.let { formatNumber(it) }}")
            println("بث مباشر: ${info.isLive}")
            println("")
            println("── الـ Formats المتاحة ──")

            // عرض الـ formats المدمجة
            println("فيديو مدمج (أسهل للتشغيل):")
            info.videoFormats().forEach { format ->
                println("  ${format.qualityLabel()} | ${format.ext} | ${formatSize(format.fileSizeBytes)}")
            }

            // عرض الـ DASH formats
            println("\nفيديو DASH (أعلى جودة — يحتاج دمج صوت):")
            info.adaptiveVideoFormats().take(5).forEach { format ->
                println("  ${format.qualityLabel()} | ${format.vcodec} | ${format.videoBitrate}kbps")
            }

            println("\nصوت:")
            info.bestAudio()?.let { audio ->
                println("  ${audio.acodec} | ${audio.audioBitrate}kbps")
            }

        }.onFailure { error ->
            println("خطأ في الاستخراج: ${error.message}")
        }
    }

    // ═══════════════════════════════════════════
    // مثال 2: Stream URL للتشغيل في ExoPlayer
    // ═══════════════════════════════════════════
    private suspend fun getStreamUrlForPlayer(url: String) {
        val result = withContext(Dispatchers.IO) {
            // preferAdaptive = false → رابط مباشر يعمل مع أي مشغل
            // preferAdaptive = true  → DASH (يحتاج ExoPlayer)
            ytdl.getStreamUrl(url, preferAdaptive = false)
        }

        result.onSuccess { streamInfo ->
            println("═══ رابط الستريم ═══")
            println("الجودة: ${streamInfo.qualityLabel()}")
            println("URL: ${streamInfo.videoStreamUrl.take(80)}...")

            // تشغيل في ExoPlayer:
            // val mediaItem = MediaItem.fromUri(streamInfo.videoStreamUrl)
            // exoPlayer.setMediaItem(mediaItem)
            // exoPlayer.prepare()
            // exoPlayer.play()

        }.onFailure { error ->
            println("خطأ: ${error.message}")
        }
    }

    // ═══════════════════════════════════════════
    // مثال 3: تحميل الفيديو
    // ═══════════════════════════════════════════
    private suspend fun downloadVideo(url: String) {
        // استخراج المعلومات أولاً
        val info = withContext(Dispatchers.IO) {
            ytdl.extract(url)
        }.getOrElse {
            Toast.makeText(this, "فشل الاستخراج: ${it.message}", Toast.LENGTH_LONG).show()
            return
        }

        // مجلد التحميل
        val downloadDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: filesDir

        println("═══ بدء التحميل ═══")
        println("الفيديو: ${info.title}")

        // تحميل أفضل جودة مدمجة
        withContext(Dispatchers.IO) {
            ytdl.download(info, downloadDir, resume = true)
        }.collect { result ->
            when (result) {
                is DownloadResult.Progress -> {
                    val mb = result.downloadedBytes / 1024 / 1024
                    val totalMb = if (result.totalBytes > 0) result.totalBytes / 1024 / 1024 else -1
                    val speed = result.speedBps / 1024  // KB/s

                    println(buildString {
                        append("تحميل: ${result.percentage.toInt()}%")
                        append(" | ${mb}MB")
                        if (totalMb > 0) append("/${totalMb}MB")
                        append(" | ${speed}KB/s")
                    })
                }

                is DownloadResult.Success -> {
                    println("✓ اكتمل التحميل: ${result.filePath}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "تم التحميل!", Toast.LENGTH_SHORT).show()
                    }
                }

                is DownloadResult.Error -> {
                    println("✗ خطأ: ${result.message}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "خطأ: ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════
    // دوال مساعدة للعرض
    // ═══════════════════════════════════════════

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%d:%02d".format(m, s)
    }

    private fun formatNumber(n: Long): String = when {
        n >= 1_000_000_000 -> "%.1fب".format(n / 1_000_000_000.0)
        n >= 1_000_000 -> "%.1fم".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fك".format(n / 1_000.0)
        else -> n.toString()
    }

    private fun formatSize(bytes: Long?): String {
        if (bytes == null || bytes <= 0) return "غير معروف"
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
