package com.ytdl.android

import com.ytdl.android.model.InnerTubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class YTDLTest {

    private val ytdl = YTDL.Builder()
        .preferClient(InnerTubeClient.ANDROID)
        .enableLogging(true)
        .timeouts(connectSec = 30L, readSec = 60L)
        .build()

    @Test
    fun `extract should return video info for public video`() = runTest {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val result = withContext(Dispatchers.IO) { ytdl.extract(url) }

        assertTrue(result.isSuccess) { "extract should succeed: ${result.exceptionOrNull()?.message}" }

        val info = result.getOrThrow()
        println("Title: ${info.title}")
        println("Channel: ${info.channelName}")
        println("Duration: ${info.durationSeconds}s")
        println("View count: ${info.viewCount}")
        println("Is live: ${info.isLive}")
        println("Number of formats: ${info.formats.size}")

        assertNotNull(info.title) { "title should not be null" }
        assertFalse(info.title!!.isBlank()) { "title should not be empty" }
        assertTrue(info.formats.isNotEmpty()) { "should have at least one format" }

        val best = info.bestVideo()
        assertNotNull(best) { "bestVideo should not be null" }
        println("Best video: ${best?.qualityLabel()} (${best?.ext}, ${best?.fileSizeBytes} bytes)")

        println("--- All formats ---")
        info.formats.forEach { f ->
            println("  ${f.formatId} | ${f.qualityLabel()} | ${f.mimeType} | ${f.fileSizeBytes}")
        }
    }

    @Test
    fun `getFormats should return list of stream formats`() = runTest {
        val url = "https://youtu.be/dQw4w9WgXcQ"
        val result = withContext(Dispatchers.IO) { ytdl.getFormats(url) }

        assertTrue(result.isSuccess) { "getFormats should succeed: ${result.exceptionOrNull()?.message}" }

        val formats = result.getOrThrow()
        assertTrue(formats.isNotEmpty()) { "should have formats" }
        println("Formats count: ${formats.size}")
    }

    @Test
    fun `getStreamUrl should return playable URL`() = runTest {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val result = withContext(Dispatchers.IO) { ytdl.getStreamUrl(url, preferAdaptive = false) }

        assertTrue(result.isSuccess) { "getStreamUrl should succeed: ${result.exceptionOrNull()?.message}" }

        val streamInfo = result.getOrThrow()
        assertNotNull(streamInfo.videoStreamUrl) { "videoStreamUrl should not be null" }
        assertTrue(streamInfo.videoStreamUrl.startsWith("http")) { "videoStreamUrl should start with http" }
        println("Stream URL: ${streamInfo.videoStreamUrl.take(100)}...")
        println("Quality: ${streamInfo.qualityLabel()}")
        println("Adaptive: ${streamInfo.isAdaptive}")
    }

    @Test
    fun `extract with invalid URL should fail`() = runTest {
        val result = withContext(Dispatchers.IO) { ytdl.extract("not-a-valid-url") }
        assertTrue(result.isFailure) { "invalid URL should fail" }
    }

    @Test
    fun `extractVideoId should work`() {
        val id = ytdl.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals("dQw4w9WgXcQ", id)
    }

    @Test
    fun `isValidUrl should validate`() {
        assertTrue(ytdl.isValidUrl("https://youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(ytdl.isValidUrl("not-a-url"))
    }
}
