package com.ytdl.android

import com.ytdl.android.model.InnerTubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable

class YTDLTest {

    private val ytdl = YTDL.Builder()
        .preferClient(InnerTubeClient.ANDROID)
        .enableLogging(true)
        .timeouts(connectSec = 15L, readSec = 30L)
        .build()

    @Test
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
    fun `extract should return video info for public video`() = runTest {
        val result = withContext(Dispatchers.IO) {
            ytdl.extract("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        }

        result.fold(
            onSuccess = { info ->
                println("Title: ${info.title}")
                println("Channel: ${info.channelName}")
                println("Duration: ${info.durationSeconds}s")
                println("Formats: ${info.formats.size}")
                assertNotNull(info.title)
                assertTrue(info.formats.isNotEmpty())
            },
            onFailure = { err ->
                println("FAILURE: ${err.message}")
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
