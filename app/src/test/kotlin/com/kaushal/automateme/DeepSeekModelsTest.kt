package com.kaushal.automateme

import com.google.gson.Gson
import com.kaushal.automateme.models.ChatMessage
import com.kaushal.automateme.models.Choice
import com.kaushal.automateme.models.DeepSeekRequest
import com.kaushal.automateme.models.DeepSeekResponse
import com.kaushal.automateme.models.ResponseFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for DeepSeek API request/response data classes.
 */
class DeepSeekModelsTest {

    private val gson = Gson()

    // -------------------------------------------------------------------------
    // DeepSeekRequest
    // -------------------------------------------------------------------------

    @Test
    fun `DeepSeekRequest default model is deepseek-chat`() {
        val req = DeepSeekRequest(messages = listOf(ChatMessage("user", "Hello")))
        assertEquals("deepseek-chat", req.model)
    }

    @Test
    fun `DeepSeekRequest serializes model field correctly`() {
        val req = DeepSeekRequest(messages = emptyList())
        val json = gson.toJson(req)
        assertTrue(json.contains("\"model\":\"deepseek-chat\""))
    }

    @Test
    fun `DeepSeekRequest serializes messages array`() {
        val req = DeepSeekRequest(
            messages = listOf(
                ChatMessage("system", "Be helpful"),
                ChatMessage("user", "Automate this")
            )
        )
        val json = gson.toJson(req)
        assertTrue(json.contains("\"messages\""))
        assertTrue(json.contains("system"))
        assertTrue(json.contains("Be helpful"))
    }

    @Test
    fun `DeepSeekRequest includes response_format`() {
        val req = DeepSeekRequest(messages = emptyList())
        val json = gson.toJson(req)
        assertTrue(json.contains("\"response_format\""))
        assertTrue(json.contains("json_object"))
    }

    // -------------------------------------------------------------------------
    // ResponseFormat
    // -------------------------------------------------------------------------

    @Test
    fun `ResponseFormat default type is json_object`() {
        assertEquals("json_object", ResponseFormat().type)
    }

    @Test
    fun `ResponseFormat serializes type correctly`() {
        val json = gson.toJson(ResponseFormat())
        assertTrue(json.contains("\"type\":\"json_object\""))
    }

    // -------------------------------------------------------------------------
    // ChatMessage
    // -------------------------------------------------------------------------

    @Test
    fun `ChatMessage stores role and content`() {
        val msg = ChatMessage(role = "assistant", content = "Done!")
        assertEquals("assistant", msg.role)
        assertEquals("Done!", msg.content)
    }

    @Test
    fun `ChatMessage serializes both fields`() {
        val json = gson.toJson(ChatMessage("system", "You are an assistant"))
        assertTrue(json.contains("\"role\":\"system\""))
        assertTrue(json.contains("\"content\":\"You are an assistant\""))
    }

    // -------------------------------------------------------------------------
    // DeepSeekResponse
    // -------------------------------------------------------------------------

    @Test
    fun `DeepSeekResponse default has null id and empty choices`() {
        val resp = DeepSeekResponse()
        assertNull(resp.id)
        assertTrue(resp.choices.isEmpty())
    }

    @Test
    fun `DeepSeekResponse deserializes from API JSON`() {
        val json = """
            {
              "id": "chat-abc123",
              "choices": [
                {
                  "message": {"role": "assistant", "content": "{\\"steps\\":[]}"},
                  "finish_reason": "stop"
                }
              ]
            }
        """.trimIndent()
        val resp = gson.fromJson(json, DeepSeekResponse::class.java)
        assertEquals("chat-abc123", resp.id)
        assertEquals(1, resp.choices.size)
        assertEquals("assistant", resp.choices[0].message.role)
        assertEquals("stop", resp.choices[0].finishReason)
    }

    @Test
    fun `DeepSeekResponse with empty choices deserializes correctly`() {
        val json = """{"id":null,"choices":[]}"""
        val resp = gson.fromJson(json, DeepSeekResponse::class.java)
        assertTrue(resp.choices.isEmpty())
    }

    @Test
    fun `Choice stores message and finishReason`() {
        val choice = Choice(
            message = ChatMessage("assistant", "OK"),
            finishReason = "stop"
        )
        assertEquals("stop", choice.finishReason)
        assertEquals("assistant", choice.message.role)
    }
}
