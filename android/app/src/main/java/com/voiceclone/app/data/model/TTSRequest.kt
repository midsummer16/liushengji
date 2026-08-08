package com.voiceclone.app.data.model

import com.google.gson.annotations.SerializedName

data class TTSRequest(
    @SerializedName("voice_id") val voiceId: String,
    @SerializedName("text") val text: String,
    @SerializedName("speed") val speed: Float = 1.0f
)
