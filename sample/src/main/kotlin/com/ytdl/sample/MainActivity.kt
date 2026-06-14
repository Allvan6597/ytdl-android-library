package com.ytdl.sample

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ytdl.android.YTDL
import com.ytdl.android.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val ytdl = YTDL.Builder()
        .preferClient(InnerTubeClient.ANDROID)
        .enableLogging(true)
        .timeouts(connectSec = 30L, readSec = 60L)
        .build()

    private lateinit var urlInput: EditText
    private lateinit var logView: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        urlInput = EditText(this).apply {
            hint = "YouTube URL"
            textSize = 14f
        }
        root.addView(urlInput, LinearLayout.LayoutParams(-1, -2))

        fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
            setText(text)
            textSize = 12f
            setOnClickListener { onClick() }
        }

        fun row(vararg buttons: Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach { addView(it) }
        }

        root.addView(row(btn("Extract") { doExtract() }, btn("Stream URL") { doStream() }))
        root.addView(row(btn("Download (best)") { doDownload(false) }, btn("Download (DASH)") { doDownload(true) }))
        root.addView(row(btn("Copy Log") { copyLog() }, btn("Clear") { logView.text = "" }))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            visibility = android.view.View.GONE
        }
        root.addView(progressBar)

        logView = TextView(this).apply {
            textSize = 10f
            isVerticalScrollBarEnabled = true
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        log("Ready. Paste YouTube URL and tap a button.")
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        runOnUiThread { logView.append("[$ts] $msg\n") }
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("YTDL Log", logView.text))
        Toast.makeText(this, "Log copied!", Toast.LENGTH_SHORT).show()
    }

    private fun progress(show: Boolean) {
        runOnUiThread { progressBar.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE }
    }

    private fun url(): String? {
        val u = urlInput.text.toString().trim()
        if (u.isEmpty()) { log("ERROR: Paste a URL first."); return null }
        return u
    }

    // ═══ Extract ═══
    private fun doExtract() {
        val url = url() ?: return
        lifecycleScope.launch {
            progress(true)
            log("Extracting: $url")
            val result = withContext(Dispatchers.IO) { ytdl.extract(url) }
            result.fold(
                onSuccess = { info ->
                    log("TITLE: ${info.title}")
                    log("CHANNEL: ${info.channelName}")
                    log("DURATION: ${info.durationSeconds}s")
                    log("VIEWS: ${info.viewCount}")
                    log("LIVE: ${info.isLive}")

                    info.bestVideo()?.let { log("BEST VIDEO: ${it.qualityLabel()} ${it.ext}") }
                    info.bestAudio()?.let { log("BEST AUDIO: ${it.acodec} ${it.audioBitrate}kbps") }

                    log("--- ${info.formats.size} formats ---")
                    info.formats.forEach { f ->
                        val size = if (f.fileSizeBytes != null) "${f.fileSizeBytes / 1048576}MB" else "?"
                        log("${f.formatId} | ${f.qualityLabel().padEnd(10)} | ${f.ext} | $size")
                    }
                    log("DONE.")
                },
                onFailure = { err -> log("EXTRACT ERROR: ${err.message}") }
            )
            progress(false)
        }
    }

    // ═══ Stream URL ═══
    private fun doStream() {
        val url = url() ?: return
        lifecycleScope.launch {
            progress(true)
            log("Stream URL: $url")
            val result = withContext(Dispatchers.IO) { ytdl.getStreamUrl(url, preferAdaptive = false) }
            result.fold(
                onSuccess = { s ->
                    log("STREAM (${s.qualityLabel()}):")
                    log(s.videoStreamUrl)
                    s.audioStreamUrl?.let { log("AUDIO: $it") }
                },
                onFailure = { err -> log("STREAM ERROR: ${err.message}") }
            )
            progress(false)
        }
    }

    // ═══ Download ═══
    private fun doDownload(dash: Boolean) {
        val url = url() ?: return
        lifecycleScope.launch {
            progress(true)
            log("Extracting for download...")
            val result = withContext(Dispatchers.IO) { ytdl.extract(url) }
            result.fold(
                onSuccess = { info ->
                    log("Downloading: ${info.title}")
                    if (dash) downloadDash(info) else downloadBest(info)
                },
                onFailure = { err -> log("EXTRACT ERROR: ${err.message}"); progress(false) }
            )
        }
    }

    private suspend fun downloadBest(info: VideoInfo) {
        val format = info.bestVideo() ?: run { log("ERROR: No combined format."); progress(false); return }
        val file = outputFile("${sanitize(info.title)}.${format.ext}")
        log("Saving: ${file.name}")

        withContext(Dispatchers.IO) {
            ytdl.downloadFormat(format, file, resume = true)
        }.collect { r ->
            when (r) {
                is DownloadResult.Progress -> {
                    val mb = r.downloadedBytes / 1048576
                    val total = if (r.totalBytes > 0) "/${r.totalBytes / 1048576}MB" else ""
                    log("DL ${r.percentage.toInt()}% ${mb}MB$total ${r.speedBps / 1048576}MB/s")
                }
                is DownloadResult.Success -> {
                    log("COMPLETE: ${r.filePath}")
                    progress(false)
                }
                is DownloadResult.Error -> {
                    log("DOWNLOAD ERROR: ${r.message}")
                    progress(false)
                }
            }
        }
    }

    private suspend fun downloadDash(info: VideoInfo) {
        val pair = ytdl.downloadDash(info, cacheDir) ?: run {
            log("ERROR: No DASH formats available.")
            progress(false); return
        }
        var vDone = false; var aDone = false

        lifecycleScope.launch {
            pair.first.collect { r ->
                when (r) {
                    is DownloadResult.Progress -> log("VIDEO ${r.percentage.toInt()}% ${r.downloadedBytes / 1048576}MB")
                    is DownloadResult.Success -> { vDone = true; log("VIDEO DONE"); if (vDone && aDone) { progress(false); log("DASH complete. Use ffmpeg to merge.") } }
                    is DownloadResult.Error -> { log("VIDEO ERROR: ${r.message}"); progress(false) }
                }
            }
        }
        lifecycleScope.launch {
            pair.second.collect { r ->
                when (r) {
                    is DownloadResult.Progress -> log("AUDIO ${r.percentage.toInt()}% ${r.downloadedBytes / 1048576}MB")
                    is DownloadResult.Success -> { aDone = true; log("AUDIO DONE"); if (vDone && aDone) { progress(false); log("DASH complete. Use ffmpeg to merge.") } }
                    is DownloadResult.Error -> { log("AUDIO ERROR: ${r.message}") }
                }
            }
        }
    }

    // ═══ Storage (all API levels) ═══
    private fun outputFile(name: String): File {
        return if (Build.VERSION.SDK_INT >= 29) {
            // API 29+: app-specific dir (no permissions needed)
            val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
            dir.mkdirs()
            File(dir, name)
        } else {
            // API < 29: Downloads public folder with WRITE_EXTERNAL_STORAGE
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            File(dir, name)
        }
    }

    private fun sanitize(s: String) = s.replace(Regex("""[/\\:*?"<>|]"""), "_").take(180)
}
