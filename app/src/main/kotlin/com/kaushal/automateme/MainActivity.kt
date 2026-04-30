package com.kaushal.automateme

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kaushal.automateme.databinding.ActivityMainBinding
import com.kaushal.automateme.network.DeepSeekApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "AutomateMePrefs"
        private const val KEY_API_KEY = "api_key"
        private const val REQUEST_OVERLAY_PERMISSION = 1001
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val logBuilder = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupUI()
        loadSavedApiKey()
    }

    override fun onResume() {
        super.onResume()
        updateStatusIndicators()
    }

    private fun setupUI() {
        binding.btnSaveApiKey.setOnClickListener {
            val key = binding.etApiKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                Toast.makeText(this, "Please enter an API key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString(KEY_API_KEY, key).apply()
            Toast.makeText(this, "API key saved \u2713", Toast.LENGTH_SHORT).show()
            addLog("API key saved")
        }

        binding.btnTestApiKey.setOnClickListener {
            val key = binding.etApiKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                Toast.makeText(this, "Please enter an API key first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnTestApiKey.isEnabled = false
            binding.btnTestApiKey.text = "Testing..."
            addLog("Testing API key...")
            lifecycleScope.launch {
                val (success, message) = withContext(Dispatchers.IO) {
                    DeepSeekApiClient.testApiKey(key)
                }
                addLog(message)
                binding.btnTestApiKey.isEnabled = true
                binding.btnTestApiKey.text = getString(R.string.test_api_key)
                Toast.makeText(this@MainActivity, message.take(80), Toast.LENGTH_LONG).show()
            }
        }

        binding.btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            addLog("Opened Accessibility Settings")
        }

        binding.btnGrantOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
                addLog("Opened overlay permission settings")
            }
        }

        binding.btnStartAutomation.setOnClickListener {
            startAutomation()
        }

        binding.btnStopAutomation.setOnClickListener {
            stopAutomation()
        }
    }

    private fun loadSavedApiKey() {
        val savedKey = prefs.getString(KEY_API_KEY, "") ?: ""
        if (savedKey.isNotBlank()) {
            binding.etApiKey.setText(savedKey)
            addLog("Loaded saved API key")
        }
    }

    private fun updateStatusIndicators() {
        val accessibilityEnabled = AutomateAccessibilityService.isRunning()
        binding.tvAccessibilityStatus.text = if (accessibilityEnabled) {
            "\u2705 Enabled"
        } else {
            "\u274c Disabled"
        }
        binding.tvAccessibilityStatus.setTextColor(
            if (accessibilityEnabled) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.holo_red_dark)
        )

        val overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
        binding.tvOverlayStatus.text = if (overlayGranted) "\u2705 Granted" else "\u274c Not granted"
        binding.tvOverlayStatus.setTextColor(
            if (overlayGranted) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.holo_red_dark)
        )
    }

    private fun startAutomation() {
        val apiKey = binding.etApiKey.text?.toString()?.trim() ?: ""
        val task = binding.etTask.text?.toString()?.trim() ?: ""

        if (apiKey.isBlank()) {
            Toast.makeText(this, "Please enter and save your API key first", Toast.LENGTH_SHORT).show()
            return
        }

        if (task.isBlank()) {
            Toast.makeText(this, "Please describe the task to automate", Toast.LENGTH_SHORT).show()
            return
        }

        if (!AutomateAccessibilityService.isRunning()) {
            Toast.makeText(this, "Please enable Accessibility Service first", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant overlay permission first", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            return
        }

        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_API_KEY, apiKey)
            putExtra(OverlayService.EXTRA_TASK, task)
        }
        startForegroundService(intent)

        addLog("Started automation: $task")
        Toast.makeText(this, "Automation started! Check the overlay.", Toast.LENGTH_SHORT).show()
    }

    private fun stopAutomation() {
        val intent = Intent(this, OverlayService::class.java)
        stopService(intent)
        addLog("Stopped automation")
        Toast.makeText(this, "Automation stopped", Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            updateStatusIndicators()
        }
    }

    private fun addLog(message: String) {
        logBuilder.insert(0, "[${currentTime()}] $message\n")
        if (logBuilder.length > 3000) {
            logBuilder.setLength(3000)
        }
        binding.tvLog.text = logBuilder.toString()
    }

    private fun currentTime(): String {
        val cal = java.util.Calendar.getInstance()
        return String.format(
            "%02d:%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND)
        )
    }
}
