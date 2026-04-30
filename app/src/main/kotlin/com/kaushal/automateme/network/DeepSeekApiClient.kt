package com.kaushal.automateme.network

import android.util.Log
import com.google.gson.Gson
import com.kaushal.automateme.models.AIResponse
import com.kaushal.automateme.models.ChatMessage
import com.kaushal.automateme.models.DeepSeekRequest
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
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

    suspend fun getAutomationSteps(
        apiKey: String,
        appPackage: String,
        visibleTexts: List<String>,
        taskDescription: String
    ): AIResponse? {
        return try {
            val uiStateJson = """
                {
                  "app": "$appPackage",
                  "task": "$taskDescription",
                  "visible_text": ${gson.toJson(visibleTexts)}
                }
            """.trimIndent()

            val systemPrompt = """
                You are an Android UI automation assistant. Given the current UI state, suggest the next automation steps.
                
                IMPORTANT: You MUST respond with ONLY valid JSON in this exact format:
                {
                  "steps": [
                    {
                      "action": "tap_text",
                      "value": "button text to tap",
                      "summary": "Brief description"
                    }
                  ]
                }
                
                Available actions:
                - tap_text: tap on a UI element by its text (requires "value")
                - scroll: scroll the screen (requires "direction": "up" or "down")
                - extract_text: read all visible text on screen
                
                Rules:
                - Maximum 10 steps
                - Only use text visible on screen for tap_text
                - Keep summaries under 10 words
                - Return ONLY JSON, no other text
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

            val content = response.choices.firstOrNull()?.message?.content
            Log.d(TAG, "AI Response: $content")

            if (content != null) {
                gson.fromJson(content, AIResponse::class.java)
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
