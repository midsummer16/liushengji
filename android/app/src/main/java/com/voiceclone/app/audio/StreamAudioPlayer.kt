package com.voiceclone.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream

class StreamAudioPlayer(private val coroutineScope: CoroutineScope) {

    companion object {
        private const val TAG = "StreamAudioPlayer"
        private const val SAMPLE_RATE = 32000
        private const val WAV_HEADER_SIZE = 44
        private const val DEFAULT_BUFFER_SIZE = SAMPLE_RATE * 2 * 4
        private const val HEADER_READ_TIMEOUT_MS = 10_000L
    }

    private var audioTrack: AudioTrack? = null
    @Volatile private var isPlaying = false
    private var playJob: Job? = null
    private var currentInputStream: InputStream? = null
    private var leftoverByte: Byte? = null

    fun playStream(
        inputStream: InputStream,
        onFirstChunkPlayed: () -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        stop()

        val rawMinBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val minBufferSize = if (rawMinBufferSize > 0) rawMinBufferSize else DEFAULT_BUFFER_SIZE

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        isPlaying = true
        currentInputStream = inputStream
        leftoverByte = null

        playJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                val buffer = ByteArray(minBufferSize)
                var firstChunk = true

                val preBuffer = peekAndSkipHeader(inputStream)
                if (preBuffer.isNotEmpty()) {
                    val writeLen = if (preBuffer.size % 2 != 0) preBuffer.size - 1 else preBuffer.size
                    if (writeLen > 0) {
                        audioTrack?.write(preBuffer, 0, writeLen)
                        firstChunk = false
                        withContext(Dispatchers.Main) { onFirstChunkPlayed() }
                    }
                    if (preBuffer.size % 2 != 0) {
                        leftoverByte = preBuffer[writeLen]
                    }
                }

                while (isActive) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break

                    val data: ByteArray
                    val dataLen: Int
                    val leftover = leftoverByte
                    if (leftover != null) {
                        val combined = ByteArray(bytesRead + 1)
                        combined[0] = leftover
                        System.arraycopy(buffer, 0, combined, 1, bytesRead)
                        leftoverByte = null
                        data = combined
                        dataLen = bytesRead + 1
                    } else {
                        data = buffer
                        dataLen = bytesRead
                    }

                    val writeLen = if (dataLen % 2 != 0) dataLen - 1 else dataLen
                    if (writeLen > 0) {
                        audioTrack?.write(data, 0, writeLen)
                        if (firstChunk) {
                            firstChunk = false
                            withContext(Dispatchers.Main) { onFirstChunkPlayed() }
                        }
                    }
                    if (dataLen % 2 != 0) {
                        leftoverByte = data[writeLen]
                    }
                }

                if (leftoverByte != null) {
                    val tail = byteArrayOf(leftoverByte!!, 0x00)
                    audioTrack?.write(tail, 0, 2)
                    leftoverByte = null
                }

                withContext(Dispatchers.Main) { onComplete() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActive) {
                    return@launch
                }
                Log.e(TAG, "Streaming audio playback error", e)
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Playback error")
                }
            } finally {
                stop()
            }
        }
    }

    private fun peekAndSkipHeader(input: InputStream): ByteArray {
        val headerBuf = ByteArray(WAV_HEADER_SIZE)
        var headerOffset = 0
        val startTime = System.currentTimeMillis()
        while (headerOffset < WAV_HEADER_SIZE) {
            if (System.currentTimeMillis() - startTime > HEADER_READ_TIMEOUT_MS) break
            val n = input.read(headerBuf, headerOffset, WAV_HEADER_SIZE - headerOffset)
            if (n <= 0) break
            headerOffset += n
        }
        if (headerOffset == WAV_HEADER_SIZE) {
            val isWav = headerBuf[0] == 'R'.code.toByte() && headerBuf[1] == 'I'.code.toByte() &&
                        headerBuf[2] == 'F'.code.toByte() && headerBuf[3] == 'F'.code.toByte()
            if (isWav) return ByteArray(0)
            return headerBuf
        }
        if (headerOffset > 0) return headerBuf.copyOf(headerOffset)
        return ByteArray(0)
    }

    fun stop() {
        isPlaying = false
        playJob?.cancel()
        playJob = null
        try {
            currentInputStream?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing input stream", e)
        }
        currentInputStream = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack", e)
        }
        leftoverByte = null
    }
}
