package com.voiceclone.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voiceclone.app.R
import com.voiceclone.app.audio.StreamAudioPlayer
import com.voiceclone.app.data.api.NetworkClient
import com.voiceclone.app.data.model.TTSRequest
import com.voiceclone.app.data.model.VoiceProfile
import com.voiceclone.app.ui.components.ServerConfigDialog
import com.voiceclone.app.viewmodel.VoiceViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: VoiceViewModel = viewModel(),
    onRecordClick: () -> Unit,
    onVoiceSelected: (VoiceProfile) -> Unit
) {
    val voices by viewModel.voices.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isFetching by viewModel.isFetching.collectAsState()
    val selectedVoice by viewModel.selectedVoice.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val context = LocalContext.current

    var showServerDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<VoiceProfile?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 搜索词:本地状态,过滤当前 voices 列表;不持久化、不影响后端拉取。
    var searchQuery by remember { mutableStateOf("") }
    val filteredVoices = remember(voices, searchQuery) {
        if (searchQuery.isBlank()) voices
        else voices.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.refText.contains(searchQuery, ignoreCase = true)
        }
    }

    // 长按试听:独立的 StreamAudioPlayer 实例,与主 TTS 通道隔离,避免覆盖聊天页播放。
    val previewPlayer = remember { StreamAudioPlayer(CoroutineScope(SupervisorJob() + Dispatchers.Main)) }
    DisposableEffect(Unit) {
        onDispose { previewPlayer.stop() }
    }

    // Material 1.x pullrefresh API(由 material-icons-extended 传递引入,无需新增依赖)。
    val pullState = rememberPullRefreshState(
        refreshing = isFetching,
        onRefresh = { viewModel.fetchVoices() }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (selectedVoice != null)
                            stringResource(R.string.current_voice, selectedVoice!!.name)
                        else stringResource(R.string.no_voice_selected),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showServerDialog = true }) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = stringResource(R.string.server_settings)
                    )
                }
            }

            if (isLoading && voices.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (voices.isEmpty()) {
                EmptyStateView(onRecordClick)
            } else {
                // 搜索框:固定在列表上方,空 query 时显示完整列表。
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.search_voice_hint)) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.clear_search),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pullRefresh(pullState)
                ) {
                    if (filteredVoices.isEmpty()) {
                        // 搜索无结果:区别于"没有声纹",给出更明确的引导。
                        Box(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.SearchOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.search_no_result),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(filteredVoices, key = { it.id }) { voice ->
                                VoiceCard(
                                    voice = voice,
                                    isSelected = voice.id == selectedVoice?.id,
                                    onSelect = { onVoiceSelected(voice) },
                                    onLongPress = {
                                        // 长按试听:播放一段固定文本,后端返回 TTS 流。
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                message = context.getString(
                                                    R.string.preview_started,
                                                    voice.name
                                                )
                                            )
                                        }
                                        playPreview(voice, context, previewPlayer) { errMsg ->
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    message = errMsg
                                                )
                                            }
                                        }
                                    },
                                    onDelete = { pendingDelete = voice }
                                )
                            }
                        }
                    }

                    PullRefreshIndicator(
                        refreshing = isFetching,
                        state = pullState,
                        modifier = Modifier.align(Alignment.TopCenter),
                        contentColor = MaterialTheme.colorScheme.primary,
                        backgroundColor = MaterialTheme.colorScheme.surface
                    )
                }
            }
        }

        // Extended FAB 显式表达"新增声纹"意图,比纯图标更易理解。
        ExtendedFloatingActionButton(
            onClick = onRecordClick,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = Color.White
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.add_voice))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp)
        )
    }

    if (showServerDialog) {
        ServerConfigDialog(
            onDismiss = { showServerDialog = false },
            onConfirm = { url ->
                viewModel.updateServerUrl(url)
                showServerDialog = false
            }
        )
    }

    // 删除确认对话框:点垃圾桶后弹,confirm 真的走 ViewModel.deleteVoice。
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_voice_title)) },
            text = {
                Text(stringResource(R.string.delete_voice_confirm, target.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteVoice(target.id)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // LaunchedEffect 内部不是 Composable 上下文,需要先在 composable 作用域内取好字符串。
    val retryLabel = stringResource(R.string.retry)
    LaunchedEffect(errorMessage) {
        errorMessage?.let { msg ->
            // 错误提示带"重试"按钮:网络类错误用户一点即可重新拉取声纹列表。
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = retryLabel,
                withDismissAction = true
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.fetchVoices()
            }
            viewModel.clearError()
        }
    }
}

@Composable
fun EmptyStateView(onRecordClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Mic,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.empty_state_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_state_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRecordClick) {
            Text(stringResource(R.string.empty_state_action))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VoiceCard(
    voice: VoiceProfile,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit
) {
    // 头像色块:用姓名首字 + 基于 name.hashCode() 的色相偏移,使每个声纹卡片
    // 拥有稳定且独立的暖色身份,比统一 Person 图标更有"亲友感"。
    val initial = voice.name.firstOrNull()?.toString() ?: "?"
    val avatarBg = remember(voice.name) { avatarColorFor(voice.name) }
    // 在底色之上叠一层 primary->secondary 的渐变光泽(透明度已压低,只起"高级感"作用)
    val gradient = Brush.linearGradient(
        listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
        )
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onLongPress
            ),
        shape = MaterialTheme.shapes.medium,
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 头像层:底色用 HSL 暖色,顶层用 primary->secondary 渐变叠出光泽,首字白色居中
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(avatarBg),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(gradient)
                )
                Text(
                    text = initial,
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = voice.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = formatCreatedAt(voice.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = voice.refText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelected) {
                    Text(
                        text = stringResource(R.string.selected_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete_icon_desc),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * 用 name.hashCode() 映射到暖色色相区间(0-50°),保证同一人始终同色,
 * 视觉上仍属于暖色家族(primary/secondary 的色相偏移),不引入冷色。
 */
private fun avatarColorFor(name: String): Color {
    val raw = if (name.isEmpty()) 0 else abs(name.hashCode())
    val hue = (raw % 50).toFloat()                       // 0-49°(红→橙→黄)
    val saturation = 0.62f                                // 适中饱和度,不刺眼
    val lightness = 0.58f                                 // 偏亮,白字可读
    return hslToColor(hue, saturation, lightness)
}

/**
 * 手写 HSL -> RGB 转换,避免再引入 androidx.compose.ui.graphics.Color.hsl
 * 之外的依赖(API 1.5+ 才有 hsl),保证与现有 Compose BOM 2023.10 兼容。
 */
private fun hslToColor(h: Float, s: Float, l: Float): Color {
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val hp = h / 60f
    val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
    val (r1, g1, b1) = when {
        hp < 1f -> Triple(c, x, 0f)
        hp < 2f -> Triple(x, c, 0f)
        hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c)
        hp < 5f -> Triple(x, 0f, c)
        else    -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = 1f
    )
}

/**
 * 把后端返回的 createdAt 截成"YYYY-MM-DD HH:MM"形式,避免显示完整 ISO 字符串。
 * 解析失败时原样返回,降级展示。
 */
private fun formatCreatedAt(raw: String): String {
    if (raw.isBlank()) return ""
    // 常见格式: "2024-01-15T10:30:00" / "2024-01-15 10:30:00" / "2024-01-15T10:30:00.123456"
    val dateTime = raw.substringBefore('.').replace('T', ' ')
    val main = dateTime.substringBefore('+').substringBefore('Z')
    return if (main.length >= 16) main.substring(0, 16) else main
}

/**
 * 试听:用后端 TTS 合成一句固定文本,与主聊天播放通道隔离。
 * 错误通过 onError 回调上抛给 UI(Snackbar),不修改 ViewModel 内部状态。
 */
private fun playPreview(
    voice: VoiceProfile,
    context: android.content.Context,
    player: StreamAudioPlayer,
    onError: (String) -> Unit
) {
    if (NetworkClient.getBaseUrl().isBlank()) {
        onError("服务器未连接")
        return
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    scope.launch {
        try {
            val resp = NetworkClient.getApiService(context).synthesizeSpeech(
                TTSRequest(voiceId = voice.id, text = "你好,我是${voice.name}")
            )
            if (resp.isSuccessful && resp.body() != null) {
                val ct = resp.headers()["Content-Type"]
                if (ct == null || !ct.contains("audio")) {
                    onError("服务器返回格式错误")
                    return@launch
                }
                player.playStream(
                    inputStream = resp.body()!!.byteStream(),
                    onFirstChunkPlayed = {},
                    onComplete = {},
                    onError = { onError("试听失败:$it") }
                )
            } else {
                onError("试听失败(${resp.code()})")
            }
        } catch (e: Exception) {
            onError("试听失败:${e.message ?: "请检查网络"}")
        }
    }
}
