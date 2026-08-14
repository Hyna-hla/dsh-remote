package com.dsh.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dsh.mobile.data.ConnectionConfig
import com.dsh.mobile.data.DshConnection
import com.dsh.mobile.data.SettingsStore
import com.dsh.mobile.ui.theme.DshBrand
import com.dsh.mobile.ui.theme.DshSuccess
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun ConnectScreen(connection: DshConnection) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val connState by connection.state.collectAsState()

    // 载入保存的地址并自动连接
    LaunchedEffect(Unit) {
        val config = settingsStore.connectionConfig.first()
        if (config.serverUrl.isNotEmpty()) {
            url = config.serverUrl
            if (config.autoConnect) {
                connection.connect(config.serverUrl)
            }
        }
    }

    LaunchedEffect(connState) {
        when (connState) {
            is DshConnection.State.Error -> {
                isLoading = false
                errorMessage = (connState as DshConnection.State.Error).message
            }
            is DshConnection.State.Connected -> isLoading = false
            else -> {}
        }
    }

    fun doConnect() {
        val u = url.trim()
        if (u.isBlank()) return
        isLoading = true
        errorMessage = null
        scope.launch {
            settingsStore.saveConnection(ConnectionConfig(serverUrl = u, autoConnect = true))
        }
        connection.connect(u)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))

        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.size(84.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.ChatBubble,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = DshBrand,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "DeepSeek Harness",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "移动端遥控 · 连接你电脑上的智能体",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(40.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("服务器地址") },
            placeholder = { Text("192.168.1.100:8787 或你的 cpolar 域名") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Dns, null) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
            ),
        )

        errorMessage?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = { doConnect() },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            enabled = url.isNotBlank() && !isLoading,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text("连接中…")
            } else {
                Icon(Icons.Default.Power, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("连接")
            }
        }

        val conn = connState
        if (conn is DshConnection.State.Connected) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = DshSuccess,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "已连接：${conn.baseUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = DshSuccess,
                )
            }
        }

        Spacer(Modifier.height(36.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "两种连接方式",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "① 局域网（同一 Wi-Fi）\n" +
                        "   PC 上运行项目里的 dsh-remote-start.ps1，\n" +
                        "   填 PC 的局域网 IP，如 192.168.1.100:8787\n\n" +
                        "② 远程穿透（任何网络）\n" +
                        "   PC 安装 cpolar → 注册 → 运行：cpolar http 8787\n" +
                        "   把 cpolar 显示的域名填到这里，如\n" +
                        "   https://xxxx.cpolar.top（无需端口号）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
