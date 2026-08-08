package com.voiceclone.app.data.model

import com.google.gson.annotations.SerializedName

data class VoiceProfile(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("ref_text") val refText: String,
    @SerializedName("audio_path") val audioPath: String,
    @SerializedName("aux_audio_paths") val auxAudioPaths: List<String> = emptyList(),
    @SerializedName("created_at") val createdAt: String
)
