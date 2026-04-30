package com.kaushal.automateme

import com.kaushal.automateme.models.Step
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ActionExecutor].
 * [UiInteractor] is mocked — no Android runtime or accessibility service required.
 */
class ActionExecutorTest {

    private lateinit var mockUi: UiInteractor
    private lateinit var executor: ActionExecutor

    @Before
    fun setUp() {
        mockUi = mockk(relaxed = true)
        executor = ActionExecutor(mockUi)
    }

    // -------------------------------------------------------------------------
    // tap_text
    // -------------------------------------------------------------------------

    @Test
    fun `tap_text with valid value and successful tap returns result string`() {
        every { mockUi.tapText("Login") } returns true
        val step = Step(action = ActionExecutor.ACTION_TAP_TEXT, value = "Login", summary = "Tap login")
        assertEquals("Tapped: Login", executor.execute(step))
        verify(exactly = 1) { mockUi.tapText("Login") }
    }

    @Test
    fun `tap_text when tapText returns false yields null`() {
        every { mockUi.tapText("Login") } returns false
        val step = Step(action = ActionExecutor.ACTION_TAP_TEXT, value = "Login", summary = "Tap login")
        assertNull(executor.execute(step))
    }

    @Test
    fun `tap_text with null value returns null without calling service`() {
        val step = Step(action = ActionExecutor.ACTION_TAP_TEXT, value = null, summary = "Tap")
        assertNull(executor.execute(step))
        verify(exactly = 0) { mockUi.tapText(any()) }
    }

    @Test
    fun `tap_text with blank value returns null without calling service`() {
        val step = Step(action = ActionExecutor.ACTION_TAP_TEXT, value = "   ", summary = "Tap")
        assertNull(executor.execute(step))
        verify(exactly = 0) { mockUi.tapText(any()) }
    }

    // -------------------------------------------------------------------------
    // scroll
    // -------------------------------------------------------------------------

    @Test
    fun `scroll with direction=down calls service and returns success`() {
        every { mockUi.scroll("down") } returns true
        val step = Step(action = ActionExecutor.ACTION_SCROLL, direction = "down", summary = "Scroll down")
        assertEquals("Scrolled down", executor.execute(step))
        verify(exactly = 1) { mockUi.scroll("down") }
    }

    @Test
    fun `scroll with direction=up calls service and returns success`() {
        every { mockUi.scroll("up") } returns true
        val step = Step(action = ActionExecutor.ACTION_SCROLL, direction = "up", summary = "Scroll up")
        assertEquals("Scrolled up", executor.execute(step))
    }

    @Test
    fun `scroll uses value as fallback when direction is null`() {
        every { mockUi.scroll("up") } returns true
        val step = Step(action = ActionExecutor.ACTION_SCROLL, direction = null, value = "up", summary = "Scroll")
        assertEquals("Scrolled up", executor.execute(step))
        verify(exactly = 1) { mockUi.scroll("up") }
    }

    @Test
    fun `scroll defaults to down when both direction and value are null`() {
        every { mockUi.scroll("down") } returns true
        val step = Step(action = ActionExecutor.ACTION_SCROLL, direction = null, value = null, summary = "Scroll")
        assertEquals("Scrolled down", executor.execute(step))
        verify(exactly = 1) { mockUi.scroll("down") }
    }

    @Test
    fun `scroll when service returns false still returns failure string`() {
        every { mockUi.scroll("down") } returns false
        val step = Step(action = ActionExecutor.ACTION_SCROLL, direction = "down", summary = "Scroll")
        assertEquals("Scroll failed", executor.execute(step))
    }

    // -------------------------------------------------------------------------
    // extract_text
    // -------------------------------------------------------------------------

    @Test
    fun `extract_text returns joined text from service`() {
        every { mockUi.extractText() } returns listOf("Hello", "World", "Test")
        val step = Step(action = ActionExecutor.ACTION_EXTRACT_TEXT, summary = "Extract text")
        assertEquals("Hello\nWorld\nTest", executor.execute(step))
        verify(exactly = 1) { mockUi.extractText() }
    }

    @Test
    fun `extract_text with single item returns that item`() {
        every { mockUi.extractText() } returns listOf("Only item")
        val step = Step(action = ActionExecutor.ACTION_EXTRACT_TEXT, summary = "Extract text")
        assertEquals("Only item", executor.execute(step))
    }

    @Test
    fun `extract_text with empty list returns empty string`() {
        every { mockUi.extractText() } returns emptyList()
        val step = Step(action = ActionExecutor.ACTION_EXTRACT_TEXT, summary = "Extract text")
        assertEquals("", executor.execute(step))
    }

    // -------------------------------------------------------------------------
    // open_app
    // -------------------------------------------------------------------------

    @Test
    fun `open_app with valid package name and successful launch returns result string`() {
        every { mockUi.launchApp("com.google.android.apps.messaging") } returns true
        val step = Step(
            action = ActionExecutor.ACTION_OPEN_APP,
            value = "com.google.android.apps.messaging",
            summary = "Open Messages"
        )
        assertEquals("Opened app: com.google.android.apps.messaging", executor.execute(step))
        verify(exactly = 1) { mockUi.launchApp("com.google.android.apps.messaging") }
    }

    @Test
    fun `open_app when launch fails returns null`() {
        every { mockUi.launchApp(any()) } returns false
        val step = Step(
            action = ActionExecutor.ACTION_OPEN_APP,
            value = "com.unknown.app",
            summary = "Open unknown app"
        )
        assertNull(executor.execute(step))
    }

    @Test
    fun `open_app with missing value returns null without calling service`() {
        val step = Step(action = ActionExecutor.ACTION_OPEN_APP, value = null, summary = "Open app")
        assertNull(executor.execute(step))
        verify(exactly = 0) { mockUi.launchApp(any()) }
    }

    // -------------------------------------------------------------------------
    // unknown action
    // -------------------------------------------------------------------------

    @Test
    fun `unknown action returns null`() {
        val step = Step(action = "fly_to_moon", summary = "Unknown")
        assertNull(executor.execute(step))
    }

    @Test
    fun `empty action string returns null`() {
        val step = Step(action = "", summary = "Empty")
        assertNull(executor.execute(step))
    }
}
