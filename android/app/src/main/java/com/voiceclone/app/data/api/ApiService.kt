package com.voiceclone.app.data.api

import com.voiceclone.app.data.model.TTSRequest
import com.voiceclone.app.data.model.VoiceProfile
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    @GET("api/v1/voices")
    suspend fun getVoices(): Response<List<VoiceProfile>>

    @Multipart
    @POST("api/v1/voices")
    suspend fun registerVoice(
        @Part("name") name: RequestBody,
        @Part("ref_text") refText: RequestBody,
        @Part audioFile: MultipartBody.Part,
        @Part auxFiles: List<MultipartBody.Part>? = null
    ): Response<VoiceProfile>

    @DELETE("api/v1/voices/{id}")
    suspend fun deleteVoice(
        @Path("id") id: String
    ): Response<Map<String, String>>

    @POST("api/v1/tts")
    @Streaming
    suspend fun synthesizeSpeech(
        @Body request: TTSRequest
    ): Response<ResponseBody>
}
