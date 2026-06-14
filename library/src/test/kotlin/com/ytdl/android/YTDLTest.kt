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
        .timeouts(connectSec = 10L, readSec = 20L)
        .build()

    @Test
    fun `extract should return video info for public video`() = runTest {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val result = withContext(Dispatchers.IO) { ytdl.extract(url) }

        result.fold(
            onSuccess = { info ->
                println("SUCCESS:")
                println("Title: ${info.title}")
                println("Channel: ${info.channelName}")
                println("Duration: ${info.durationSeconds}s")
                println("View count: ${info.viewCount}")
                println("Is live: ${info.isLive}")
                println("Formats: ${info.formats.size}")

                val best = info.bestVideo()
                if (best != null) {
                    println("Best: ${best.qualityLabel()} ${best.ext} ${best.fileSizeBytes}b")
                }

                assertNotNull(info.title) { "title" }
                assertTrue(info.formats.isNotEmpty()) { "formats" }
                assertNotNull(info.bestVideo()) { "bestVideo" }
            },
            onFailure = { err ->
                println("FAILURE: ${err.message}")
                err.printStackTrace()
            }
        )
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
