package com.example

import com.example.data.remote.GeminiService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GeminiServiceFallbackTest {

    @Test
    fun resolveModelCandidates_defaultFlashIncludesFallbacks() {
        val candidates = GeminiService.resolveModelCandidates(
            requestedModel = "gemini-3.8-flash",
            generateImage = false,
            enableHighThinking = false
        )
        assertEquals("gemini-3.8-flash", candidates.first())
        assertTrue(candidates.size >= 4)
        assertTrue(candidates.contains("gemini-2.5-flash"))
        assertEquals(candidates.size, candidates.distinct().size)
    }

    @Test
    fun resolveModelCandidates_highThinkingIncludesFallbackChain() {
        val candidates = GeminiService.resolveModelCandidates(
            requestedModel = "gemini-4-argon",
            generateImage = false,
            enableHighThinking = true
        )
        assertEquals("gemini-4-argon", candidates.first())
        assertTrue(candidates.contains("gemini-3.8-flash"))
        assertTrue(candidates.contains("gemini-2.5-flash"))
    }

    @Test
    fun resolveModelCandidates_imageGenIncludesNanoBananaFallback() {
        val candidates = GeminiService.resolveModelCandidates(
            requestedModel = "gemini-3-pro-image-preview",
            generateImage = true,
            enableHighThinking = false
        )
        assertEquals("gemini-3-pro-image-preview", candidates.first())
        assertTrue(candidates.contains("gemini-nano-banana-2.1"))
    }

    @Test
    fun extractApiErrorMessage_parses503JsonCleanlyAndRedactsSecrets() {
        val rawJson = """
            {
              "error": {
                "code": 503,
                "message": "The model is overloaded. Please try again later.",
                "status": "UNAVAILABLE"
              }
            }
        """.trimIndent()
        val msg = GeminiService.extractApiErrorMessage(503, rawJson)
        assertTrue(msg.contains("503"))
        assertTrue(msg.contains("The model is overloaded"))
        assertFalse(msg.contains("{"))
    }
}
