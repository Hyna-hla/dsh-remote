package com.dsh.mobile.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
import com.dsh.mobile.data.*
import com.dsh.mobile.ui.theme.DshShape
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostProfileScreen(profileId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val isNew = profileId == null

    var original by remember {
        mutableStateOf<HostProfile?>(
            profileId?.let { id ->
                runBlocking { settingsStore.profiles.first().firstOrNull { it.id == id } }
            } ?: HostProfile(id = UUID.randomUUID().toString(), remark = "", url = "")
        )
    }
    var remark by remember { mutableStateOf(original?.remark ?: "") }
    var url by remember { mutableStateOf(original?.url ?: "") }
    var trustSelfSigned by remember { mutableStateOf(original?.trustSelfSigned ?: false) }
    var caCertUri by remember { mutableStateOf(original?.caCertUri) }
    var proxyType by remember { mutableStateOf(original?.proxy?.type ?: "none") }
    var proxyHost by remember { mutableStateOf(original?.proxy?.host ?: "") }
    var proxyPort by remember { mutableStateOf(original?.proxy?.port?.takeIf { it > 0 }?.toString() ?: "") }
    var proxyUser by remember { mutableStateOf(original?.proxy?.username ?: "") }
    var proxyPass by remember { mutableStateOf(original?.proxy?.password ?: "") }
    var autoConnect by remember { mutableStateOf(original?.autoConnect ?: false) }
    var diag by remember { mutableStateOf<List<DiagStep>?>(null) }
    var diagRunning by remember { mutableStateOf(false) }

    val caPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val dir = File(context.filesDir, "certs").apply { mkdirs() }
                    val target = File(dir, (original?.id ?: "new") + ".pem")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                    caCertUri = target.absolutePath
                }.onFailure {
                    Toast.makeText(context, "CA 证书导入失败：${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun save() {
        val profile = (original ?: HostProfile(id = UUID.randomUUID().toString(), remark = "", url = ""))
            .copy(
                remark = remark.ifBlank { url },
                url = url.trim().trimEnd('/'),
                trustSelfSigned = trustSelfSigned,
                caCertUri = caCertUri,
                proxy = if (proxyType == "none") null else ProxyConfig(
                    type = proxyType, host = proxyHost,
                    port = proxyPort.toIntOrNull() ?: 0,
                    username = proxyUser, password = proxyPass,
                ),
                autoConnect = autoConnect,
            )
        if (profile.url.isBlank()) {
            Toast.makeText(context, "地址不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            settingsStore.upsertProfile(profile)
            onBack()
        }
    }

    fun delete() {
        original?.let { p ->
            scope.launch {
                settingsStore.deleteProfile(p.id)
                onBack()
            }
        }
    }

    fun runDiag() {
        val profile = (original ?: return).copy(
            remark = remark, url = url.trim().trimEnd('/'),
            trustSelfSigned = trustSelfSigned, caCertUri = caCertUri,
            proxy = if (proxyType == "none") null else ProxyConfig(
                type = proxyType, host = proxyHost, port = proxyPort.toIntOrNull() ?: 0,
                username = proxyUser, password = proxyPass,
            ),
        )
        diagRunning = true
        scope.launch {
            diag = runDiagnostics(profile)
            diagRunning = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "添加主机" else "编辑主机") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = remark, onValueChange = { remark = it },
                label = { Text("备注名") },
                placeholder = { Text("如：家里 / 公司 / 服务器") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("服务器地址") },
                placeholder = { Text("192.168.1.100:8787 或你的 cpolar 域名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Card(Modifier.fillMaxWidth(), shape = DshShape.card) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("HTTPS 证书", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("信任自签名证书", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "⚠ 仅对本主机生效，跳过证书校验",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Switch(checked = trustSelfSigned, onCheckedChange = { trustSelfSigned = it })
                    }
                    OutlinedButton(onClick = { caPicker.launch(arrayOf("application/x-pem-file", "application/octet-stream")) }) {
                        Icon(Icons.Default.UploadFile, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导入 CA 证书")
                    }
                    caCertUri?.let {
                        Text(
                            "已导入：$it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth(), shape = DshShape.card) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("代理（按主机）", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf("none" to "无", "http" to "HTTP", "socks5" to "SOCKS5").forEach { (v, label) ->
                            FilterChip(
                                selected = proxyType == v,
                                onClick = { proxyType = v },
                                label = { Text(label) },
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    if (proxyType != "none") {
                        OutlinedTextField(
                            value = proxyHost, onValueChange = { proxyHost = it },
                            label = { Text("代理主机") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = proxyPort, onValueChange = { proxyPort = it },
                            label = { Text("端口") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = proxyUser, onValueChange = { proxyUser = it },
                            label = { Text("账号（可选）") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = proxyPass, onValueChange = { proxyPass = it },
                            label = { Text("密码（可选）") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启动时自动连接", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = autoConnect, onCheckedChange = { autoConnect = it })
            }

            Button(
                onClick = { runDiag() },
                enabled = !diagRunning && url.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = DshShape.pill,
            ) {
                if (diagRunning) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("诊断中…")
                } else {
                    Text("测试连接")
                }
            }

            diag?.let { steps ->
                Card(Modifier.fillMaxWidth(), shape = DshShape.card) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("诊断结果", style = MaterialTheme.typography.titleSmall)
                        steps.forEach { s ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (s.ok) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                    null,
                                    Modifier.size(16.dp),
                                    tint = if (s.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${s.name}（${s.elapsedMs}ms）", style = MaterialTheme.typography.bodySmall)
                                    s.detail?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { save() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = DshShape.pill,
            ) { Text("保存") }

            if (!isNew) {
                OutlinedButton(
                    onClick = { delete() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = DshShape.pill,
                ) { Text("删除该主机", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
