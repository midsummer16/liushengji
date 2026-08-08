package com.voiceclone.app.ui.screens

import com.voiceclone.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voiceclone.app.viewmodel.ChatMessage
import com.voiceclone.app.viewmodel.MessageStatus
import com.voiceclone.app.viewmodel.VoiceViewModel
import kotlinx.coroutines.launch

@Composable
fun TtsChatScreen(
    viewModel: VoiceViewModel = viewModel(),
    onBack: () -> Unit
) {
    val chatMessages by viewModel.chatMessages.collectAsState()
    val selectedVoice by viewModel.selectedVoice.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
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
            Column {
                Text(
                    text = selectedVoice?.name ?: "未选择声纹",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                Text(
                    text = "语音克隆合成",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Divider()

        // Messages List
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (chatMessages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.VolumeUp,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "输入文字，让 ${selectedVoice?.name ?: "TA"} 念给你听",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            items(chatMessages) { message ->
                ChatBubble(
                    message = message,
                    onReplay = { viewModel.replayMessage(message) },
                    onStop = { viewModel.stopMessagePlayback(message.id) },
                    onRetry = { viewModel.retryMessage(message) }
                )
            }
        }

        Divider()

        // Input Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入想说的话…") },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            FloatingActionButton(
                onClick = {
                    if (inputText.isNotBlank() && selectedVoice != null) {
                        viewModel.sendTtsRequest(inputText)
                        inputText = ""
                        scope.launch {
                            listState.animateScrollToItem(chatMessages.size)
                        }
                    }
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Filled.Send, contentDescription = "发送")
            }
        }
    }
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    onReplay: () -> Unit = {},
    onStop: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    val isUser = message.isUser
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val backgroundColor = if (isUser) {
        // 用户气泡右对齐 + primaryContainer 背景,沿用 M3 配色
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (!isUser) {
                when (message.status) {
                    MessageStatus.LOADING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    MessageStatus.PLAYING -> {
                        Icon(
                            Icons.Default.GraphicEq,
                            contentDescription = "播放中",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    MessageStatus.COMPLETED -> {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "已完成",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    MessageStatus.ERROR -> {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = "出错",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                }
            }
            Column {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(backgroundColor)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                // 用户消息不展示操作按钮;assistant 三种状态各自暴露一个轻量按钮。
                if (!isUser) {
                    when (message.status) {
                        MessageStatus.COMPLETED -> {
                            Row(
                                modifier = Modifier.padding(top = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = onReplay) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("重播", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        MessageStatus.PLAYING -> {
                            Row(
                                modifier = Modifier.padding(top = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = onStop) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("停止", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        MessageStatus.ERROR -> {
                            Row(
                                modifier = Modifier.padding(top = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = message.errorText?.takeIf { it.isNotBlank() }
                                        ?: stringResource(R.string.tts_failed),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                TextButton(onClick = onRetry) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("重试", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        else -> { /* LOADING 不显示按钮 */ }
                    }
                }
            }
        }
    }
}
