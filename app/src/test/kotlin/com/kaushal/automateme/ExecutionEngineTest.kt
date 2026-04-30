package com.kaushal.automateme

import android.content.Context
import com.kaushal.automateme.models.Step
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ExecutionEngine] state management.
 * Network calls and accessibility service interaction are not exercised here;
 * those are covered by instrumented tests.
 */
class ExecutionEngineTest {

    private lateinit var engine: ExecutionEngine
    private val mockContext: Context = mockk(relaxed = true)

    /** Captures listener callbacks for assertion. */
    private inner class RecordingListener : ExecutionEngine.Listener {
        val logs = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val statusUpdates = mutableListOf<String>()
        val summaries = mutableListOf<String>()
        var completeCalled = false

        override fun onStepsLoaded(steps: List<Step>, totalCount: Int) {}
        override fun onStepExecuted(step: Step, index: Int, total: Int, result: String?) {}
        override fun onStatusUpdate(message: String) { statusUpdates.add(message) }
        override fun onError(message: String) { errors.add(message) }
        override fun onComplete() { completeCalled = true }
        override fun onLog(message: String) { logs.add(message) }
        override fun onSummary(summary: String) { summaries.add(summary) }
    }

    @Before
    fun setUp() {
        // ExecutionEngine creates CoroutineScope(Dispatchers.Main + Job()) in its constructor.
        // Dispatchers.Main is unavailable on JVM by default, so we install a test dispatcher first.
        Dispatchers.setMain(StandardTestDispatcher())
        engine = ExecutionEngine(mockContext)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -------------------------------------------------------------------------
    // Initial state
    // -------------------------------------------------------------------------

    @Test
    fun `initial state - not running`() {
        assertFalse(engine.isRunning())
    }

    @Test
    fun `initial state - autopilot off`() {
        assertFalse(engine.isAutopilot())
    }

    @Test
    fun `initial currentIndex is 0`() {
        assertEquals(0, engine.getCurrentIndex())
    }

    @Test
    fun `initial steps list is empty`() {
        assertTrue(engine.getSteps().isEmpty())
    }

    // -------------------------------------------------------------------------
    // Autopilot toggle
    // -------------------------------------------------------------------------

    @Test
    fun `setAutopilot true enables autopilot`() {
        engine.setAutopilot(true)
        assertTrue(engine.isAutopilot())
    }

    @Test
    fun `setAutopilot false disables autopilot`() {
        engine.setAutopilot(true)
        engine.setAutopilot(false)
        assertFalse(engine.isAutopilot())
    }

    @Test
    fun `setAutopilot emits log message`() {
        val listener = RecordingListener()
        engine.setListener(listener)
        engine.setAutopilot(true)
        assertTrue(listener.logs.any { it.contains("enabled", ignoreCase = true) })
    }

    // -------------------------------------------------------------------------
    // Stop
    // -------------------------------------------------------------------------

    @Test
    fun `stop when not running still fires onComplete`() {
        val listener = RecordingListener()
        engine.setListener(listener)
        engine.stop()
        assertTrue(listener.completeCalled)
    }

    @Test
    fun `stop sets isRunning to false`() {
        engine.stop()
        assertFalse(engine.isRunning())
    }

    @Test
    fun `stop disables autopilot`() {
        engine.setAutopilot(true)
        engine.stop()
        assertFalse(engine.isAutopilot())
    }

    @Test
    fun `stop emits Stopped status update`() {
        val listener = RecordingListener()
        engine.setListener(listener)
        engine.stop()
        assertTrue(listener.statusUpdates.any { it.contains("Stopped", ignoreCase = true) })
    }

    // -------------------------------------------------------------------------
    // executeNextStep when not running
    // -------------------------------------------------------------------------

    @Test
    fun `executeNextStep when not running does not crash`() {
        // Should just log and return without errors
        val listener = RecordingListener()
        engine.setListener(listener)
        engine.executeNextStep()
        assertTrue(listener.errors.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    @Test
    fun `MAX_STEPS constant is 15`() {
        assertEquals(15, ExecutionEngine.MAX_STEPS)
    }

    @Test
    fun `STEP_DELAY_MS constant is 500`() {
        assertEquals(500L, ExecutionEngine.STEP_DELAY_MS)
    }

    // -------------------------------------------------------------------------
    // Listener management
    // -------------------------------------------------------------------------

    @Test
    fun `null listener does not cause NPE on stop`() {
        engine.setListener(null)
        engine.stop() // must not throw
    }

    @Test
    fun `replacing listener mid-session is safe`() {
        val first = RecordingListener()
        val second = RecordingListener()
        engine.setListener(first)
        engine.setListener(second)
        engine.stop()
        assertFalse(first.completeCalled) // first listener no longer receives events
        assertTrue(second.completeCalled)
    }
}
