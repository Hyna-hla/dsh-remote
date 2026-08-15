package com.dsh.mobile.ui.screens

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dsh.mobile.R
import androidx.core.content.ContextCompat
import com.dsh.mobile.data.ConnectionConfig
import com.dsh.mobile.data.DshConnection
import com.dsh.mobile.data.HostProfile
import com.dsh.mobile.data.SettingsStore
import com.dsh.mobile.service.DshConnectionService
import com.dsh.mobile.ui.theme.DshBrand
import com.dsh.mobile.ui.theme.DshSuccess
import com.dsh.mobile.ui.theme.DshShape
import com.dsh.mobile.ui.theme.brandGradient
import com.journeyapps.barcodescanner.CaptureActivity
import java.util.UUID
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

    // —— 连接成功后：申请通知权限 + 启动后台提醒服务 ——
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    fun onConnectedActions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, DshConnectionService::class.java))
        }
    }

    // —— 扫码连接：先申请相机权限，再启动扫描，扫到即自动连接 ——
    val scannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanned = result.data?.getStringExtra("SCAN_RESULT")?.trim().orEmpty()
            if (scanned.isNotEmpty()) {
                url = scanned
                errorMessage = null
                isLoading = true
                scope.launch {
                    settingsStore.saveConnection(ConnectionConfig(serverUrl = scanned, autoConnect = true))
                }
                connection.connect(
                    HostProfile(
                        id = "legacy-" + UUID.randomUUID().toString(),
                        remark = "旧连接",
                        url = scanned,
                        autoConnect = true,
                    ),
                )
                onConnectedActions()
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scannerLauncher.launch(Intent(context, CaptureActivity::class.java))
        } else {
            Toast.makeText(context, "需要相机权限才能扫码连接", Toast.LENGTH_SHORT).show()
        }
    }

    fun startScan() {
        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    // 载入保存的地址并自动连接
    LaunchedEffect(Unit) {
        val config = settingsStore.connectionConfig.first()
        if (config.serverUrl.isNotEmpty()) {
            url = config.serverUrl
            if (config.autoConnect) {
                connection.connect(
                    HostProfile(
                        id = "legacy-" + UUID.randomUUID().toString(),
                        remark = "旧连接",
                        url = config.serverUrl,
                        autoConnect = true,
                    ),
                )
                onConnectedActions()
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
        connection.connect(
            HostProfile(
                id = "legacy-" + UUID.randomUUID().toString(),
                remark = "旧连接",
                url = u,
                autoConnect = true,
            ),
        )
        onConnectedActions()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 让开状态栏/灵动岛：整页内容（含右上角扫码按钮）从状态栏下方开始
            .statusBarsPadding(),
    ) {
        // 滚动容器包住满高 Column：内容垂直居中（上下弹性留白），小屏高度不足时自动可滚动
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f).height(48.dp))

            Surface(
                shape = DshShape.card,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(84.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // 品牌鲸鱼（灵感来自 DeepSeek 官方标识，黑白负空间风格）
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "DSH Remote",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "遥控你电脑上的 DeepSeek Harness 智能体",
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
                    .height(54.dp)
                    .background(brandGradient(), DshShape.pill),
                enabled = url.isNotBlank() && !isLoading,
                shape = DshShape.pill,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
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
                        "连接方式",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "① 局域网（同一 Wi-Fi）：PC 运行 dsh-remote-start.ps1，填 PC 局域网 IP\n\n" +
                            "② 远程穿透（任何网络）：PC 用 cpolar 或 DSH 设置里的「远程控制」生成公网地址\n\n" +
                            "③ 扫码连接：点右上角扫码图标，扫描「远程控制」页面生成的二维码，自动连接",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified,
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            val versionName = remember {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "?"
            }
            Text(
                "DSH Remote v$versionName · 非官方客户端 · 数据只存你的手机与你的服务器",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(20.dp))
            // 底部弹性留白：与顶部对称，内容整体垂直居中
            Spacer(Modifier.weight(1f).height(48.dp))
            }
        }

        // 右上角扫码按钮（根 Box 已做 statusBarsPadding，不会再被状态栏压住）
        IconButton(
            onClick = { startScan() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        ) {
            Icon(
                Icons.Default.QrCodeScanner,
                contentDescription = "扫码连接",
                tint = DshBrand,
            )
        }
    }
}
