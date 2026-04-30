package com.kaushal.automateme

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

open class AutomateAccessibilityService : AccessibilityService(), UiInteractor {

    companion object {
        private const val TAG = "AutomateA11yService"

        /** Ordered list of known SMS app packages to check as last-resort fallback. */
        private val KNOWN_SMS_PACKAGES = listOf(
            "com.truecaller",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging",
            "com.jio.myjio"
        )

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
     * Returns a map of key device app roles to their installed package names.
     * Passed to the AI so it knows which packages to use for open_app actions.
     *
     * SMS detection strategy (in order):
     *   1. Telephony.Sms.getDefaultSmsPackage() — standard system API
     *   2. Resolve ACTION_SENDTO smsto: intent — works when queries are declared
     *   3. Resolve ACTION_VIEW sms: intent
     *   4. Scan KNOWN_SMS_PACKAGES via PackageManager (requires <queries> in manifest)
     */
    fun getDeviceContext(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val smsPkg = resolveDefaultSmsPackage()
            if (!smsPkg.isNullOrEmpty()) {
                val label = getAppLabel(smsPkg)
                result["default_sms_app"] = if (label != null) "$smsPkg ($label)" else smsPkg
                Log.d(TAG, "getDeviceContext: SMS = ${result["default_sms_app"]}")
            } else {
                Log.w(TAG, "getDeviceContext: could not resolve default SMS app")
            }

            // Default dialer
            val dialerIntent = Intent(Intent.ACTION_DIAL).apply { data = Uri.parse("tel:") }
            packageManager.resolveActivity(dialerIntent, 0)?.activityInfo?.packageName?.let { pkg ->
                val label = getAppLabel(pkg)
                result["default_dialer_app"] = if (label != null) "$pkg ($label)" else pkg
            }

            // Default browser
            val browserIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://example.com")
            }
            packageManager.resolveActivity(browserIntent, 0)?.activityInfo?.packageName?.let { pkg ->
                val label = getAppLabel(pkg)
                result["default_browser_app"] = if (label != null) "$pkg ($label)" else pkg
            }
        } catch (e: Exception) {
            Log.w(TAG, "getDeviceContext: ${e.message}")
        }
        Log.d(TAG, "getDeviceContext result: $result")
        return result
    }

    /**
     * Resolves the default SMS app package using multiple fallback strategies.
     * getRoleHolders() is @SystemApi so we use public APIs only.
     */
    private fun resolveDefaultSmsPackage(): String? {
        // 1. Standard Telephony API (bypasses package visibility — uses system roles)
        try {
            val pkg = Telephony.Sms.getDefaultSmsPackage(this)
            if (!pkg.isNullOrEmpty()) {
                Log.d(TAG, "resolveDefaultSmsPackage via Telephony: $pkg")
                return pkg
            }
        } catch (e: Exception) {
            Log.w(TAG, "Telephony.Sms.getDefaultSmsPackage failed: ${e.message}")
        }

        // 2. Resolve via ACTION_SENDTO smsto: — the canonical SMS compose intent
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply { data = Uri.parse("smsto:") }
            val pkg = packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
            if (!pkg.isNullOrEmpty()) {
                Log.d(TAG, "resolveDefaultSmsPackage via ACTION_SENDTO smsto: $pkg")
                return pkg
            }
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_SENDTO smsto resolution failed: ${e.message}")
        }

        // 3. Resolve via ACTION_VIEW sms:
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply { data = Uri.parse("sms:") }
            val pkg = packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
            if (!pkg.isNullOrEmpty()) {
                Log.d(TAG, "resolveDefaultSmsPackage via ACTION_VIEW sms: $pkg")
                return pkg
            }
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_VIEW sms resolution failed: ${e.message}")
        }

        // 4. Scan known packages (requires <queries> entries in manifest)
        for (pkg in KNOWN_SMS_PACKAGES) {
            try {
                packageManager.getApplicationInfo(pkg, 0)
                Log.d(TAG, "resolveDefaultSmsPackage via known list: $pkg")
                return pkg
            } catch (e: Exception) {
                // Not visible or not installed — try next
            }
        }

        return null
    }

    private fun getAppLabel(packageName: String): String? {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            null
        }
    }

    /** Captures the current UI state: package name and list of visible texts. */
    override fun captureUiState(): Pair<String, List<String>> {
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
        if (!text.isNullOrBlank()) texts.add(text)
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank() && desc != text) texts.add(desc)
        for (i in 0 until node.childCount) {
            collectTexts(node.getChild(i), texts)
        }
    }

    /** Taps a UI node that contains the given text. */
    override fun tapText(text: String): Boolean {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "tapText: rootInActiveWindow is null")
            return false
        }
        return try {
            // 1. Standard text/contentDescription search
            var nodes = root.findAccessibilityNodeInfosByText(text)

            // 2. Fallback: traverse tree searching contentDescription (catches icon-only nav tabs)
            if (nodes.isNullOrEmpty()) {
                val descNode = findNodeByContentDescription(root, text)
                if (descNode != null) nodes = listOf(descNode)
            }

            if (nodes.isNullOrEmpty()) {
                Log.w(TAG, "tapText: no nodes found for text='$text'")
                false
            } else {
                var clicked = false
                for (node in nodes) {
                    // Walk up to 8 levels to find a clickable ancestor (handles nested nav groups)
                    if (clickNodeOrAncestor(node)) {
                        clicked = true
                        break
                    }
                }
                Log.d(TAG, "tapText: text='$text', result=$clicked")
                clicked
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * Tries to perform ACTION_CLICK on [start]. If not clickable, walks up the parent
     * chain (up to 8 levels) to find the first clickable ancestor.
     * Handles nested view groups like bottom-nav tabs where only the outer container is clickable.
     */
    private fun clickNodeOrAncestor(start: AccessibilityNodeInfo): Boolean {
        if (start.isClickable) return start.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        var ancestor: AccessibilityNodeInfo? = start.parent ?: return false
        repeat(8) {
            val current = ancestor ?: return false
            if (current.isClickable) {
                val result = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                current.recycle()
                return result
            }
            val next = current.parent
            current.recycle()
            ancestor = next
        }
        return false
    }

    /**
     * Recursively searches for a node whose contentDescription contains [desc] (case-insensitive).
     * Used as a fallback when findAccessibilityNodeInfosByText returns nothing.
     */
    private fun findNodeByContentDescription(node: AccessibilityNodeInfo?, desc: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString()?.contains(desc, ignoreCase = true) == true) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByContentDescription(child, desc)
            if (found != null) return found
        }
        return null
    }

    /** Scrolls the screen in the given direction ("down" or "up"). */
    override fun scroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "scroll: rootInActiveWindow is null")
            return false
        }
        return try {
            val scrollableNode = findScrollableNode(root)
            if (scrollableNode != null) {
                val action = if (direction.lowercase() == "up")
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
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
            val scrollable = findScrollableNode(node.getChild(i))
            if (scrollable != null) return scrollable
        }
        return null
    }

    /** Extracts all visible text from the current screen. */
    override fun extractText(): List<String> {
        val (_, texts) = captureUiState()
        return texts
    }

    /** Launches an app by its package name. */
    override fun launchApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.d(TAG, "launchApp: launched $packageName")
                true
            } else {
                Log.w(TAG, "launchApp: no launch intent for $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "launchApp: failed $packageName: ${e.message}")
            false
        }
    }
}
