package com.dsh.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dsh.mobile.data.DshConnection
import com.dsh.mobile.ui.theme.DshError
import com.dsh.mobile.ui.theme.DshSuccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    connection: DshConnection,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connState by connection.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("设置") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("连接", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = when (connState) {
                                is DshConnection.State.Connected -> Icons.Default.CheckCircle
                                is DshConnection.State.Error -> Icons.Default.ErrorOutline
                                else -> Icons.Default.CloudOff
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = when (connState) {
                                is DshConnection.State.Connected -> DshSuccess
                                is DshConnection.State.Error -> DshError
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            when (connState) {
                                is DshConnection.State.Connected -> "已连接 " + (connState as DshConnection.State.Connected).baseUrl
                                is DshConnection.State.Error -> "连接错误"
                                is DshConnection.State.Connecting -> "连接中…"
                                else -> "未连接"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = connState is DshConnection.State.Connected,
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("断开并重新连接")
                    }
                }
            }

            Text("远程连接（cpolar）", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "1. 在 PC 访问 cpolar.com 注册并下载安装客户端\n" +
                            "2. 运行：cpolar http 8787（8787 是 DSH 服务端口）\n" +
                            "3. 客户端会显示一个公网域名，形如 https://xxx.cpolar.top\n" +
                            "4. 把这个域名填进 App 的服务器地址即可，任何网络都能连",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.6,
                    )
                }
            }

            Text("关于", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingInfoRow("应用", "DSH Remote v1.0.0")
                    SettingInfoRow("协议", "dsh-api (client-request / SSE mux)")
                    SettingInfoRow("后端", "DeepSeek Harness")
                    SettingInfoRow("主题", "DSH 深色 · AMOLED 友好")
                }
            }
        }
    }
}

@Composable
private fun SettingInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
