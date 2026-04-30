package com.kaushal.automateme

/**
 * Abstraction over [AutomateAccessibilityService] for UI interaction.
 * Using an interface here keeps [ActionExecutor] decoupled from the Android
 * framework so it can be tested with plain JVM unit tests.
 */
interface UiInteractor {
    fun tapText(text: String): Boolean
    fun scroll(direction: String): Boolean
    fun extractText(): List<String>
    fun captureUiState(): Pair<String, List<String>>
}
