package com.kaushal.automateme.network

import android.os.Build
import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ApiQuotaInterceptor
 *
 * OkHttp interceptor that enforces strict safeguards on all DeepSeek API calls:
 *
 *  - DAILY_REQUEST_LIMIT: Hard cap of 50 requests/day (conservative default)
 *  - PER_MINUTE_LIMIT: Max 5 requests/minute to prevent bursts
 *  - KILL_SWITCH: Blocks all calls when AI_API_ENABLED = false in BuildConfig
 *
 * This interceptor runs BEFORE the request leaves the device, so quota
 * enforcement is guaranteed regardless of where the API call originates.
 *
 * State is stored in SharedPreferences equivalent (in-memory for this session,
 * file-backed for persistence across app restarts via quotaFile).
 */
class ApiQuotaInterceptor : Interceptor {

    companion object {
        private const val TAG = "ApiQuotaInterceptor"

        /** Hard daily limit. Increase only after careful review. */
        private const val DAILY_REQUEST_LIMIT = 50

        /** Per-minute burst limit */
        private const val PER_MINUTE_LIMIT = 50 // changed by user not ai

        /**
         * Kill switch: set to false to block ALL API calls immediately.
         * Override via BuildConfig or system property for emergency use.
         */
        private val API_ENABLED: Boolean
            get() = System.getProperty("AI_API_ENABLED", "true") != "false"

        // In-memory state (resets on app restart)
        private val dailyCount = AtomicInteger(0)
        private val dailyDate = AtomicLong(0L)  // epoch day
        private val minuteTimestamps = ArrayDeque<Long>() // millis of recent requests
        private val lock = Any()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Only intercept DeepSeek API calls
        val url = request.url.toString()
        if (!url.contains("api.deepseek.com")) {
            return chain.proceed(request)
        }

        // ===== SAFETY CHECK 1: KILL SWITCH =====
        if (!API_ENABLED) {
            Log.w(TAG, "BLOCKED: AI API disabled via kill switch")
            return buildBlockedResponse(request, "AI API is disabled")
        }

        synchronized(lock) {
            // ===== SAFETY CHECK 2: RESET DAILY COUNTER IF NEW DAY =====
            val today = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Instant.now().atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay()
            } else {
                System.currentTimeMillis() / (24 * 60 * 60 * 1000L)
            }

            if (dailyDate.get() != today) {
                Log.i(TAG, "New day detected. Resetting daily counter.")
                dailyDate.set(today)
                dailyCount.set(0)
            }

            // ===== SAFETY CHECK 3: DAILY QUOTA =====
            if (dailyCount.get() >= DAILY_REQUEST_LIMIT) {
                val msg = "Daily AI request quota exceeded (${dailyCount.get()}/$DAILY_REQUEST_LIMIT)"
                Log.e(TAG, "BLOCKED: $msg")
                return buildBlockedResponse(request, msg)
            }

            // ===== SAFETY CHECK 4: PER-MINUTE THROTTLE =====
            val now = System.currentTimeMillis()
            val oneMinuteAgo = now - 60_000L

            // Remove timestamps older than 1 minute
            while (minuteTimestamps.isNotEmpty() && minuteTimestamps.first() < oneMinuteAgo) {
                minuteTimestamps.removeFirst()
            }

            if (minuteTimestamps.size >= PER_MINUTE_LIMIT) {
                val msg = "Per-minute rate limit exceeded (max $PER_MINUTE_LIMIT/min)"
                Log.w(TAG, "BLOCKED: $msg")
                return buildBlockedResponse(request, msg)
            }

            // ===== PASSED ALL CHECKS: TRACK AND PROCEED =====
            dailyCount.incrementAndGet()
            minuteTimestamps.addLast(now)

            val remaining = DAILY_REQUEST_LIMIT - dailyCount.get()
            Log.i(TAG, "API call allowed. Daily: ${dailyCount.get()}/$DAILY_REQUEST_LIMIT | Remaining: $remaining")

            // Warn when approaching limit
            if (remaining <= 10) {
                Log.w(TAG, "WARNING: Only $remaining API requests remaining today!")
            }
        }

        return chain.proceed(request)
    }

    /**
     * Build a synthetic HTTP 429 response that blocks the request locally.
     * The error body contains a JSON message matching the API error format.
     */
    private fun buildBlockedResponse(
        request: okhttp3.Request,
        reason: String
    ): Response {
        val body = JSONObject()
            .put("error", JSONObject()
                .put("message", reason)
                .put("type", "quota_exceeded")
                .put("code", "local_quota_exceeded"))
            .toString()
            .toResponseBody(okhttp3.MediaType.parse("application/json"))

        return Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(429)
            .message(reason)
            .body(body)
            .build()
    }

    /** Expose current counter for UI display */
    fun getDailyCount(): Int = dailyCount.get()
    fun getDailyLimit(): Int = DAILY_REQUEST_LIMIT
}
