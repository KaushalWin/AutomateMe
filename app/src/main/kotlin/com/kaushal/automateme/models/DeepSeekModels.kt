package com.kaushal.automateme.models

import com.google.gson.annotations.SerializedName

data class DeepSeekRequest(
    @SerializedName("model") val model: String = "deepseek-chat",
    @SerializedName("messages") val messages: List<ChatMessage>,
    @SerializedName("response_format") val responseFormat: ResponseFormat = ResponseFormat()
)

data class ChatMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

data class ResponseFormat(
    @SerializedName("type") val type: String = "json_object"
)

data class DeepSeekResponse(
    @SerializedName("id") val id: String? = null,
    @SerializedName("choices") val choices: List<Choice> = emptyList()
)

data class Choice(
    @SerializedName("message") val message: ChatMessage,
    @SerializedName("finish_reason") val finishReason: String? = null
)
