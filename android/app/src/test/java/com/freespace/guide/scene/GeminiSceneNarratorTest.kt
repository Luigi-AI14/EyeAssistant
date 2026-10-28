package com.freespace.guide.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiSceneNarratorTest {

    @Test
    fun `isConfigured restituisce false con chiave vuota o placeholder`() {
        val emptyNarrator = GeminiSceneNarrator(apiKey = "")
        assertFalse(emptyNarrator.isConfigured())

        val placeholderNarrator = GeminiSceneNarrator(apiKey = "YOUR_GEMINI_API_KEY_HERE")
        assertFalse(placeholderNarrator.isConfigured())
    }

    @Test
    fun `isConfigured restituisce true con chiave valida`() {
        val validNarrator = GeminiSceneNarrator(apiKey = "AIzaSyFakeKeyForTesting123456")
        assertTrue(validNarrator.isConfigured())
    }

    @Test
    fun `default model name e costanti Gemini 3x sono configurate`() {
        assertEquals("gemini-3.5-flash-lite", GeminiSceneNarrator.DEFAULT_MODEL_NAME)
        assertEquals("gemini-3.7-flash", GeminiSceneNarrator.FALLBACK_37_FLASH)
        assertEquals("gemini-3.6-flash", GeminiSceneNarrator.FALLBACK_36_FLASH)
        assertEquals("gemini-3.5-flash", GeminiSceneNarrator.FALLBACK_35_FLASH)
    }

    @Test
    fun `buildJsonPayload genera JSON valido con campi system_instruction e inline_data`() {
        val narrator = GeminiSceneNarrator(apiKey = "fake_key")
        val jsonStr = narrator.buildJsonPayload("base64_data_xyz", "Test prompt")
        val json = org.json.JSONObject(jsonStr)

        assertTrue(json.has("system_instruction"))
        assertTrue(json.has("contents"))
        assertTrue(json.has("generationConfig"))

        val contents = json.getJSONArray("contents")
        assertEquals(1, contents.length())
        val parts = contents.getJSONObject(0).getJSONArray("parts")
        assertEquals(2, parts.length())
        assertEquals("image/jpeg", parts.getJSONObject(0).getJSONObject("inline_data").getString("mime_type"))
        assertEquals("base64_data_xyz", parts.getJSONObject(0).getJSONObject("inline_data").getString("data"))
        assertEquals("Test prompt", parts.getJSONObject(1).getString("text"))

        val config = json.getJSONObject("generationConfig")
        assertEquals(1024, config.getInt("maxOutputTokens"))
    }
}
