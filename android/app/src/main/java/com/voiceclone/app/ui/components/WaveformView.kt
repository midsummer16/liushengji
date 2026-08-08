package com.voiceclone.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 录音波形可视化。
 *
 * @param amplitude        实时 RMS 幅值(0..1),由 ViewModel 从 AudioRecorder 推过来。
 * @param isRecording      是否处于录音中;为 true 时颜色变红(主题 error 色)以加强"正在录音"提示。
 * @param isActive         是否启用无限相位动画;为 false 时不进 rememberInfiniteTransition,
 *                         波形保持固定小竖条(避免没录音时空转耗电 / 视觉空跑)。
 * @param modifier         外部 modifier。
 * @param barCount         竖条数量。
 */
@Composable
fun WaveformView(
    amplitude: Float,
    isRecording: Boolean,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    barCount: Int = 30
) {
    // EMA 平滑:避免 RMS 抖动让波形抽搐。α=0.3,历史权重 0.7。
    var smoothed by remember { mutableStateOf(0f) }
    LaunchedEffect(amplitude) {
        smoothed = smoothed * 0.7f + amplitude * 0.3f
    }

    // 录音中用 error 红更醒目,空闲用主题 primary;同时跟随 light/dark 主题。
    val color = if (isRecording) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary

    // 只在 active 时才开无限动画。phase 初始为 0,意味着静态时波形是一条正弦快照,
    // 再加上 baseScale 兜底 0.05,实际呈现为一排固定小竖条。
    val animOffset: Float = if (isActive) {
        val infiniteTransition = rememberInfiniteTransition(label = "waveform")
        val offset by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2 * Math.PI.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "phase"
        )
        offset
    } else {
        0f
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val barWidth = width / (barCount * 1.5f)
        val gap = barWidth * 0.5f

        for (i in 0 until barCount) {
            val x = i * (barWidth + gap) + barWidth / 2f
            // 录音中用平滑后的幅值,空闲用最小值;非 active 时整体压平
            val baseScale = if (isRecording) smoothed.coerceAtLeast(0.05f) else 0.05f
            val sineWave = if (isActive) kotlin.math.sin(i * 0.3f + animOffset) else 0f
            val barHeight = (baseScale * height * 0.8f * (0.5f + 0.5f * sineWave)).coerceAtLeast(4f)

            drawLine(
                color = color,
                start = Offset(x, centerY - barHeight / 2f),
                end = Offset(x, centerY + barHeight / 2f),
                strokeWidth = barWidth
            )
        }
    }
}
