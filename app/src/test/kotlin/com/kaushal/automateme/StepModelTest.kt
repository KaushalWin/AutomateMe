package com.kaushal.automateme

import com.google.gson.Gson
import com.kaushal.automateme.models.AIResponse
import com.kaushal.automateme.models.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Step] and [AIResponse] Gson serialization / deserialization.
 */
class StepModelTest {

    private val gson = Gson()

    @Test
    fun `Step with all fields deserializes correctly`() {
        val json = """{"action":"tap_text","value":"OK","summary":"Tap OK button","direction":null}"""
        val step = gson.fromJson(json, Step::class.java)
        assertEquals("tap_text", step.action)
        assertEquals("OK", step.value)
        assertEquals("Tap OK button", step.summary)
        assertNull(step.direction)
    }

    @Test
    fun `Step with scroll direction deserializes correctly`() {
        val json = """{"action":"scroll","direction":"down","summary":"Scroll down"}"""
        val step = gson.fromJson(json, Step::class.java)
        assertEquals("scroll", step.action)
        assertEquals("down", step.direction)
        assertNull(step.value)
    }

    @Test
    fun `Step with extract_text deserializes correctly`() {
        val json = """{"action":"extract_text","summary":"Extract visible text"}"""
        val step = gson.fromJson(json, Step::class.java)
        assertEquals("extract_text", step.action)
        assertNull(step.value)
        assertNull(step.direction)
    }

    @Test
    fun `AIResponse with multiple steps deserializes correctly`() {
        val json = """
            {
              "steps": [
                {"action":"tap_text","value":"Login","summary":"Tap login"},
                {"action":"scroll","direction":"down","summary":"Scroll down"},
                {"action":"extract_text","summary":"Extract visible text"}
              ]
            }
        """.trimIndent()
        val response = gson.fromJson(json, AIResponse::class.java)
        assertEquals(3, response.steps.size)
        assertEquals("tap_text", response.steps[0].action)
        assertEquals("Login", response.steps[0].value)
        assertEquals("scroll", response.steps[1].action)
        assertEquals("down", response.steps[1].direction)
        assertEquals("extract_text", response.steps[2].action)
    }

    @Test
    fun `AIResponse with empty steps list`() {
        val json = """{"steps":[]}"""
        val response = gson.fromJson(json, AIResponse::class.java)
        assertTrue(response.steps.isEmpty())
    }

    @Test
    fun `Step serializes and round-trips correctly`() {
        val original = Step(action = "tap_text", value = "Submit", summary = "Tap submit button")
        val json = gson.toJson(original)
        val deserialized = gson.fromJson(json, Step::class.java)
        assertEquals(original.action, deserialized.action)
        assertEquals(original.value, deserialized.value)
        assertEquals(original.summary, deserialized.summary)
        assertNull(deserialized.direction)
    }

    @Test
    fun `AIResponse with single step round-trips correctly`() {
        val original = AIResponse(
            steps = listOf(
                Step(action = "scroll", direction = "up", summary = "Scroll to top")
            )
        )
        val json = gson.toJson(original)
        val deserialized = gson.fromJson(json, AIResponse::class.java)
        assertEquals(1, deserialized.steps.size)
        assertEquals("scroll", deserialized.steps[0].action)
        assertEquals("up", deserialized.steps[0].direction)
    }
}
