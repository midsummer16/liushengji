package com.voiceclone.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.voiceclone.app.R
import com.voiceclone.app.ui.components.WaveformView
import kotlinx.coroutines.delay
import com.voiceclone.app.viewmodel.VoiceViewModel
import java.io.File
import java.io.RandomAccessFile

private val ALLOWED_AUDIO_EXTS = setOf("wav", "mp3", "m4a", "ogg", "flac")
private const val MIN_AUDIO_SEC = 3.0
private const val MAX_AUDIO_SEC = 10.0

private val REFERENCE_TEXTS = listOf(
    "大家好，很高兴认识你们，让我们一起去公园散步吧。",
    "今天天气真不错，周末我们一起去吃火锅怎么样？",
    "小时候的梦想，就是长大后能做一个自由自在的人。",
    "人生就像一场旅行，重要的不是目的地，而是沿途的风景。",
    "我特别喜欢唱歌，每天晚上都会练习半小时。"
)

@Composable
fun RecordScreen(
    viewModel: VoiceViewModel,
    onBack: () -> Unit
) {
    val isRecording by viewModel.isRecording.collectAsState()
    val amplitude by viewModel.recordingAmplitude.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val context = LocalContext.current

    var voiceName by remember { mutableStateOf("") }
    var refText by remember { mutableStateOf(REFERENCE_TEXTS.random()) }
    var showNameDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var clips by remember { mutableStateOf<List<File>>(emptyList()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 实时录音计时:0.1s 步进,<3s 红色提示太短,3-10s 绿色合适,>10s 红色太长。
    // 停止时(isRecording -> false)归零。isRecording 作 key,切换时 LaunchedEffect
    // 自动取消旧协程、启动新分支,无需手动 cancel。
    var recordingDuration by remember { mutableStateOf(0f) }
    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (isRecording) {
                delay(100)
                recordingDuration += 0.1f
            }
        } else {
            recordingDuration = 0f
        }
    }

    // 离开页面时若正在录音则停掉,避免后台空跑占用麦克风。
    DisposableEffect(Unit) {
        onDispose {
            if (isRecording) viewModel.stopRecording()
        }
    }

    // 录音权限按需申请:用户点击录音按钮时检查;被拒后弹自定义对话框
    // 引导去系统设置页,避免反复冷启动时进入"永不再问"静默态。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startRecording(onError = { errorMsg = it })
        } else {
            showPermissionDialog = true
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val newFiles = uris.mapNotNull { uri ->
            try {
                // 1) 通过 MIME 与文件名后缀双重判断格式,只接受 wav/mp3/m4a/ogg/flac。
                val mime = context.contentResolver.getType(uri).orEmpty()
                val displayName = queryDisplayName(context, uri).orEmpty()
                val ext = displayName.substringAfterLast('.', "").lowercase()
                val isAudioByExt = ext in ALLOWED_AUDIO_EXTS
                val isAudioByMime = mime.startsWith("audio/")
                if (!isAudioByExt && !isAudioByMime) {
                    Toast.makeText(context, "仅支持 wav/mp3/m4a/ogg/flac 音频", Toast.LENGTH_SHORT).show()
                    return@mapNotNull null
                }

                // 2) 拷贝到 cacheDir,保留原扩展名(部分解码器依赖扩展名嗅探)。
                val outExt = ext.ifEmpty { "wav" }
                val file = java.io.File(context.cacheDir, "upload_${System.currentTimeMillis()}.${outExt}")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } ?: return@mapNotNull null

                // 3) 用 MediaExtractor 解码出真实时长,只接受 3-10 秒。
                val durationMs = getAudioDurationMs(file)
                if (durationMs <= 0L) {
                    Toast.makeText(context, "无法识别音频时长,请换一段", Toast.LENGTH_SHORT).show()
                    file.delete()
                    return@mapNotNull null
                }
                val durationSec = durationMs / 1000.0
                if (durationSec < MIN_AUDIO_SEC || durationSec > MAX_AUDIO_SEC) {
                    Toast.makeText(context, "仅支持 3-10 秒的音频文件", Toast.LENGTH_SHORT).show()
                    file.delete()
                    return@mapNotNull null
                }
                file
            } catch (e: Exception) {
                Toast.makeText(context, "读取文件失败:${e.message}", Toast.LENGTH_SHORT).show()
                null
            }
        }
        if (newFiles.isNotEmpty()) clips = clips + newFiles
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "录制新声纹",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
        }

        // Instruction Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "请朗读以下文本：",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = refText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { refText = REFERENCE_TEXTS.random() }) {
                    Text("换一段")
                }
            }
        }

        // Waveform Visualization
        Spacer(modifier = Modifier.height(24.dp))
        WaveformView(
            amplitude = amplitude,
            isRecording = isRecording,
            isActive = isRecording,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        // 录音时长与区间提示:3-10s 区间为绿色"合适",其他为 error 红。
        val (timerColor, hintText) = when {
            !isRecording -> MaterialTheme.colorScheme.onSurfaceVariant to "点击下方按钮开始录音"
            recordingDuration < MIN_AUDIO_SEC.toFloat() -> MaterialTheme.colorScheme.error to "再读久一点(建议 3-10 秒)"
            recordingDuration > MAX_AUDIO_SEC.toFloat() -> MaterialTheme.colorScheme.error to "太长啦,可以精简一些(建议 3-10 秒)"
            else -> Color(0xFF4CAF50) to "时长合适 ✓"
        }
        if (isRecording) {
            Text(
                text = String.format("%.1f", recordingDuration) + " 秒",
                style = MaterialTheme.typography.headlineMedium,
                color = timerColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Text(
                text = hintText,
                style = MaterialTheme.typography.bodySmall,
                color = timerColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                text = hintText,
                style = MaterialTheme.typography.bodyMedium,
                color = timerColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }

        // Main Record Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            RecordButton(
                isRecording = isRecording,
                onToggle = {
                    if (isRecording) {
                        val f = viewModel.stopRecording()
                        if (f != null && f.exists()) clips = clips + f
                    } else {
                        // 按需申请录音权限,已授权则直接开始;未授权则交由
                        // permissionLauncher 处理结果,被拒后会弹"去设置"对话框。
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            viewModel.startRecording(onError = { errorMsg = it })
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
            )
        }

        // Clip list (main + aux references)
        if (clips.isNotEmpty()) {
            Text(
                text = "参考片段（第 1 段为主参考，其余为附加参考）：",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            clips.forEachIndexed { index, file ->
                // 展示用时长优先用 MediaExtractor 解码;失败再退到 WAV 头解析。
                val durMs = getAudioDurationMs(file)
                val dur = if (durMs > 0) durMs / 1000.0 else estimateWavDuration(file)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (index == 0) "主参考" else "附加 $index",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(56.dp)
                    )
                    Text(
                        text = String.format("%.1f 秒", dur),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (index == 0 && (dur < 3.0 || dur > 10.0)) {
                        Text(
                            text = "建议 3-10 秒",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    IconButton(onClick = { clips = clips.filterIndexed { i, _ -> i != index } }) {
                        Icon(Icons.Default.Close, contentDescription = "移除", modifier = Modifier.size(16.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Upload local files
        OutlinedButton(
            onClick = { filePicker.launch(arrayOf("audio/*")) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 60.dp)
        ) {
            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (clips.isEmpty()) "从本地选择音频文件" else "继续添加音频文件")
        }

        // Save button (enabled once at least one clip is ready)
        if (clips.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { showNameDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 60.dp)
            ) {
                Text("保存并上传（${clips.size} 段参考）")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    LaunchedEffect(errorMsg) {
        errorMsg?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            errorMsg = null
        }
    }

    if (showPermissionDialog) {
        // 用户拒绝麦克风权限后,引导去系统设置页手动开启,避免重复弹系统框被静默拒。
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text(stringResource(R.string.mic_permission_needed)) },
            text = { Text(stringResource(R.string.mic_permission_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }) { Text(stringResource(R.string.go_to_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text(text = "保存声纹卡片") },
            text = {
                Column {
                    OutlinedTextField(
                        value = voiceName,
                        onValueChange = { voiceName = it },
                        label = { Text("声纹名称（如：妈妈）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = refText,
                        onValueChange = { refText = it },
                        label = { Text("这句话对应的文本") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = voiceName.isNotBlank() && refText.isNotBlank(),
                    onClick = {
                        showNameDialog = false
                        val main = clips.firstOrNull()
                        if (main != null && main.exists()) {
                            viewModel.uploadWavFile(
                                name = voiceName,
                                refText = refText,
                                wavFile = main,
                                auxFiles = clips.drop(1).take(3),
                                onSuccess = { onBack() },
                                onError = { errorMsg = it }
                            )
                        } else {
                            errorMsg = "没有可上传的音频，请先录音或选择文件"
                        }
                    }
                ) {
                    Text("保存并上传")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
fun RecordButton(isRecording: Boolean, onToggle: () -> Unit) {
    val containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .size(96.dp)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.size(96.dp),
            shape = androidx.compose.foundation.shape.CircleShape,
            color = containerColor,
            shadowElevation = 8.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isRecording) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "停止录音",
                        modifier = Modifier.size(44.dp),
                        tint = Color.White
                    )
                } else {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = "开始录音",
                        modifier = Modifier.size(44.dp),
                        tint = Color.White
                    )
                }
            }
        }
    }
}

private fun estimateWavDuration(file: File): Double {
    return try {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 44) return@use 0.0
            raf.seek(24)
            val b = ByteArray(4)
            raf.read(b)
            val sr = (b[0].toLong() and 0xFF) or
                ((b[1].toLong() and 0xFF) shl 8) or
                ((b[2].toLong() and 0xFF) shl 16) or
                ((b[3].toLong() and 0xFF) shl 24)
            if (sr <= 0) return@use 0.0
            (raf.length() - 44) / 2.0 / sr.toDouble()
        }
    } catch (e: Exception) {
        0.0
    }
}

/**
 * 通过 ContentResolver 读取 URI 指向的文件名(用于后缀判断)。
 * 找不到时返回 null,调用方需自行兜底。
 */
private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * 用 MediaExtractor 解码真实时长(毫秒),失败时返回 -1。
 * 不依赖文件扩展名,可处理 wav/mp3/m4a/ogg/flac 等多种容器。
 */
private fun getAudioDurationMs(file: File): Long {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(file.absolutePath)
        var durationUs = -1L
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                durationUs = format.getLong(MediaFormat.KEY_DURATION)
                break
            }
        }
        if (durationUs > 0) durationUs / 1000L else -1L
    } catch (e: Exception) {
        -1L
    } finally {
        try { extractor.release() } catch (_: Exception) {}
    }
}
