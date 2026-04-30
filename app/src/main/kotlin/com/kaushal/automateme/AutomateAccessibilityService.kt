package com.kaushal.automateme

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutomateAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutomateA11yService"

        @Volatile
        var instance: AutomateAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    var currentPackage: String = ""
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        serviceInfo = info
        Log.d(TAG, "Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.let {
            currentPackage = it.toString()
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.d(TAG, "Accessibility Service unbound")
        return super.onUnbind(intent)
    }

    /**
     * Captures the current UI state: package name and list of visible texts.
     */
    fun captureUiState(): Pair<String, List<String>> {
        val texts = mutableListOf<String>()
        val root = rootInActiveWindow ?: return Pair(currentPackage, texts)

        try {
            collectTexts(root, texts)
        } finally {
            root.recycle()
        }

        Log.d(TAG, "Captured UI state: $currentPackage with ${texts.size} text nodes")
        return Pair(currentPackage, texts)
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, texts: MutableList<String>) {
        if (node == null) return
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) {
            texts.add(text)
        }
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank() && desc != text) {
            texts.add(desc)
        }
        for (i in 0 until node.childCount) {
            collectTexts(node.getChild(i), texts)
        }
    }

    /**
     * Taps a UI node that contains the given text.
     * Returns true if successful.
     */
    fun tapText(text: String): Boolean {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "tapText: rootInActiveWindow is null")
            return false
        }
        return try {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (nodes.isNullOrEmpty()) {
                Log.w(TAG, "tapText: no nodes found for text='$text'")
                false
            } else {
                var clicked = false
                for (node in nodes) {
                    if (node.isClickable) {
                        clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.d(TAG, "tapText: clicked node with text='$text', result=$clicked")
                        if (clicked) break
                    } else {
                        val parent = node.parent
                        if (parent != null && parent.isClickable) {
                            clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.d(TAG, "tapText: clicked parent for text='$text', result=$clicked")
                            parent.recycle()
                            if (clicked) break
                        }
                    }
                }
                clicked
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * Scrolls the screen in the given direction.
     * direction: "down" or "up"
     */
    fun scroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "scroll: rootInActiveWindow is null")
            return false
        }
        return try {
            val scrollableNode = findScrollableNode(root)
            if (scrollableNode != null) {
                val action = if (direction.lowercase() == "up") {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                val result = scrollableNode.performAction(action)
                Log.d(TAG, "scroll: direction=$direction, result=$result")
                result
            } else {
                Log.w(TAG, "scroll: no scrollable node found")
                false
            }
        } finally {
            root.recycle()
        }
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val scrollable = findScrollableNode(child)
            if (scrollable != null) return scrollable
        }
        return null
    }

    /**
     * Extracts all visible text from the current screen.
     */
    fun extractText(): List<String> {
        val (_, texts) = captureUiState()
        return texts
    }
}
