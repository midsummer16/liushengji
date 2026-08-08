package com.voiceclone.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.sqrt

class AudioRecorder(private val context: Context) {

    companion object {
        const val TAG = "AudioRecorder"
        const val INPUT_SAMPLE_RATE = 44100
        const val TARGET_SAMPLE_RATE = 32000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    private var isRecording = false
    private var recordingJob: Job? = null
    private val rawPcmStream = ByteArrayOutputStream()

    var onAmplitudeListener: ((Float) -> Unit)? = null

    fun startRecording(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            onError("Invalid AudioRecord parameters")
            return
        }

        val bufferSize = minBufferSize * 2

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                INPUT_SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            val audioSessionId = audioRecord?.audioSessionId ?: 0

            // Enable hardware Noise Suppressor if available (Snapdragon 8 Gen 2 array)
            if (NoiseSuppressor.isAvailable() && audioSessionId != 0) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                }
            }

            // Enable Acoustic Echo Canceler if available
            if (AcousticEchoCanceler.isAvailable() && audioSessionId != 0) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                }
            }

            rawPcmStream.reset()
            audioRecord?.startRecording()
            isRecording = true

            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ByteArray(minBufferSize)
                while (isRecording) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readBytes > 0) {
                        rawPcmStream.write(buffer, 0, readBytes)

                        // Calculate RMS amplitude for UI visualization
                        val amplitude = calculateRMS(buffer, readBytes)
                        onAmplitudeListener?.invoke(amplitude)
                    }
                }
            }

            onSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord", e)
            onError(e.message ?: "Failed to start recording")
        }
    }

    fun stopRecording(): File? {
        isRecording = false
        recordingJob?.cancel()

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            noiseSuppressor?.release()
            echoCanceler?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }

        val pcm44kData = rawPcmStream.toByteArray()
        if (pcm44kData.isEmpty()) return null

        // DDS Specification: Resample from 44.1kHz to 32kHz
        val pcm32kData = AudioResampler.resample16BitMono(
            pcm44kData,
            INPUT_SAMPLE_RATE,
            TARGET_SAMPLE_RATE
        )

        val outputFile = File(context.cacheDir, "recorded_voice_${System.currentTimeMillis()}.wav")
        saveAsWav(outputFile, pcm32kData, TARGET_SAMPLE_RATE)
        return outputFile
    }

    private fun calculateRMS(buffer: ByteArray, size: Int): Float {
        var sum = 0.0
        val shortCount = size / 2
        for (i in 0 until size step 2) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val shortVal = sample.toShort()
            sum += shortVal * shortVal
        }
        if (shortCount == 0) return 0f
        val rms = sqrt(sum / shortCount)
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    private fun saveAsWav(file: File, pcmData: ByteArray, sampleRate: Int) {
        val totalAudioLen = pcmData.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val longSampleRate = sampleRate.toLong()
        val channels = 1
        val byteRate = (sampleRate * 16 * channels / 8).toLong()

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 16 for PCM
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // PCM Format
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = (longSampleRate shr 8 and 0xff).toByte()
        header[26] = (longSampleRate shr 16 and 0xff).toByte()
        header[27] = (longSampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (16 * channels / 8).toByte() // Block align
        header[33] = 0
        header[34] = 16 // Bits per sample
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte()
        header[43] = (totalAudioLen shr 24 and 0xff).toByte()

        FileOutputStream(file).use { fos ->
            fos.write(header)
            fos.write(pcmData)
        }
    }
}
