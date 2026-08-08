package com.voiceclone.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voiceclone.app.R
import com.voiceclone.app.data.api.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Composable
fun ServerConfigDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var urlText by remember { mutableStateOf(NetworkClient.getBaseUrl()) }
    var isTesting by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 错误文案在 Composable 内取,避免 lambda 里调 stringResource 的限制。
    val emptyErr = stringResource(R.string.server_empty_error)
    val invalidErr = stringResource(R.string.server_invalid_error)
    val unreachableErr = stringResource(R.string.server_unreachable)

    val trimmed = urlText.trim()
    val looksValid = trimmed.isNotBlank() &&
        (trimmed.startsWith("http://") || trimmed.startsWith("https://"))

    // 把输入归一化为带 http 前缀 + 末尾斜杠,与 NetworkClient.setBaseUrl 保持一致。
    fun normalize(raw: String): String {
        var s = raw.trim()
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        if (!s.endsWith("/")) s = "$s/"
        return s
    }

    AlertDialog(
        onDismissRequest = { if (!isTesting) onDismiss() },
        title = { Text(text = stringResource(R.string.server_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.server_dialog_hint),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = urlText,
                    onValueChange = {
                        urlText = it
                        errorText = null
                    },
                    label = { Text(stringResource(R.string.server_address_label)) },
                    placeholder = { Text(stringResource(R.string.server_address_placeholder)) },
                    singleLine = true,
                    isError = errorText != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorText != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorText!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isTesting && looksValid,
                onClick = {
                    // 前端校验:空白/非 http 开头直接提示,不发请求。
                    if (trimmed.isBlank()) {
                        errorText = emptyErr
                        return@Button
                    }
                    if (!looksValid) {
                        errorText = invalidErr
                        return@Button
                    }
                    val normalized = normalize(trimmed)
                    val healthUrl = normalized.trimEnd('/') + "/health"
                    isTesting = true
                    errorText = null
                    // 用 OkHttp 同步 GET /health(超时 5s),成功才允许保存。
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                val client = OkHttpClient.Builder()
                                    .connectTimeout(5, TimeUnit.SECONDS)
                                    .readTimeout(5, TimeUnit.SECONDS)
                                    .build()
                                val resp = client.newCall(
                                    Request.Builder().url(healthUrl).get().build()
                                ).execute()
                                resp.use { it.isSuccessful }
                            }.getOrDefault(false)
                        }
                        isTesting = false
                        if (ok) {
                            onConfirm(normalized)
                            onDismiss()
                        } else {
                            errorText = unreachableErr
                        }
                    }
                }
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.testing))
                } else {
                    Text(stringResource(R.string.test_and_connect))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isTesting) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
