package com.voiceclone.app.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object AudioResampler {

    private const val FILTER_TAPS = 16
    private const val CUTOFF_HZ = 15000.0
    private const val IN_SAMPLE_RATE_HZ = 44100.0
    private const val CUTOFF_NORM = CUTOFF_HZ / (IN_SAMPLE_RATE_HZ / 2.0)

    private val filterCoeffs: DoubleArray = run {
        val n = FILTER_TAPS
        val center = (n - 1) / 2.0
        val coeffs = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            val t = i - center
            val arg = 2.0 * CUTOFF_NORM * t
            val sincVal = if (arg == 0.0) 1.0 else sin(PI * arg) / (PI * arg)
            val window = 0.54 - 0.46 * cos(2.0 * PI * i / (n - 1))
            coeffs[i] = sincVal * window
            sum += coeffs[i]
        }
        for (i in 0 until n) coeffs[i] /= sum
        coeffs
    }

    /**
     * Resamples 16-bit PCM Mono audio from inSampleRate to outSampleRate
     * using a lowpass-filtered linear interpolation.
     */
    fun resample16BitMono(inputPcm: ByteArray, inSampleRate: Int, outSampleRate: Int): ByteArray {
        if (inSampleRate == outSampleRate || inputPcm.isEmpty()) {
            return inputPcm
        }

        val shortBuffer = ByteBuffer.wrap(inputPcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val inSamples = ShortArray(shortBuffer.remaining())
        shortBuffer.get(inSamples)

        val filteredSamples = DoubleArray(inSamples.size)
        for (i in inSamples.indices) {
            var acc = 0.0
            for (k in 0 until FILTER_TAPS) {
                val srcIdx = i - k
                if (srcIdx >= 0) {
                    acc += inSamples[srcIdx] * filterCoeffs[k]
                }
            }
            filteredSamples[i] = acc
        }

        val ratio = inSampleRate.toDouble() / outSampleRate.toDouble()
        val outLength = (inSamples.size / ratio).toInt()
        val outSamples = ShortArray(outLength)

        for (i in 0 until outLength) {
            val srcIndex = i * ratio
            val indexFloor = srcIndex.toInt()
            val indexCeil = (indexFloor + 1).coerceAtMost(filteredSamples.size - 1)
            val weight = srcIndex - indexFloor

            val sample = (filteredSamples[indexFloor] * (1.0 - weight) + filteredSamples[indexCeil] * weight).toInt()
            outSamples[i] = sample.coerceIn(-32768, 32767).toShort()
        }

        val resultBuffer = ByteBuffer.allocate(outSamples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in outSamples) {
            resultBuffer.putShort(sample)
        }
        return resultBuffer.array()
    }
}
