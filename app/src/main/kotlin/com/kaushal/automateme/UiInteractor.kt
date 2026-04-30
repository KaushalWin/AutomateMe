package com.kaushal.automateme

interface UiInteractor {
    fun tapText(text: String): Boolean
    fun scroll(direction: String): Boolean
    fun extractText(): List<String>
    fun captureUiState(): Pair<String, List<String>>
    fun launchApp(packageName: String): Boolean
}
