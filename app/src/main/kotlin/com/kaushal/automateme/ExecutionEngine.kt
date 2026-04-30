package com.kaushal.automateme

import android.content.Context
import android.util.Log
import com.kaushal.automateme.models.Step
import com.kaushal.automateme.network.DeepSeekApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ExecutionEngine(private val context: Context) {

    companion object {
        private const val TAG = "ExecutionEngine"
        const val MAX_STEPS = 15
        const val STEP_DELAY_MS = 500L
    }

    interface Listener {
        fun onStepsLoaded(steps: List<Step>, totalCount: Int)
        fun onStepExecuted(step: Step, index: Int, total: Int, result: String?)
        fun onStatusUpdate(message: String)
        fun onError(message: String)
        fun onComplete()
        fun onLog(message: String)
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var steps: List<Step> = emptyList()
    private var currentStepIndex = 0
    private var isRunning = false
    private var stepsLoaded = false
    private var isAutopilot = false
    private var listener: Listener? = null
    private var executionJob: Job? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun isRunning() = isRunning
    fun isAutopilot() = isAutopilot
    fun getCurrentIndex() = currentStepIndex
    fun getSteps() = steps

    fun setAutopilot(enabled: Boolean) {
        isAutopilot = enabled
        log("Autopilot ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Loads steps from DeepSeek AI and prepares for execution.
     */
    fun start(apiKey: String, taskDescription: String) {
        if (isRunning) {
            log("Already running")
            return
        }
        val accessibilityService = AutomateAccessibilityService.instance
        if (accessibilityService == null) {
            listener?.onError("Accessibility service not running. Please enable it in settings.")
            return
        }

        isRunning = true
        stepsLoaded = false
        currentStepIndex = 0
        steps = emptyList()

        listener?.onStatusUpdate("Capturing UI state...")
        log("Starting automation: $taskDescription")

        scope.launch(Dispatchers.IO) {
            try {
                val (appPackage, visibleTexts) = accessibilityService.captureUiState()
                log("UI state captured: $appPackage, ${visibleTexts.size} text nodes")

                scope.launch(Dispatchers.Main) {
                    listener?.onStatusUpdate("Asking AI for steps...")
                }

                val aiResponse = DeepSeekApiClient.getAutomationSteps(
                    apiKey = apiKey,
                    appPackage = appPackage,
                    visibleTexts = visibleTexts,
                    taskDescription = taskDescription
                )

                scope.launch(Dispatchers.Main) {
                    if (aiResponse == null || aiResponse.steps.isEmpty()) {
                        listener?.onError("No steps received from AI. Check API key and network.")
                        isRunning = false
                        stepsLoaded = false
                        return@launch
                    }

                    steps = aiResponse.steps.take(MAX_STEPS)
                    stepsLoaded = true
                    log("Received ${steps.size} steps from AI")
                    listener?.onStepsLoaded(steps, steps.size)
                    listener?.onStatusUpdate(
                        "Steps loaded. ${if (isAutopilot) "Running autopilot..." else "Tap ▶ Next to execute."}"
                    )

                    if (isAutopilot) {
                        runAutopilot()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in start: ${e.message}", e)
                scope.launch(Dispatchers.Main) {
                    listener?.onError("Error: ${e.message}")
                    isRunning = false
                    stepsLoaded = false
                }
            }
        }
    }

    /**
     * Executes the next step manually.
     * If steps have not loaded yet (AI is still responding), shows a waiting message.
     */
    fun executeNextStep() {
        if (!isRunning) {
            log("Not running")
            return
        }
        if (!stepsLoaded) {
            log("Steps not loaded yet, waiting for AI response")
            listener?.onStatusUpdate("⏳ Waiting for AI response...")
            return
        }
        if (currentStepIndex >= steps.size) {
            log("All steps completed")
            isRunning = false
            listener?.onComplete()
            return
        }

        val accessibilityService = AutomateAccessibilityService.instance
        if (accessibilityService == null) {
            listener?.onError("Accessibility service not available")
            return
        }

        val step = steps[currentStepIndex]
        log("Executing step ${currentStepIndex + 1}/${steps.size}: ${step.summary}")
        listener?.onStatusUpdate("Executing: ${step.summary}")

        scope.launch(Dispatchers.IO) {
            try {
                val executor = ActionExecutor(accessibilityService)
                val result = executor.execute(step)
                delay(STEP_DELAY_MS)

                scope.launch(Dispatchers.Main) {
                    val stepIndex = currentStepIndex
                    currentStepIndex++
                    listener?.onStepExecuted(step, stepIndex + 1, steps.size, result)

                    if (result == null) {
                        log("Step ${stepIndex + 1} failed: ${step.action}")
                        listener?.onStatusUpdate("Step failed. Try next step.")
                    } else {
                        log("Step ${stepIndex + 1} result: $result")
                    }

                    if (currentStepIndex >= steps.size) {
                        log("All steps executed")
                        isRunning = false
                        listener?.onComplete()
                    } else {
                        listener?.onStatusUpdate(
                            if (isAutopilot) "Running autopilot..."
                            else "Step done. Tap ▶ Next to continue."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing step: ${e.message}", e)
                scope.launch(Dispatchers.Main) {
                    listener?.onError("Step error: ${e.message}")
                }
            }
        }
    }

    /**
     * Runs steps automatically with delay.
     */
    private fun runAutopilot() {
        executionJob = scope.launch {
            while (isRunning && isAutopilot && currentStepIndex < steps.size) {
                val indexBefore = currentStepIndex
                executeNextStep()
                // Wait for step to complete (up to 3 seconds)
                var waited = 0
                while (waited < 30 && currentStepIndex == indexBefore && isRunning) {
                    delay(100L)
                    waited++
                }
                delay(300L) // extra delay between steps
            }
        }
    }

    /**
     * Stops execution immediately.
     */
    fun stop() {
        isRunning = false
        isAutopilot = false
        stepsLoaded = false
        executionJob?.cancel()
        log("Automation stopped")
        listener?.onStatusUpdate("Stopped.")
        listener?.onComplete()
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        listener?.onLog(message)
    }
}
