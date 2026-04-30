package com.kaushal.automateme.models

import com.google.gson.annotations.SerializedName

data class Step(
    @SerializedName("action") val action: String,
    @SerializedName("value") val value: String? = null,
    @SerializedName("summary") val summary: String,
    @SerializedName("direction") val direction: String? = null
)

data class AIResponse(
    @SerializedName("steps") val steps: List<Step>
)
