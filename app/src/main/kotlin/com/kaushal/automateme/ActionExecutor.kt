package com.kaushal.automateme

import android.util.Log
import com.kaushal.automateme.models.Step

class ActionExecutor(private val service: UiInteractor) {

    companion object {
        private const val TAG = "ActionExecutor"
        const val ACTION_TAP_TEXT = "tap_text"
        const val ACTION_SCROLL = "scroll"
        const val ACTION_EXTRACT_TEXT = "extract_text"
    }

    /**
     * Executes a single step. Returns the result as a string (for logging / extract_text).
     * Returns null on failure.
     */
    fun execute(step: Step): String? {
        Log.d(TAG, "Executing step: action=${step.action}, value=${step.value}, summary=${step.summary}")
        return when (step.action) {
            ACTION_TAP_TEXT -> executeTapText(step)
            ACTION_SCROLL -> executeScroll(step)
            ACTION_EXTRACT_TEXT -> executeExtractText()
            else -> {
                Log.w(TAG, "Unknown action: ${step.action}")
                null
            }
        }
    }

    private fun executeTapText(step: Step): String? {
        val value = step.value
        if (value.isNullOrBlank()) {
            Log.w(TAG, "tap_text: missing value")
            return null
        }
        val success = service.tapText(value)
        return if (success) "Tapped: $value" else null
    }

    private fun executeScroll(step: Step): String {
        val direction = step.direction ?: step.value ?: "down"
        val success = service.scroll(direction)
        return if (success) "Scrolled $direction" else "Scroll failed"
    }

    private fun executeExtractText(): String {
        val texts = service.extractText()
        val result = texts.joinToString("\n")
        Log.d(TAG, "Extracted text: $result")
        return result
    }
}
