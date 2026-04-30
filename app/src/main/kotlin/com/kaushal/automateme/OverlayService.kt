package com.kaushal.automateme

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.kaushal.automateme.models.Step

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        const val CHANNEL_ID = "automateme_overlay"
        const val NOTIFICATION_ID = 1001

        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_TASK = "task"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var engine: ExecutionEngine? = null
    private var isAutopilot = false

    private var tvStepSummary: TextView? = null
    private var tvStepCount: TextView? = null
    private var tvStatus: TextView? = null
    private var btnStop: Button? = null
    private var btnNextStep: Button? = null
    private var btnAutopilot: Button? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        setupOverlay()
        engine = ExecutionEngine(this)
        engine?.setListener(engineListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val apiKey = intent?.getStringExtra(EXTRA_API_KEY) ?: ""
        val task = intent?.getStringExtra(EXTRA_TASK) ?: ""
        if (apiKey.isNotBlank() && task.isNotBlank()) {
            engine?.start(apiKey, task)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        engine?.stop()
        removeOverlay()
    }

    // -------------------------------------------------------------------------
    // Overlay setup
    // -------------------------------------------------------------------------

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_layout, null)

        tvStepSummary = overlayView?.findViewById(R.id.tvStepSummary)
        tvStepCount = overlayView?.findViewById(R.id.tvStepCount)
        tvStatus = overlayView?.findViewById(R.id.tvStatus)
        btnStop = overlayView?.findViewById(R.id.btnStop)
        btnNextStep = overlayView?.findViewById(R.id.btnNextStep)
        btnAutopilot = overlayView?.findViewById(R.id.btnAutopilot)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 100
        }

        windowManager?.addView(overlayView, params)
        setupDrag(params)
        setupButtons()
    }

    private fun setupDrag(params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false
        val dragThreshold = 10f

        // Attach drag only to the header area (title + step count row), not the buttons.
        // This allows buttons to receive their own click events normally.
        val dragHandle = overlayView?.findViewById<View>(R.id.overlayDragHandle)
            ?: overlayView // fallback to root if no drag handle id found

        dragHandle?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    false // don't consume — let child views (buttons) handle their own touches
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = Math.abs(event.rawX - touchX)
                    val dy = Math.abs(event.rawY - touchY)
                    if (dx > dragThreshold || dy > dragThreshold) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + (touchX - event.rawX).toInt()
                        params.y = initialY + (event.rawY - touchY).toInt()
                        windowManager?.updateViewLayout(overlayView, params)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    isDragging = false
                    false
                }
                else -> false
            }
        }
    }

    private fun setupButtons() {
        // Next Step is disabled until the AI has loaded steps
        btnNextStep?.isEnabled = false
        btnNextStep?.alpha = 0.4f

        btnStop?.setOnClickListener {
            engine?.stop()
            stopSelf()
        }

        btnNextStep?.setOnClickListener {
            engine?.executeNextStep()
        }

        btnAutopilot?.setOnClickListener {
            isAutopilot = !isAutopilot
            engine?.setAutopilot(isAutopilot)
            updateAutopilotButton()
            if (isAutopilot && engine?.isRunning() == true) {
                engine?.executeNextStep()
            }
        }
    }

    private fun setNextStepEnabled(enabled: Boolean) {
        btnNextStep?.isEnabled = enabled
        btnNextStep?.alpha = if (enabled) 1.0f else 0.4f
    }

    private fun updateAutopilotButton() {
        if (isAutopilot) {
            btnAutopilot?.text = "\u26a1 AUTO ON"
            btnAutopilot?.backgroundTintList =
                resources.getColorStateList(R.color.autopilot_on_color, theme)
        } else {
            btnAutopilot?.text = "\u26a1 Auto"
            btnAutopilot?.backgroundTintList =
                resources.getColorStateList(R.color.autopilot_off_color, theme)
        }
    }

    private fun removeOverlay() {
        try {
            if (overlayView != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Execution Engine Listener
    // -------------------------------------------------------------------------

    private val engineListener = object : ExecutionEngine.Listener {
        override fun onStepsLoaded(steps: List<Step>, totalCount: Int) {
            setNextStepEnabled(true)
            tvStepCount?.text = "Step 0/$totalCount"
            val firstSummary = steps.firstOrNull()?.summary ?: "Ready"
            tvStepSummary?.text = firstSummary
        }

        override fun onStepExecuted(step: Step, index: Int, total: Int, result: String?) {
            tvStepCount?.text = "Step $index/$total"
            val nextStep = engine?.getSteps()?.getOrNull(index)
            tvStepSummary?.text = nextStep?.summary ?: step.summary
        }

        override fun onStatusUpdate(message: String) {
            tvStatus?.text = message
        }

        override fun onError(message: String) {
            setNextStepEnabled(false)
            tvStatus?.text = "\u274c $message"
            Log.e(TAG, message)
        }

        override fun onComplete() {
            setNextStepEnabled(false)
            tvStatus?.text = "\u2705 Done"
            tvStepSummary?.text = "Automation complete"
        }

        override fun onLog(message: String) {
            Log.d(TAG, "Engine: $message")
        }

        override fun onSummary(summary: String) {
            // Show summary prominently in the step summary box (teal, larger text)
            tvStepSummary?.text = "\uD83D\uDCCB Summary:\n$summary"
            tvStatus?.text = "\u2705 AI Summary ready"
            Log.d(TAG, "Summary: $summary")
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "AutomateMe overlay service"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
