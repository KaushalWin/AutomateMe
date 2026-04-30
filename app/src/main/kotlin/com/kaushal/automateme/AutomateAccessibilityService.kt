package com.kaushal.automateme

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

open class AutomateAccessibilityService : AccessibilityService(), UiInteractor {

    companion object {
        private const val TAG = "AutomateA11yService"

        /** Ordered list of known SMS app packages to check as fallback. */
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
     * Strategy (SMS):
     *   1. Telephony.Sms.getDefaultSmsPackage() — standard API
     *   2. RoleManager.getRoleHolders(ROLE_SMS) — Android 10+, OEM-safe
     *   3. Scan KNOWN_SMS_PACKAGES via PackageManager (requires <queries> in manifest)
     */
    fun getDeviceContext(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val smsPkg = resolveDefaultSmsPackage()
            if (!smsPkg.isNullOrEmpty()) {
                val label = getAppLabel(smsPkg)
                result["default_sms_app"] = if (label != null) "$smsPkg ($label)" else smsPkg
                Log.d(TAG, "getDeviceContext: SMS app = ${result["default_sms_app"]}")
            } else {
                Log.w(TAG, "getDeviceContext: could not resolve default SMS app")
            }

            // Default dialer
            val dialerIntent = Intent(Intent.ACTION_DIAL).also {
                it.data = android.net.Uri.parse("tel:")
            }
            val dialerInfo = packageManager.resolveActivity(dialerIntent, 0)
            dialerInfo?.activityInfo?.packageName?.let { pkg ->
                val label = getAppLabel(pkg)
                result["default_dialer_app"] = if (label != null) "$pkg ($label)" else pkg
            }

            // Default browser
            val browserIntent = Intent(Intent.ACTION_VIEW).also {
                it.data = android.net.Uri.parse("https://example.com")
            }
            val browserInfo = packageManager.resolveActivity(browserIntent, 0)
            browserInfo?.activityInfo?.packageName?.let { pkg ->
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
     * Resolves the default SMS app package name via multiple fallback strategies.
     */
    private fun resolveDefaultSmsPackage(): String? {
        // 1. Standard Telephony API
        try {
            val pkg = Telephony.Sms.getDefaultSmsPackage(this)
            if (!pkg.isNullOrEmpty()) {
                Log.d(TAG, "resolveDefaultSmsPackage via Telephony: $pkg")
                return pkg
            }
        } catch (e: Exception) {
            Log.w(TAG, "Telephony.Sms.getDefaultSmsPackage failed: ${e.message}")
        }

        // 2. RoleManager (Android 10+) — works on OEM ROMs that override Telephony API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roleManager = getSystemService(RoleManager::class.java)
                val holders = roleManager?.getRoleHolders(RoleManager.ROLE_SMS)
                val pkg = holders?.firstOrNull()
                if (!pkg.isNullOrEmpty()) {
                    Log.d(TAG, "resolveDefaultSmsPackage via RoleManager: $pkg")
                    return pkg
                }
            } catch (e: Exception) {
                Log.w(TAG, "RoleManager SMS lookup failed: ${e.message}")
            }
        }

        // 3. Scan known packages (requires <queries> entries in manifest)
        for (pkg in KNOWN_SMS_PACKAGES) {
            try {
                packageManager.getApplicationInfo(pkg, 0)
                Log.d(TAG, "resolveDefaultSmsPackage via known list: $pkg")
                return pkg
            } catch (e: Exception) {
                // Not installed or not visible — try next
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

    /**
     * Captures the current UI state: package name and list of visible texts.
     */
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
     */
    override fun tapText(text: String): Boolean {
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
     * Scrolls the screen in the given direction ("down" or "up").
     */
    override fun scroll(direction: String): Boolean {
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
    override fun extractText(): List<String> {
        val (_, texts) = captureUiState()
        return texts
    }

    /**
     * Launches an app by its package name.
     */
    override fun launchApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.d(TAG, "launchApp: launched $packageName")
                true
            } else {
                Log.w(TAG, "launchApp: no launch intent found for $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "launchApp: failed to launch $packageName: ${e.message}")
            false
        }
    }
}
