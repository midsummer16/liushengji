package com.voiceclone.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voiceclone.app.audio.AudioRecorder
import com.voiceclone.app.audio.StreamAudioPlayer
import com.voiceclone.app.data.api.NetworkClient
import com.voiceclone.app.data.model.TTSRequest
import com.voiceclone.app.data.model.VoiceProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatMessage(
    val id: String,
    val text: String,
    val isUser: Boolean,
    val voiceId: String = "",
    var status: MessageStatus = MessageStatus.LOADING,
    var errorText: String? = null
)

enum class MessageStatus {
    LOADING,
    PLAYING,
    COMPLETED,
    ERROR
}

private fun extractErrorDetail(response: retrofit2.Response<*>): String {
    val raw = response.errorBody()?.string()
    val detail = if (!raw.isNullOrBlank()) {
        try {
            JSONObject(raw).optString("detail")
        } catch (_: Exception) {
            ""
        }
    } else ""
    return detail.ifBlank { response.message() }
}

class VoiceViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "voice_clone_chat_prefs"
        private const val KEY_CHAT_HISTORY = "chat_history"
        private const val MAX_PERSISTED_MESSAGES = 50
    }

    private val _voices = MutableStateFlow<List<VoiceProfile>>(emptyList())
    val voices: StateFlow<List<VoiceProfile>> = _voices

    private val _isFetching = MutableStateFlow(false)
    val isFetching: StateFlow<Boolean> = _isFetching

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading

    val isLoading: StateFlow<Boolean> = combine(_isFetching, _isUploading) { fetching, uploading ->
        fetching || uploading
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _isUnconfigured = MutableStateFlow(false)
    val isUnconfigured: StateFlow<Boolean> = _isUnconfigured

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _selectedVoice = MutableStateFlow<VoiceProfile?>(null)
    val selectedVoice: StateFlow<VoiceProfile?> = _selectedVoice

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages

    private val _recordingAmplitude = MutableStateFlow(0f)
    val recordingAmplitude: StateFlow<Float> = _recordingAmplitude

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    val audioRecorder = AudioRecorder(application)
    val streamAudioPlayer = StreamAudioPlayer(viewModelScope)

    init {
        audioRecorder.onAmplitudeListener = { amp ->
            _recordingAmplitude.value = amp
        }
        // 启动时恢复最近聊天历史,避免用户回到应用看不到之前对话。
        _chatMessages.value = loadChatHistory()
        // Only fetch if a server has been configured. Otherwise surface an
        // "unconfigured" state so the UI can prompt the user to set the URL.
        if (NetworkClient.getBaseUrl().isNotBlank()) {
            fetchVoices()
        } else {
            _isUnconfigured.value = true
        }
    }

    fun fetchVoices() {
        if (NetworkClient.getBaseUrl().isBlank()) {
            _isUnconfigured.value = true
            return
        }
        _isUnconfigured.value = false
        viewModelScope.launch {
            _isFetching.value = true
            try {
                val response = NetworkClient.getApiService(getApplication()).getVoices()
                if (response.isSuccessful && response.body() != null) {
                    _voices.value = response.body()!!
                    // Validate previously selected voice still exists; clear if not.
                    val current = _selectedVoice.value
                    if (current != null && _voices.value.none { it.id == current.id }) {
                        _selectedVoice.value = null
                    }
                    if (_selectedVoice.value == null && _voices.value.isNotEmpty()) {
                        _selectedVoice.value = _voices.value.first()
                    }
                } else {
                    _errorMessage.value = "获取声纹列表失败（${response.code()}）：${extractErrorDetail(response)}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "网络异常，无法连接服务器：${e.message}"
            } finally {
                _isFetching.value = false
            }
        }
    }

    fun selectVoice(voice: VoiceProfile) {
        _selectedVoice.value = voice
    }

    fun deleteVoice(voiceId: String) {
        viewModelScope.launch {
            try {
                val response = NetworkClient.getApiService(getApplication()).deleteVoice(voiceId)
                if (response.isSuccessful) {
                    // Clear stale selectedVoice reference to avoid "ghost" selection
                    // pointing to a deleted profile (would break TTS downstream).
                    if (_selectedVoice.value?.id == voiceId) {
                        _selectedVoice.value = null
                    }
                    fetchVoices()
                } else {
                    _errorMessage.value = "删除失败，请重试：${extractErrorDetail(response)}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "删除失败，请重试：${e.message}"
            }
        }
    }

    fun startRecording(onError: (String) -> Unit) {
        audioRecorder.startRecording(
            onSuccess = { _isRecording.value = true },
            onError = onError
        )
    }

    fun stopRecording(): File? {
        _isRecording.value = false
        return audioRecorder.stopRecording()
    }

    /**
     * Stop any in-flight TTS playback. Safe to call when nothing is playing.
     * Used by screen DisposableEffects to release AudioTrack when the user
     * navigates away from the chat screen mid-stream.
     */
    fun stopPlayback() {
        streamAudioPlayer.stop()
    }

    /**
     * Stop playback AND mark the bubble as completed (used by the "停止"
     * button on a currently-playing message). Plain stopPlayback() leaves
     * the bubble stuck in PLAYING, which looks like the audio kept playing.
     */
    fun stopMessagePlayback(msgId: String) {
        streamAudioPlayer.stop()
        updateMessageStatus(msgId, MessageStatus.COMPLETED, null)
    }

    fun uploadWavFile(
        name: String,
        refText: String,
        wavFile: File,
        auxFiles: List<File> = emptyList(),
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!wavFile.exists()) {
            onError("音频文件不存在")
            return
        }

        viewModelScope.launch {
            _isUploading.value = true
            try {
                val nameBody = name.toRequestBody("text/plain".toMediaTypeOrNull())
                val refTextBody = refText.toRequestBody("text/plain".toMediaTypeOrNull())
                val fileBody = wavFile.asRequestBody("audio/wav".toMediaTypeOrNull())
                val multipart = MultipartBody.Part.createFormData("audio_file", wavFile.name, fileBody)

                val auxParts = auxFiles.mapNotNull { f ->
                    if (f.exists()) {
                        MultipartBody.Part.createFormData(
                            "aux_files",
                            f.name,
                            f.asRequestBody("audio/wav".toMediaTypeOrNull())
                        )
                    } else null
                }

                val response = NetworkClient.getApiService(getApplication()).registerVoice(nameBody, refTextBody, multipart, auxParts)
                if (response.isSuccessful && response.body() != null) {
                    fetchVoices()
                    onSuccess()
                } else {
                    val errorBody = response.errorBody()?.string()
                    val detail = try { JSONObject(errorBody ?: "").optString("detail") } catch (_: Exception) { "" }
                    onError("上传失败：${detail.ifBlank { response.message() }}")
                }
            } catch (e: Exception) {
                onError("上传失败：${e.message ?: "请检查网络后重试"}")
            } finally {
                _isUploading.value = false
            }
        }
    }

    fun sendTtsRequest(text: String) {
        val voice = _selectedVoice.value
        if (voice == null) {
            // Previously returned silently — user got no feedback when tapping send
            // with no voice selected. Surface a clear error instead.
            _errorMessage.value = "请先在首页选择一个声纹"
            return
        }

        // 中断任何正在播放的旧消息(playStream 内部 stop() 会断音频,这里同步把
        // UI 状态从 PLAYING 拉回 COMPLETED,否则旧气泡会一直显示"播放中"图标)。
        val currentMessages = _chatMessages.value.map { msg ->
            if (msg.status == MessageStatus.PLAYING) msg.copy(status = MessageStatus.COMPLETED) else msg
        }

        // 1) 先追加用户气泡(右对齐 primaryContainer),再追加 assistant 加载气泡。
        val userMsg = ChatMessage(
            id = java.util.UUID.randomUUID().toString(),
            text = text,
            isUser = true,
            voiceId = voice.id
        )
        val msgId = java.util.UUID.randomUUID().toString()
        val newMsg = ChatMessage(
            id = msgId,
            text = text,
            isUser = false,
            voiceId = voice.id,
            status = MessageStatus.LOADING
        )
        _chatMessages.value = currentMessages + userMsg + newMsg
        saveChatHistory(_chatMessages.value)

        viewModelScope.launch {
            try {
                val response = NetworkClient.getApiService(getApplication()).synthesizeSpeech(
                    TTSRequest(voiceId = voice.id, text = text)
                )

                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val contentType = response.headers()["Content-Type"]
                    if (contentType == null || !contentType.contains("audio")) {
                        updateMessageStatus(msgId, MessageStatus.ERROR, "服务器返回格式错误")
                        _errorMessage.value = "合成失败：服务器返回格式错误"
                    } else {
                        streamAudioPlayer.playStream(
                            inputStream = body.byteStream(),
                            onFirstChunkPlayed = {
                                updateMessageStatus(msgId, MessageStatus.PLAYING)
                            },
                            onComplete = {
                                updateMessageStatus(msgId, MessageStatus.COMPLETED)
                            },
                            onError = { err ->
                                updateMessageStatus(msgId, MessageStatus.ERROR, err)
                                _errorMessage.value = "合成失败：${err}"
                            }
                        )
                    }
                } else {
                    val errDetail = extractErrorDetail(response)
                    updateMessageStatus(msgId, MessageStatus.ERROR, errDetail)
                    _errorMessage.value = "合成失败，请检查网络（${response.code()}）"
                }
            } catch (e: Exception) {
                updateMessageStatus(msgId, MessageStatus.ERROR, e.message)
                _errorMessage.value = "合成失败，请检查网络：${e.message}"
            }
        }
    }

    /**
     * Re-send the text from an existing message (used by the "重播" button on
     * a COMPLETED bubble). Currently uses the current selectedVoice; the
     * stored voiceId is preserved on the new message for future use.
     */
    fun replayMessage(msg: ChatMessage) {
        sendTtsRequest(msg.text)
    }

    /**
     * Re-send a failed message (used by the "重试" button on an ERROR bubble).
     * Identical to replayMessage today; kept as a separate API so the intent
     * is clear at the call site and we can specialize later if needed.
     */
    fun retryMessage(msg: ChatMessage) {
        sendTtsRequest(msg.text)
    }

    private fun updateMessageStatus(id: String, status: MessageStatus, errorText: String? = null) {
        _chatMessages.value = _chatMessages.value.map { msg ->
            if (msg.id == id) {
                // 只在 ERROR 时记录 errorText,其他状态清空,避免旧错误文案残留
                val newErrorText = if (status == MessageStatus.ERROR) {
                    errorText ?: msg.errorText
                } else {
                    null
                }
                msg.copy(status = status, errorText = newErrorText)
            } else msg
        }
        saveChatHistory(_chatMessages.value)
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun updateServerUrl(url: String) {
        NetworkClient.setBaseUrl(url, getApplication())
        fetchVoices()
    }

    /**
     * Mark the "unconfigured" banner as dismissed. Called by the UI after the
     * user has been shown the empty-state hint.
     */
    fun dismissUnconfigured() {
        _isUnconfigured.value = false
    }

    /**
     * 清空当前聊天历史(UI + SharedPreferences)。调用方应在用户主动确认后调用。
     */
    fun clearChatHistory() {
        _chatMessages.value = emptyList()
        saveChatHistory(emptyList())
    }

    // ---------------------------------------------------------------------
    // 聊天历史持久化(SharedPreferences + JSON,简单方案)
    // 只保存最近 MAX_PERSISTED_MESSAGES 条,避免文件过大影响冷启动。
    // 加载/保存都在主线程上,JSON 体积小(<10KB)可接受;若以后数据量变大再换 Room。
    // ---------------------------------------------------------------------

    private fun prefs(): android.content.SharedPreferences {
        return getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 从 SharedPreferences 读最近聊天历史;解析失败时返回空列表(降级而非崩溃)。
     */
    private fun loadChatHistory(): List<ChatMessage> {
        val raw = prefs().getString(KEY_CHAT_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<ChatMessage>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ChatMessage(
                        id = obj.optString("id"),
                        text = obj.optString("text"),
                        isUser = obj.optBoolean("isUser"),
                        voiceId = obj.optString("voiceId"),
                        status = runCatching { MessageStatus.valueOf(obj.optString("status", "COMPLETED")) }
                            .getOrDefault(MessageStatus.COMPLETED),
                        errorText = obj.optString("errorText").takeIf { it.isNotEmpty() }
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 写入最近聊天历史。仅持久化最近 MAX_PERSISTED_MESSAGES 条;忽略中间瞬态
     * 状态(LOADING/PLAYING)持久化为 COMPLETED,避免冷启动时把"加载中"残留住。
     */
    private fun saveChatHistory(messages: List<ChatMessage>) {
        try {
            val tail = messages.takeLast(MAX_PERSISTED_MESSAGES)
            val arr = JSONArray()
            tail.forEach { msg ->
                val obj = JSONObject()
                obj.put("id", msg.id)
                obj.put("text", msg.text)
                obj.put("isUser", msg.isUser)
                obj.put("voiceId", msg.voiceId)
                // LOADING/PLAYING 持久化为 COMPLETED,这样冷启动看到的是稳定态。
                val persistStatus = when (msg.status) {
                    MessageStatus.LOADING, MessageStatus.PLAYING -> MessageStatus.COMPLETED
                    else -> msg.status
                }
                obj.put("status", persistStatus.name)
                msg.errorText?.takeIf { it.isNotBlank() }?.let { obj.put("errorText", it) }
                arr.put(obj)
            }
            prefs().edit().putString(KEY_CHAT_HISTORY, arr.toString()).apply()
        } catch (_: Exception) {
            // 持久化失败不影响主流程,静默忽略。
        }
    }
}
