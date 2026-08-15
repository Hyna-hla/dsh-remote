package com.dsh.mobile.ui.screens

import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.mobile.data.*
import com.dsh.mobile.ui.theme.DshBrand
import com.dsh.mobile.ui.theme.DshSuccess
import com.dsh.mobile.ui.theme.DshShape
import com.dsh.mobile.ui.theme.DshThemeStyle
import com.dsh.mobile.ui.theme.ThemeStyle
import com.dsh.mobile.ui.theme.brandGradient
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    connection: DshConnection,
    onSessionClick: (String) -> Unit,
    onSettings: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    var presets by remember { mutableStateOf<List<Pair<String, String>>>(listOf("cordis" to "Cordis")) }
    var preset by remember { mutableStateOf("cordis") }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var presetMenu by remember { mutableStateOf(false) }
    var archivedIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var workspaces by remember { mutableStateOf<List<WorkspaceView>>(emptyList()) }
    var archiveTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    var pendingImages by remember { mutableStateOf<List<DshConnection.ImagePart>>(emptyList()) }
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val cache = remember { HistoryCache(context) }

    // —— 新对话工作区选择 ——
    var selectedWorkspaceId by remember { mutableStateOf("") }
    var workspaceSheet by remember { mutableStateOf(false) }
    var newWsPath by remember { mutableStateOf("") }
    var newWsTitle by remember { mutableStateOf("") }
    var wsBusy by remember { mutableStateOf(false) }
    var wsError by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@launch
                if (bytes.size > 4 * 1024 * 1024) {
                    sendError = "图片超过 4MB"
                    return@launch
                }
                val type = context.contentResolver.getType(uri) ?: "image/png"
                if (type !in listOf("image/png", "image/jpeg", "image/webp", "image/gif")) {
                    sendError = "不支持的图片格式: $type"
                    return@launch
                }
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                pendingImages = pendingImages + DshConnection.ImagePart(type, b64)
            } catch (e: Exception) {
                sendError = e.message
            }
        }
    }

    val connState by connection.state.collectAsState()

    /** 后台预取最近 3 个会话首屏进内存缓存：点开即消费（SessionChatState.load 取走），跳过网络等待 */
    fun prefetchRecent(list: List<SessionSummary>) {
        scope.launch(Dispatchers.Default) {
            list.take(3).forEach { s ->
                if (HistoryMemoryCache.history(s.sessionId) == null) {
                    runCatching { connection.history(s.sessionId, maxMessages = 3) }
                        .getOrNull()?.let { HistoryMemoryCache.putPrefetch(s.sessionId, it) }
                }
            }
        }
    }

    fun refreshSessions() {
        // 冷热分离：先渲染本地缓存（秒开；gzip 解压+解码移出主线程），再刷网络
        scope.launch {
            val cached = withContext(Dispatchers.Default) { cache.loadSessionList() }
            if (!cached.isNullOrEmpty()) sessions = cached
            try {
                val net = connection.listSessions()
                    .sortedByDescending { it.updatedAt }
                sessions = net
                withContext(Dispatchers.Default) { cache.saveSessionList(net) }
                listError = null
                prefetchRecent(net)
            } catch (e: Exception) {
                if (cached.isNullOrEmpty()) {
                    listError = "会话列表加载失败：" + e.message
                }
            }
        }
    }

    fun refreshArchived() {
        scope.launch {
            try {
                val w = connection.workspaceList()
                workspaces = w.items
                archivedIds = w.archivedSessionIds
            } catch (e: Exception) {
                listError = "工作区加载失败：${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshSessions()
        refreshArchived()
        selectedWorkspaceId = settingsStore.workspaceId.first()
        presets = try {
            val p = connection.agentPresets()
            if (p.isNotEmpty()) p else listOf("cordis" to "Cordis")
        } catch (_: Exception) {
            listOf("cordis" to "Cordis")
        }
        if (presets.none { it.first == preset }) {
            preset = presets.firstOrNull()?.first ?: preset
        }
    }

    // 事件驱动的列表刷新（断线重连成功后立即补拉，避免列表停留在空态）
    LaunchedEffect(Unit) {
        connection.events.collect { ev ->
            when (ev) {
                is DshConnection.Event.SessionAdded,
                is DshConnection.Event.SessionRemoved,
                is DshConnection.Event.SessionStatus,
                is DshConnection.Event.Reconnected,
                -> {
                    refreshSessions()
                    refreshArchived()
                }
                else -> {}
            }
        }
    }

    // 从设置页/会话页返回、App 回前台时强制刷新会话列表，
    // 修复「从设置界面出来之后会话加载不出来」：返回时组合重建但拉取被静默失败吞掉后
    // 列表停在空态，必须在这里兜底重拉。
    LifecycleResumeEffect(Unit) {
        refreshSessions()
        refreshArchived()
        onPauseOrDispose {}
    }

    fun send() {
        val text = input.trim()
        if ((text.isBlank() && pendingImages.isEmpty()) || sending) return
        sending = true
        sendError = null
        scope.launch {
            try {
                val sid = connection.createSession(
                    agentPreset = preset,
                    workspaceId = selectedWorkspaceId.ifBlank { null },
                )
                connection.prompt(sid, text, images = pendingImages)
                input = ""
                pendingImages = emptyList()
                onSessionClick(sid)
            } catch (e: Exception) {
                sendError = e.message
            } finally {
                sending = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("DeepSeek Harness", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(10.dp))
                        val dot = when (connState) {
                            is DshConnection.State.Connected -> DshSuccess
                            else -> MaterialTheme.colorScheme.outline
                        }
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(dot, CircleShape),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // —— 新任务输入卡（Trae 首页风格）——
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                // 问候区：STANDARD 用官方风格大标题；CODEX 用终端提示符风格
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (DshThemeStyle == ThemeStyle.CODEX) {
                        Text(
                            "❯",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = DshBrand,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        if (DshThemeStyle == ThemeStyle.CODEX) "新任务" else "你好 👋",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    "今天想交给智能体什么任务？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 品牌渐变装饰条（官方 hero 区的点睛细节）
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .width(36.dp)
                        .height(3.dp)
                        .background(brandGradient(), DshShape.small),
                )
                Spacer(Modifier.height(10.dp))

                // —— 工作区选择 chip（新对话创建在该工作区，对齐 Web 的 Session Intent hero）——
                val wsSelected = workspaces.firstOrNull { it.workspaceId == selectedWorkspaceId }
                val wsLabel = when {
                    selectedWorkspaceId.isBlank() -> "默认工作区"
                    wsSelected != null -> wsSelected.title.ifBlank { wsSelected.path }
                    else -> "工作区 ${selectedWorkspaceId.take(8)}"
                }
                AssistChip(
                    onClick = { workspaceSheet = true },
                    label = {
                        Text(
                            wsLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Folder,
                            null,
                            Modifier.size(16.dp),
                            tint = DshBrand,
                        )
                    },
                    trailingIcon = {
                        Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp))
                    },
                )
                Spacer(Modifier.height(10.dp))

                // 待发送图片
                if (pendingImages.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        pendingImages.forEachIndexed { i, _ ->
                            AssistChip(
                                onClick = { pendingImages = pendingImages.filterIndexed { j, _ -> j != i } },
                                label = { Text("图片 ${i + 1}") },
                                trailingIcon = {
                                    Icon(Icons.Default.Close, null, Modifier.size(14.dp))
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    // 品牌淡色描边：卡片有设计感但不抢内容
                    border = BorderStroke(1.dp, DshBrand.copy(alpha = if (DshThemeStyle == ThemeStyle.CODEX) 0.45f else 0.22f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("描述你的任务，例如：审查最近改动并给出改进建议") },
                            minLines = 3,
                            maxLines = 6,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions.Default,
                        )
                        sendError?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 图片附件
                            IconButton(onClick = { imagePicker.launch("image/*") }) {
                                Icon(Icons.Default.AttachFile, null)
                            }
                            // 预设选择（限宽：预设名过长时发送键不被挤出屏幕）
                            Box {
                                AssistChip(
                                    onClick = { presetMenu = true },
                                    modifier = Modifier.widthIn(max = 150.dp),
                                    label = {
                                        Text(
                                            presets.firstOrNull { it.first == preset }?.second ?: preset,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.SmartToy,
                                            null,
                                            Modifier.size(16.dp),
                                            tint = DshBrand,
                                        )
                                    },
                                )
                                DropdownMenu(
                                    expanded = presetMenu,
                                    onDismissRequest = { presetMenu = false },
                                ) {
                                    presets.forEach { (id, name) ->
                                        DropdownMenuItem(
                                            text = { Text(name) },
                                            onClick = {
                                                preset = id
                                                presetMenu = false
                                            },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Button(
                                onClick = { send() },
                                enabled = (input.isNotBlank() || pendingImages.isNotEmpty()) && !sending,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Transparent,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                                modifier = Modifier.background(brandGradient(), RoundedCornerShape(14.dp)),
                            ) {
                                if (sending) {
                                    CircularProgressIndicator(
                                        Modifier.size(18.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(Icons.Default.Send, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("发送")
                                }
                            }
                        }
                    }
                }
            }

            // —— 最近会话 ——
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "最近会话",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { refreshSessions() }) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("刷新")
                }
            }

            listError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (sessions.isEmpty() && archivedIds.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // 官方风格空态：品牌底圆 + 鲸鱼 logo
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .background(DshBrand.copy(alpha = 0.12f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = androidx.compose.ui.res.painterResource(com.dsh.mobile.R.drawable.ic_launcher_foreground),
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "还没有会话，从上面的输入框开始第一个任务吧",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                ) {
                    items(sessions, key = { "s_" + it.sessionId }) { s ->
                        SessionCard(
                            session = s,
                            onClick = { onSessionClick(s.sessionId) },
                            onLongClick = { archiveTarget = s },
                        )
                    }
                    if (archivedIds.isNotEmpty()) {
                        item(key = "arch_header") {
                            Text(
                                "已归档（点击恢复）",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        items(archivedIds, key = { "a_" + it }) { id ->
                            ArchivedRow(id = id, onRestore = {
                                scope.launch {
                                    val ws = workspaces.firstOrNull()?.workspaceId
                                    if (ws.isNullOrBlank()) {
                                        listError = "没有可用工作区，无法恢复"
                                    } else {
                                        try {
                                            connection.restoreSession(ws, id)
                                            refreshSessions()
                                            refreshArchived()
                                        } catch (e: Exception) {
                                            listError = e.message
                                        }
                                    }
                                }
                            })
                        }
                    }
                }
            }

            // 归档确认
            archiveTarget?.let { target ->
                AlertDialog(
                    onDismissRequest = { archiveTarget = null },
                    title = { Text("归档会话") },
                    text = { Text("「${target.sessionId.take(8)}」将从会话列表移除，可在已归档区恢复。") },
                    confirmButton = {
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    connection.archiveSession(target.sessionId)
                                    archiveTarget = null
                                    refreshSessions()
                                    refreshArchived()
                                } catch (e: Exception) {
                                    listError = e.message
                                    archiveTarget = null
                                }
                            }
                        }) { Text("归档") }
                    },
                    dismissButton = {
                        TextButton(onClick = { archiveTarget = null }) { Text("取消") }
                    },
                )
            }

            // 工作区选择面板（对齐 Web WorkspacePicker：默认 / 已有工作区 / 新建）
            if (workspaceSheet) {
                ModalBottomSheet(onDismissRequest = { workspaceSheet = false }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                    ) {
                        Text(
                            "新对话工作区",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "新对话将创建在所选工作区（PC 端目录）下，切换后自动记住。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(14.dp))

                        // 默认工作区
                        WorkspaceRow(
                            icon = Icons.Default.Home,
                            title = "默认工作区",
                            subtitle = "跟随 DSH 的默认目录",
                            selected = selectedWorkspaceId.isBlank(),
                            onClick = {
                                selectedWorkspaceId = ""
                                scope.launch { settingsStore.setWorkspaceId("") }
                                workspaceSheet = false
                            },
                        )

                        // 已有工作区
                        workspaces.forEach { ws ->
                            WorkspaceRow(
                                icon = Icons.Default.Folder,
                                title = ws.title.ifBlank { ws.path },
                                subtitle = ws.path,
                                selected = selectedWorkspaceId == ws.workspaceId,
                                onClick = {
                                    selectedWorkspaceId = ws.workspaceId
                                    scope.launch { settingsStore.setWorkspaceId(ws.workspaceId) }
                                    workspaceSheet = false
                                },
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        )

                        // 新建工作区：输入 PC 端目录绝对路径
                        Text(
                            "新建工作区",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newWsPath,
                            onValueChange = { newWsPath = it; wsError = null },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("PC 端目录绝对路径，如 E:\\AI搓的小东西") },
                            label = { Text("目录路径（必须已存在）") },
                            singleLine = true,
                            shape = DshShape.small,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newWsTitle,
                            onValueChange = { newWsTitle = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("可选：显示名称") },
                            label = { Text("标题") },
                            singleLine = true,
                            shape = DshShape.small,
                        )
                        wsError?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                val path = newWsPath.trim()
                                if (path.isEmpty()) {
                                    wsError = "请填写 PC 端目录路径"
                                    return@Button
                                }
                                if (wsBusy) return@Button
                                wsBusy = true
                                scope.launch {
                                    try {
                                        connection.createWorkspace(
                                            path,
                                            newWsTitle.trim().ifBlank { null },
                                        )
                                        newWsPath = ""
                                        newWsTitle = ""
                                        refreshArchived()
                                        workspaceSheet = false
                                    } catch (e: Exception) {
                                        wsError = e.message
                                    } finally {
                                        wsBusy = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = DshShape.small,
                        ) {
                            if (wsBusy) {
                                CircularProgressIndicator(
                                    Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("创建并选中")
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                    }
                }
            }
        }
    }
}

/** 工作区选择行（选中态勾选 + 两行文本） */
@Composable
private fun WorkspaceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            Modifier.size(20.dp),
            tint = if (selected) DshBrand else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) DshBrand else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Default.CheckCircle,
                null,
                Modifier.size(20.dp),
                tint = DshBrand,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: SessionSummary,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val values = runCatching {
        session.projections?.jsonObject?.get("values")?.jsonObject
    }.getOrNull()
    val title = values?.get("title")?.jsonPrimitive?.contentOrNull
        ?: "会话 ${session.sessionId.take(8)}"
    val preview = values?.get("preview")?.jsonPrimitive?.contentOrNull ?: ""
    val model = values?.get("model")?.jsonPrimitive?.contentOrNull
    val time = remember(session.updatedAt) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(session.updatedAt))
    }
    // 运行态脉冲呼吸（官方状态灯的"活着"感）
    val pulse by if (session.running) {
        val t = rememberInfiniteTransition(label = "run")
        t.animateFloat(
            initialValue = 1f, targetValue = 0.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Twitter 风格圆形首字头像（Codex 风格下改方形终端块）
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(
                        if (DshThemeStyle == ThemeStyle.CODEX) DshBrand.copy(alpha = 0.18f)
                        else DshBrand.copy(alpha = 0.14f),
                        if (DshThemeStyle == ThemeStyle.CODEX) RoundedCornerShape(6.dp) else CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = DshBrand,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    if (session.running) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(DshBrand.copy(alpha = pulse), CircleShape),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (preview.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!model.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    // 模型徽章：品牌淡底小圆角 chip（Codex 风 = 终端方角 + 等宽）
                    Surface(
                        shape = if (DshThemeStyle == ThemeStyle.CODEX) RoundedCornerShape(3.dp) else RoundedCornerShape(6.dp),
                        color = DshBrand.copy(alpha = 0.13f),
                    ) {
                        Text(
                            model,
                            style = MaterialTheme.typography.labelSmall,
                            color = DshBrand,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
            thickness = 0.5.dp,
        )
    }
}

@Composable
private fun ArchivedRow(id: String, onRestore: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Archive,
                null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "会话 ${id.take(8)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRestore) { Text("恢复") }
        }
    }
}
