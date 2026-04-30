package com.kaushal.automateme.network

import android.util.Log
import com.google.gson.Gson
import com.kaushal.automateme.models.AIResponse
import com.kaushal.automateme.models.ChatMessage
import com.kaushal.automateme.models.DeepSeekRequest
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object DeepSeekApiClient {

    private const val TAG = "DeepSeekApiClient"
    private const val BASE_URL = "https://api.deepseek.com/"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val service: DeepSeekApiService = retrofit.create(DeepSeekApiService::class.java)

    private val gson = Gson()

    /**
     * Tests whether the given API key is valid by making a minimal JSON completion request.
     * NOTE: response_format=json_object requires the prompt to explicitly ask for JSON.
     */
    suspend fun testApiKey(apiKey: String): Pair<Boolean, String> {
        return try {
            val messages = listOf(
                ChatMessage(role = "user", content = "Respond with JSON: {\"status\": \"ok\"}")
            )
            val request = DeepSeekRequest(messages = messages)
            val response = service.getCompletion(
                authorization = "Bearer $apiKey",
                request = request
            )
            val content = response.choices.firstOrNull()?.message?.content
            if (!content.isNullOrBlank()) {
                Pair(true, "\u2705 API key is valid!")
            } else {
                Pair(false, "\u274c API returned empty response")
            }
        } catch (e: HttpException) {
            val msg = when (e.code()) {
                401 -> "\u274c Invalid API key (401 Unauthorized)"
                402 -> "\u274c Insufficient balance (402 Payment Required)"
                429 -> "\u274c Rate limit exceeded (429)"
                else -> "\u274c HTTP error ${e.code()}: ${e.message()}"
            }
            Log.w(TAG, "testApiKey HTTP error: ${e.code()}")
            Pair(false, msg)
        } catch (e: Exception) {
            Log.w(TAG, "testApiKey failed: ${e.message}")
            Pair(false, "\u274c Connection failed: ${e.message}")
        }
    }

    /**
     * @param deviceContext  Map of role -> "package (App Label)" detected at runtime.
     *                       e.g. {"default_sms_app": "com.truecaller (Truecaller)"}
     *                       Injected into the prompt so the AI picks the right package for open_app.
     */
    suspend fun getAutomationSteps(
        apiKey: String,
        appPackage: String,
        visibleTexts: List<String>,
        taskDescription: String,
        deviceContext: Map<String, String> = emptyMap()
    ): AIResponse? {
        return try {
            val deviceAppsJson = if (deviceContext.isNotEmpty()) {
                gson.toJson(deviceContext)
            } else {
                "{}"
            }

            val uiStateJson = """
                {
                  "app": "$appPackage",
                  "task": "$taskDescription",
                  "visible_text": ${gson.toJson(visibleTexts)},
                  "device_apps": $deviceAppsJson
                }
            """.trimIndent()

            val systemPrompt = """
                You are an Android UI automation assistant. Given the current UI state, suggest the next automation steps.
                
                IMPORTANT: You MUST respond with ONLY valid JSON — no markdown, no code blocks, no extra text.
                Use this exact format:
                {"steps":[{"action":"tap_text","value":"button text","summary":"Brief description"}]}
                
                Available actions:
                - tap_text: tap on a UI element by its visible text (requires "value")
                - scroll: scroll the screen (requires "direction": "up" or "down")
                - extract_text: read all visible text on screen
                - open_app: launch an app by package name (requires "value": package name)
                
                IMPORTANT for open_app:
                - Always use the package name from "device_apps" in the UI state when available.
                - For SMS: use the value of "default_sms_app" from device_apps.
                - For phone calls: use the value of "default_dialer_app" from device_apps.
                - For web browsing: use the value of "default_browser_app" from device_apps.
                - Strip any " (App Label)" suffix — use only the package name before the space.
                
                Rules:
                - Maximum 10 steps
                - Only use text visible on screen for tap_text
                - Keep summaries under 10 words
                - Return ONLY the JSON object, nothing else
            """.trimIndent()

            val messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = "Current UI state:\n$uiStateJson\n\nProvide automation steps as JSON.")
            )

            val request = DeepSeekRequest(messages = messages)
            val response = service.getCompletion(
                authorization = "Bearer $apiKey",
                request = request
            )

            val rawContent = response.choices.firstOrNull()?.message?.content
            Log.d(TAG, "AI Raw Response: $rawContent")

            if (rawContent != null) {
                // Strip markdown code fences the model may emit despite instructions
                val cleanContent = rawContent
                    .trim()
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()
                Log.d(TAG, "AI Clean Response: $cleanContent")
                gson.fromJson(cleanContent, AIResponse::class.java)
            } else {
                Log.e(TAG, "Empty response from API")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calling DeepSeek API: ${e.message}", e)
            null
        }
    }
}
