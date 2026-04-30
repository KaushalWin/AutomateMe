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
        private const val OPEN_APP_WAIT_MS = 2500L
        /** Wait after a tap before re-querying AI so the new screen has time to settle. */
        private const val TAP_NAV_WAIT_MS = 1200L
    }

    interface Listener {
        fun onStepsLoaded(steps: List<Step>, totalCount: Int)
        fun onStepExecuted(step: Step, index: Int, total: Int, result: String?)
        fun onStatusUpdate(message: String)
        fun onError(message: String)
        fun onComplete()
        fun onLog(message: String)
        fun onSummary(summary: String)
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var steps: List<Step> = emptyList()
    private var currentStepIndex = 0
    private var isRunning = false
    private var stepsLoaded = false
    private var isAutopilot = false
    private var listener: Listener? = null
    private var executionJob: Job? = null

    private var savedApiKey = ""
    private var savedTask = ""
    private var extractedTextBuffer = StringBuilder()

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
        savedApiKey = apiKey
        savedTask = taskDescription
        extractedTextBuffer = StringBuilder()

        listener?.onStatusUpdate("Capturing UI state...")
        log("Starting automation: $taskDescription")

        scope.launch(Dispatchers.IO) {
            try {
                val (appPackage, visibleTexts) = accessibilityService.captureUiState()
                // Detect installed default apps so AI knows which packages to use
                val deviceContext = accessibilityService.getDeviceContext()
                log("UI state captured: $appPackage, ${visibleTexts.size} text nodes")
                log("Device context: $deviceContext")

                scope.launch(Dispatchers.Main) {
                    listener?.onStatusUpdate("Asking AI for steps...")
                }

                val aiResponse = DeepSeekApiClient.getAutomationSteps(
                    apiKey = apiKey,
                    appPackage = appPackage,
                    visibleTexts = visibleTexts,
                    taskDescription = taskDescription,
                    deviceContext = deviceContext
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
                        "Steps loaded. ${if (isAutopilot) "Running autopilot..." else "Tap \u25b6 Next to execute."}"
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
            finalizeSummary()
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

                // After open_app or a navigation tap, wait for the UI to settle then re-query AI
                var newSteps: List<Step>? = null
                val isNavTap = step.action == ActionExecutor.ACTION_TAP_TEXT && result != null &&
                    step.summary?.let { s ->
                        val lower = s.lowercase()
                        lower.contains("tab") || lower.contains("open") ||
                        lower.contains("messages") || lower.contains("navigate") ||
                        lower.contains("go to") || lower.contains("inbox")
                    } == true
                if ((step.action == ActionExecutor.ACTION_OPEN_APP && result != null) || isNavTap) {
                    val waitMs = if (step.action == ActionExecutor.ACTION_OPEN_APP) OPEN_APP_WAIT_MS else TAP_NAV_WAIT_MS
                    log("Re-querying AI after ${if (isNavTap) "navigation tap" else "opening ${step.value}"}...")
                    delay(waitMs)
                    val (appPackage, visibleTexts) = accessibilityService.captureUiState()
                    val deviceContext = accessibilityService.getDeviceContext()
                    newSteps = DeepSeekApiClient.getAutomationSteps(
                        apiKey = savedApiKey,
                        appPackage = appPackage,
                        visibleTexts = visibleTexts,
                        taskDescription = savedTask,
                        deviceContext = deviceContext
                    )?.steps?.take(MAX_STEPS)
                }

                scope.launch(Dispatchers.Main) {
                    val stepIndex = currentStepIndex
                    currentStepIndex++

                    if (step.action == ActionExecutor.ACTION_EXTRACT_TEXT && result != null) {
                        extractedTextBuffer.append(result).append("\n")
                    }

                    if (newSteps != null) {
                        // Replace all pending steps with the fresh AI response
                        steps = steps.take(currentStepIndex) + newSteps
                        stepsLoaded = true
                        listener?.onStepsLoaded(steps, steps.size)
                        log("Re-queried AI: ${newSteps.size} new steps after open_app")
                    }

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
                        finalizeSummary()
                    } else {
                        listener?.onStatusUpdate(
                            if (isAutopilot) "Running autopilot..."
                            else "Step done. Tap \u25b6 Next to continue."
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
                // Wait up to 15 seconds for step to complete (open_app + AI re-query can take ~8s)
                var waited = 0
                while (waited < 150 && currentStepIndex == indexBefore && isRunning) {
                    delay(100L)
                    waited++
                }
                delay(300L) // extra delay between steps
            }
        }
    }

    /**
     * Called when all steps have been executed. Emits onComplete and, if any text was
     * extracted during the run, requests a DeepSeek summary and emits it via onSummary.
     */
    private fun finalizeSummary() {
        listener?.onComplete()
        val extracted = extractedTextBuffer.toString().trim()
        extractedTextBuffer = StringBuilder() // clear to prevent duplicate triggers
        if (extracted.isNotEmpty()) {
            listener?.onStatusUpdate("Summarizing extracted text...")
            scope.launch(Dispatchers.IO) {
                val summary = DeepSeekApiClient.summarizeText(
                    apiKey = savedApiKey,
                    extractedText = extracted,
                    task = savedTask
                )
                scope.launch(Dispatchers.Main) {
                    if (summary != null) {
                        log("Summary: $summary")
                        listener?.onSummary(summary)
                    }
                }
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
