package com.dsh.mobile.ui.screens

import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.mobile.R
import com.dsh.mobile.data.*
import com.dsh.mobile.ui.theme.DshBrand
import com.dsh.mobile.ui.theme.DshBrandSoft
import com.dsh.mobile.ui.theme.DshSuccess
import com.dsh.mobile.ui.theme.DshShape
import com.dsh.mobile.ui.theme.DshThemeId
import com.dsh.mobile.ui.theme.DshThemeStyle
import com.dsh.mobile.ui.theme.ThemeStyle
import com.dsh.mobile.ui.theme.brandGradient
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
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
    approvalCenter: ApprovalCenter,
    onPending: () -> Unit,
    onSessionClick: (String) -> Unit,
    onSettings: () -> Unit,
    onUpgrade: () -> Unit,
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

    // —— 浏览 PC 目录（任选目录作工作区，不限于 DSH 已划定的）——
    var browseOpen by remember { mutableStateOf(false) }
    var browsePath by remember { mutableStateOf("C:\\") }
    var browseDirs by remember { mutableStateOf<List<String>>(emptyList()) }
    var browseLoading by remember { mutableStateOf(false) }
    var browseError by remember { mutableStateOf<String?>(null) }

    fun loadBrowseDir(path: String) {
        browseLoading = true
        browseError = null
        scope.launch {
            val dirs = connection.listDirectory(path)
            browsePath = path
            browseDirs = dirs
            browseLoading = false
        }
    }

    fun enterBrowseDir(name: String) {
        loadBrowseDir(browsePath.trimEnd('\\') + "\\" + name)
    }

    fun browseUp() {
        var p = browsePath.trimEnd('\\')
        if (p.length <= 3) return // 盘符根（如 C:\）无上级
        val idx = p.lastIndexOf('\\')
        if (idx < 0) return
        val parent = p.substring(0, idx + 1)
        loadBrowseDir(parent)
    }

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
    val pendingCount by approvalCenter.pendingCount.collectAsState()

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

    // 工作区选择面板（所有布局分支共用的覆盖层；DeepLook 分支不渲染 Scaffold，也必须挂这里）
    val workspaceSheetOverlay: @Composable () -> Unit = {
        if (workspaceSheet) {
            ModalBottomSheet(onDismissRequest = { workspaceSheet = false }) {
                WorkspaceSheetContent(
                    workspaces = workspaces,
                    selectedWorkspaceId = selectedWorkspaceId,
                    newWsPath = newWsPath,
                    onNewWsPath = { newWsPath = it; wsError = null },
                    newWsTitle = newWsTitle,
                    onNewWsTitle = { newWsTitle = it },
                    wsBusy = wsBusy,
                    wsError = wsError,
                    onSetError = { wsError = it },
                    // —— 浏览 PC 目录 ——
                    browseOpen = browseOpen,
                    onBrowseOpen = {
                        browseOpen = true
                        loadBrowseDir(browsePath)
                    },
                    browsePath = browsePath,
                    browseDirs = browseDirs,
                    browseLoading = browseLoading,
                    browseError = browseError,
                    onBrowseEnter = { enterBrowseDir(it) },
                    onBrowseUp = { browseUp() },
                    onBrowseBack = { browseOpen = false },
                    onBrowsePick = {
                        // 选中当前浏览目录 → 创建为工作区并选中
                        val path = browsePath.trimEnd('\\')
                        val title = path.substringAfterLast('\\')
                        wsBusy = true
                        scope.launch {
                            try {
                                connection.createWorkspace(path, title.ifBlank { null })
                                refreshArchived()
                                browseOpen = false
                                workspaceSheet = false
                            } catch (e: Exception) {
                                browseError = e.message
                            } finally {
                                wsBusy = false
                            }
                        }
                    },
                    onPickDefault = {
                        selectedWorkspaceId = ""
                        scope.launch { settingsStore.setWorkspaceId("") }
                        workspaceSheet = false
                    },
                    onPickWorkspace = { id ->
                        selectedWorkspaceId = id
                        scope.launch { settingsStore.setWorkspaceId(id) }
                        workspaceSheet = false
                    },
                    onCreateWorkspace = { path, title ->
                        wsBusy = true
                        scope.launch {
                            try {
                                connection.createWorkspace(path, title.ifBlank { null })
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
                )
            }
        }
    }

    // ChatGPT 移动端布局：纯黑扁平 + 顶部导航栏 + 侧边抽屉（85% 宽 + 遮罩）+ 底部胶囊输入栏
    if (DshThemeStyle == ThemeStyle.CHATGPT) {
        ChatGptHomeLayout(
            sessions = sessions,
            archivedIds = archivedIds,
            input = input,
            onInputChange = { input = it },
            sending = sending,
            onSend = { send() },
            sendError = sendError,
            preset = preset,
            presets = presets,
            presetMenu = presetMenu,
            onPresetMenuChange = { presetMenu = it },
            pendingImages = pendingImages,
            onPickImage = { imagePicker.launch("image/*") },
            onRemoveImage = { i -> pendingImages = pendingImages.filterIndexed { j, _ -> j != i } },
            onSessionClick = onSessionClick,
            onSettings = onSettings,
            onUpgrade = onUpgrade,
            onPending = onPending,
            pendingCount = pendingCount,
            onRefresh = {
                refreshSessions()
                refreshArchived()
            },
            onRestoreArchived = { id ->
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
            },
        )
        return
    }

    // Claude 移动端布局：暖炭黑 + 极简顶部导航（无背景图标）+ 28px 超大圆角输入容器 + 衬线空态标语 + 侧边抽屉
    if (DshThemeStyle == ThemeStyle.CLAUDE) {
        ClaudeHomeLayout(
            sessions = sessions,
            archivedIds = archivedIds,
            input = input,
            onInputChange = { input = it },
            sending = sending,
            onSend = { send() },
            sendError = sendError,
            preset = preset,
            presets = presets,
            presetMenu = presetMenu,
            onPresetMenuChange = { presetMenu = it },
            pendingImages = pendingImages,
            onPickImage = { imagePicker.launch("image/*") },
            onRemoveImage = { i -> pendingImages = pendingImages.filterIndexed { j, _ -> j != i } },
            onNewChat = {
                input = ""
                pendingImages = emptyList()
            },
            onSessionClick = onSessionClick,
            onSettings = onSettings,
            onUpgrade = onUpgrade,
            onPending = onPending,
            pendingCount = pendingCount,
            onRefresh = {
                refreshSessions()
                refreshArchived()
            },
            onRestoreArchived = { id ->
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
            },
        )
        return
    }

    // DeepLook 布局（DeepSeek 移动端 1:1）：鲸鱼顶栏 + 大标题 + iOS 分组卡片 + 底部三 tab 导航
    if (DshThemeStyle == ThemeStyle.DEEPLOOK) {
        Box {
            DeepLookHomeLayout(
                sessions = sessions,
                workspaces = workspaces,
                selectedWorkspaceId = selectedWorkspaceId,
                onWorkspaceClick = { workspaceSheet = true },
                input = input,
                onInputChange = { input = it },
                sending = sending,
                onSend = { send() },
                sendError = sendError,
                preset = preset,
                presets = presets,
                presetMenu = presetMenu,
                onPresetMenuChange = { presetMenu = it },
                pendingImages = pendingImages,
                onPickImage = { imagePicker.launch("image/*") },
                onRemoveImage = { i -> pendingImages = pendingImages.filterIndexed { j, _ -> j != i } },
                onSessionClick = onSessionClick,
                onSettings = onSettings,
                onPending = onPending,
                pendingCount = pendingCount,
                onRefresh = {
                    refreshSessions()
                    refreshArchived()
                },
            )
            // 工作区选择面板覆盖层（DeepLook 分支不渲染 Scaffold，这里单独挂载）
            workspaceSheetOverlay()
        }
        return
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
                    PendingBell(pendingCount = pendingCount, onPending = onPending)
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
                // 问候区：STANDARD 用官方风格大标题；CODEX 用终端提示符风格；CYBERPUNK 用 HUD 风格
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            when (DshThemeStyle) {
                                ThemeStyle.CODEX -> Text(
                                    "❯",
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = DshBrand,
                                )
                                ThemeStyle.CYBERPUNK -> Text(
                                    "◈",
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Black,
                                    color = DshBrand,
                                )
                                else -> {}
                            }
                            if (DshThemeStyle != ThemeStyle.STANDARD) Spacer(Modifier.width(10.dp))
                            Text(
                                when (DshThemeStyle) {
                                    ThemeStyle.CODEX -> "新任务"
                                    ThemeStyle.CYBERPUNK -> "新任务"
                                    else -> "你好 👋"
                                },
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            "今天想交给智能体什么任务？",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 深蓝主题专属：鲸鱼娘女仆立绘（dsh-deep-whale 皮肤资产，CC BY-NC-SA 4.0）
                    if (DshThemeId == "blue") {
                        Spacer(Modifier.width(8.dp))
                        Image(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.maid_left),
                            contentDescription = "鲸鱼娘",
                            modifier = Modifier
                                .width(96.dp)
                                .height(130.dp)
                                .graphicsLayer { alpha = 0.95f },
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
                // 品牌装饰：STANDARD 用渐变条；CODEX 用终端字符分隔线；CYBERPUNK 用双线 HUD 条
                Spacer(Modifier.height(10.dp))
                when (DshThemeStyle) {
                    ThemeStyle.CODEX -> Text(
                        "─".repeat(26),
                        style = MaterialTheme.typography.labelSmall,
                        color = DshBrand,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                    ThemeStyle.CYBERPUNK -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(18.dp).height(3.dp).background(DshBrand))
                        Spacer(Modifier.width(4.dp))
                        Box(Modifier.width(10.dp).height(3.dp).background(DshBrandSoft))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "NIGHT CITY // 2077",
                            style = MaterialTheme.typography.labelSmall,
                            color = DshBrandSoft,
                        )
                    }
                    else -> Box(
                        Modifier
                            .width(36.dp)
                            .height(3.dp)
                            .background(brandGradient(), DshShape.small),
                    )
                }
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
                        // 卡片头行：任务标签 + 预设选择（对齐 Web 端 hero 卡结构）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 深蓝主题专属：工作区盾徽（dsh-deep-whale 皮肤装饰）
                            if (DshThemeId == "blue") {
                                Image(
                                    painter = androidx.compose.ui.res.painterResource(R.drawable.maid_shield),
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                if (DshThemeStyle == ThemeStyle.CODEX) "❯ 新任务"
                                else if (DshThemeStyle == ThemeStyle.CYBERPUNK) "◈ 新任务"
                                else "新任务",
                                style = MaterialTheme.typography.labelMedium,
                                color = DshBrand,
                            )
                            Spacer(Modifier.weight(1f))
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
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    if (DshThemeStyle == ThemeStyle.CODEX) "> 输入任务指令…" else "描述你的任务，例如：审查最近改动并给出改进建议",
                                )
                            },
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
                        if (DshThemeId == "blue") {
                            // 深蓝主题专属：鲸鱼娘立绘空态
                            Image(
                                painter = androidx.compose.ui.res.painterResource(R.drawable.maid_left),
                                contentDescription = "鲸鱼娘",
                                modifier = Modifier.width(130.dp),
                                contentScale = ContentScale.Fit,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "还没有会话，从上面的输入框开始第一个任务吧",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
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
                                if (DshThemeStyle == ThemeStyle.CYBERPUNK) "— NIGHT CITY // 2077 —" else "还没有会话，从上面的输入框开始第一个任务吧",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
            workspaceSheetOverlay()
        }
    }
}

/** 首页待办铃铛：徽章数字 = 待办总数，点击进入待办中心 */
@Composable
private fun PendingBell(
    pendingCount: Int,
    onPending: () -> Unit,
    tint: Color = Color.Unspecified,
) {
    IconButton(onClick = onPending) {
        BadgedBox(
            badge = {
                if (pendingCount > 0) Badge { Text(pendingCount.toString()) }
            },
        ) {
            Icon(Icons.Default.Notifications, contentDescription = "待办中心", tint = tint)
        }
    }
}

/** 工作区选择面板内容（所有布局分支共用） */
@Composable
private fun WorkspaceSheetContent(
    workspaces: List<WorkspaceView>,
    selectedWorkspaceId: String,
    newWsPath: String,
    onNewWsPath: (String) -> Unit,
    newWsTitle: String,
    onNewWsTitle: (String) -> Unit,
    wsBusy: Boolean,
    wsError: String?,
    onSetError: (String?) -> Unit,
    browseOpen: Boolean,
    onBrowseOpen: () -> Unit,
    browsePath: String,
    browseDirs: List<String>,
    browseLoading: Boolean,
    browseError: String?,
    onBrowseEnter: (String) -> Unit,
    onBrowseUp: () -> Unit,
    onBrowseBack: () -> Unit,
    onBrowsePick: () -> Unit,
    onPickDefault: () -> Unit,
    onPickWorkspace: (String) -> Unit,
    onCreateWorkspace: (String, String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Text(
            if (browseOpen) "浏览电脑目录" else "新对话工作区",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (browseOpen) "任意选择一个 PC 端文件夹作为工作区（不限于 DSH 已划定的工作区）。"
            else "新对话将创建在所选工作区（PC 端目录）下，切换后自动记住。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        if (browseOpen) {
            // ── 目录浏览器 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onBrowseUp,
                    enabled = !browseLoading,
                ) {
                    Icon(Icons.Default.ArrowUpward, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("上级")
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    browsePath,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            browseError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.dp))
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
            ) {
                if (browseLoading) {
                    Box(
                        Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp))
                    }
                } else if (browseDirs.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "（没有子目录，或 PC 端插件未更新）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        browseDirs.forEach { name ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onBrowseEnter(name) }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    null,
                                    Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onBrowsePick,
                enabled = !wsBusy && !browseLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (wsBusy) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("选这个目录作为工作区")
                }
            }
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = onBrowseBack,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("返回") }
            Spacer(Modifier.height(28.dp))
            return@Column
        }

        // 浏览入口（不限于 DSH 已划定工作区）
        WorkspaceRow(
            icon = Icons.Default.FolderOpen,
            title = "浏览电脑目录…",
            subtitle = "任选 PC 端文件夹作为工作区",
            selected = false,
            onClick = onBrowseOpen,
        )

        // 默认工作区
        WorkspaceRow(
            icon = Icons.Default.Home,
            title = "默认工作区",
            subtitle = "跟随 DSH 的默认目录",
            selected = selectedWorkspaceId.isBlank(),
            onClick = onPickDefault,
        )

        // 已有工作区
        workspaces.forEach { ws ->
            WorkspaceRow(
                icon = Icons.Default.Folder,
                title = ws.title.ifBlank { ws.path },
                subtitle = ws.path,
                selected = selectedWorkspaceId == ws.workspaceId,
                onClick = { onPickWorkspace(ws.workspaceId) },
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
            onValueChange = onNewWsPath,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("PC 端目录绝对路径，如 E:\\AI搓的小东西") },
            label = { Text("目录路径（必须已存在）") },
            singleLine = true,
            shape = DshShape.small,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = newWsTitle,
            onValueChange = onNewWsTitle,
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
                    onSetError("请填写 PC 端目录路径")
                    return@Button
                }
                if (wsBusy) return@Button
                onCreateWorkspace(path, newWsTitle.trim())
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
    // 夜之城：会话 = 数据碎片，按稀有度上色（白/绿/青/紫/橙循环）；运行中 = 传奇橙黄
    val ncRarity = listOf(
        Color(0xFFE8F1FF), Color(0xFF00E5A0), Color(0xFF00F0FF), Color(0xFFB484FF), Color(0xFFFF9F40),
    )
    val titleColor = when {
        DshThemeStyle != ThemeStyle.CYBERPUNK -> Color.Unspecified
        session.running -> Color(0xFFFFB300)
        else -> ncRarity[(session.sessionId.hashCode() and Int.MAX_VALUE) % ncRarity.size]
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
                        fontWeight = if (DshThemeStyle == ThemeStyle.CYBERPUNK) FontWeight.Bold else FontWeight.Medium,
                        color = titleColor,
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

// ════════════════════════ Claude 移动端布局（主题包风格）════════════════════════
// 按用户提供的 1:1 设计令牌落地：
// 暖炭黑 #171716 / 次级 #242423 / 三级 #2A2A29 / 陶土橙 #E8755A / 淡紫 #A78BFA
// 衬线大标题 32/600、空态标语 36/500、输入容器 28px 超大圆角、极简顶部导航（无背景图标）

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClaudeHomeLayout(
    sessions: List<SessionSummary>,
    archivedIds: List<String>,
    input: String,
    onInputChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
    sendError: String?,
    preset: String,
    presets: List<Pair<String, String>>,
    presetMenu: Boolean,
    onPresetMenuChange: (Boolean) -> Unit,
    pendingImages: List<DshConnection.ImagePart>,
    onPickImage: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    onNewChat: () -> Unit,
    onSessionClick: (String) -> Unit,
    onSettings: () -> Unit,
    onUpgrade: () -> Unit,
    onPending: () -> Unit,
    pendingCount: Int,
    onRefresh: () -> Unit,
    onRestoreArchived: (String) -> Unit,
) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = Color.Black.copy(alpha = 0.5f),
        drawerContent = {
            Column(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.85f)
                    .background(Color(0xFF171716))
                    .statusBarsPadding(),
            ) {
                // ── 顶部标题区：衬线大标题 32/600 + New chat（橙色加号 24 + 18sp）──
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 24.dp),
                ) {
                    Text(
                        "DSH Remote",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF5F5F4),
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                closeDrawer()
                                onNewChat()
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.AddComment,
                            null,
                            Modifier.size(24.dp),
                            tint = Color(0xFFE8755A),
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            "New chat",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFE8755A),
                        )
                    }
                }

                // ── 功能菜单区：Chats / Projects / Artifacts（56dp、图标 24、文字 18）──
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                ) {
                    val menuItems = listOf(
                        Triple(Icons.Default.ChatBubbleOutline, "Chats") { closeDrawer() },
                        Triple(Icons.Default.Folder, "Projects") {
                            Toast.makeText(context, "移动端暂未开放", Toast.LENGTH_SHORT).show()
                        },
                        Triple(Icons.Default.Category, "Artifacts") {
                            Toast.makeText(context, "移动端暂未开放", Toast.LENGTH_SHORT).show()
                        },
                    )
                    menuItems.forEach { (icon, label, action) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 12.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { action() },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(icon, null, Modifier.size(24.dp), tint = Color(0xFFF5F5F4))
                            Spacer(Modifier.width(16.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color(0xFFF5F5F4),
                            )
                        }
                    }
                }

                // ── 1px 细分割线 #2A2A29 ──
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .height(1.dp)
                        .background(Color(0xFF2A2A29)),
                )

                // ── Recents 分区（可滚动）：16/500 二级文本标题，48dp 项 ──
                Text(
                    "Recents",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFA8A29E),
                    modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 8.dp),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (sessions.isEmpty() && archivedIds.isEmpty()) {
                        Text(
                            "暂无会话",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF78716C),
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                        )
                    }
                    sessions.forEach { s ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .padding(horizontal = 32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    closeDrawer()
                                    onSessionClick(s.sessionId)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                chatGptTitleOf(s),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFF5F5F4),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (archivedIds.isNotEmpty()) {
                        Text(
                            "已归档",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFA8A29E),
                            modifier = Modifier.padding(start = 32.dp, top = 12.dp, bottom = 4.dp),
                        )
                        archivedIds.forEach { id ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .padding(horizontal = 32.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "会话 ${id.take(8)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFA8A29E),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(onClick = { onRestoreArchived(id) }) { Text("恢复") }
                            }
                        }
                    }
                }

                // ── 底部用户栏：40 圆头像（浅灰底深灰字母）+ 用户名 + 齿轮 24 无背景 ──
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .background(Color(0xFF2A2A29), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "D",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF78716C),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "DSH Remote",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color(0xFFF5F5F4),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PendingBell(
                            pendingCount = pendingCount,
                            onPending = {
                                closeDrawer()
                                onPending()
                            },
                            tint = Color(0xFFA8A29E),
                        )
                        Icon(
                            Icons.Default.Settings,
                            "设置",
                            Modifier
                                .size(24.dp)
                                .clickable {
                                    closeDrawer()
                                    onSettings()
                                },
                            tint = Color(0xFFA8A29E),
                        )
                    }
                }
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF171716))
                // 让开状态栏/灵动岛
                .statusBarsPadding(),
        ) {
            // ── 顶部极简导航 56dp：纯图标无背景（汉堡 24 / 幽灵星芒 28），无中间标题 ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Icon(
                    Icons.Default.Menu,
                    "菜单",
                    Modifier
                        .size(24.dp)
                        .clickable { scope.launch { drawerState.open() } },
                    tint = Color(0xFFF5F5F4),
                )
                Icon(
                    Icons.Default.AutoAwesome,
                    "Claude 风格品牌图标",
                    Modifier
                        .size(28.dp)
                        .clickable { onRefresh() },
                    tint = Color(0xFFF5F5F4),
                )
            }

            // ── 内容空状态：垂直居中——64×64 陶土橙星芒 + 衬线 36sp 标语（间距 32）──
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    null,
                    Modifier.size(64.dp),
                    tint = Color(0xFFE8755A),
                )
                Spacer(Modifier.height(32.dp))
                Text(
                    "今天有什么可以帮你的？",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color(0xFFF5F5F4),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                )
                if (pendingImages.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pendingImages.forEachIndexed { i, _ ->
                            AssistChip(
                                onClick = { onRemoveImage(i) },
                                label = { Text("图片 ${i + 1}") },
                                trailingIcon = {
                                    Icon(Icons.Default.Close, null, Modifier.size(14.dp))
                                },
                            )
                        }
                    }
                }
            }

            // ── 底部输入容器：16 外边距 + 安全区；#242423 圆角 28 内边距 12，三层结构 ──
            sendError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color(0xFF242423),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    // 1. Pro 入口行（有实际功能：进入趣味假订阅中心）
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onUpgrade() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Get more with DSH",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFA8A29E),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.WorkspacePremium,
                                null,
                                Modifier.size(14.dp),
                                tint = Color(0xFFA78BFA),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Upgrade to Pro",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFA78BFA),
                            )
                        }
                    }
                    // 2. 多行文本输入区（无边框无背景，17sp 一级文本，橙色光标）
                    BasicTextField(
                        value = input,
                        onValueChange = onInputChange,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFFF5F5F4)),
                        cursorBrush = SolidColor(Color(0xFFE8755A)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        minLines = 2,
                        maxLines = 6,
                        decorationBox = { inner ->
                            Box {
                                if (input.isEmpty()) {
                                    Text(
                                        "给 DSH 发消息…",
                                        color = Color(0xFFA8A29E),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    // 3. 底部操作栏：+ 40 圆 / 模型胶囊 40 / 发送 44 圆橙箭头（无意义的装饰元素已移除）
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .background(Color(0xFF2A2A29), CircleShape)
                                .clickable { onPickImage() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Add, null, Modifier.size(20.dp), tint = Color(0xFFF5F5F4))
                        }
                        // 模型选择胶囊（映射 "Sonnet 5 Thinking" = 预设名）
                        Box {
                            Row(
                                Modifier
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color(0xFF2A2A29))
                                    .clickable { onPresetMenuChange(true) }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    presets.firstOrNull { it.first == preset }?.second ?: preset,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFA8A29E),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            DropdownMenu(
                                expanded = presetMenu,
                                onDismissRequest = { onPresetMenuChange(false) },
                            ) {
                                presets.forEach { (id, name) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = { onPresetMenuChange(false) },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier
                                .size(44.dp)
                                .background(
                                    if (input.isNotBlank() || pendingImages.isNotEmpty()) Color(0xFFE8755A)
                                    else Color(0xFF2A2A29),
                                    CircleShape,
                                )
                                .clickable(
                                    enabled = (input.isNotBlank() || pendingImages.isNotEmpty()) && !sending,
                                ) { onSend() },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (sending) {
                                CircularProgressIndicator(
                                    Modifier.size(18.dp),
                                    color = Color(0xFFF5F5F4),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    Icons.Default.ArrowUpward,
                                    null,
                                    Modifier.size(20.dp),
                                    tint = Color(0xFFF5F5F4),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ════════════════════════ DeepLook 布局（DeepSeek 移动端 1:1）════════════════════════
// 依据 design/dsh-mobile-ui-spec.md（5 张实机截图提取）：
// #f8f8f8 页面底 + 纯白分组卡片（圆角16 阴影1dp）+ 品牌蓝 #4d6bfe + 深蓝黑 #0d1b2a 选中态
// 顶栏 46dp 鲸鱼 logo + 大标题 30/700 + 底部导航 78dp 三 tab（会话 | ⊕新会话(深蓝黑方块) | 设置）

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun DeepLookHomeLayout(
    sessions: List<SessionSummary>,
    workspaces: List<WorkspaceView>,
    selectedWorkspaceId: String,
    onWorkspaceClick: () -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
    sendError: String?,
    preset: String,
    presets: List<Pair<String, String>>,
    presetMenu: Boolean,
    onPresetMenuChange: (Boolean) -> Unit,
    pendingImages: List<DshConnection.ImagePart>,
    onPickImage: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    onSessionClick: (String) -> Unit,
    onSettings: () -> Unit,
    onPending: () -> Unit,
    pendingCount: Int,
    onRefresh: () -> Unit,
) {
    val deep = Color(0xFF0D1B2A)
    // 深色变体自适应：浅色下深蓝黑做前景强调（规范），深色下改用亮色前景 + 品牌蓝方块（否则黑底黑字不可见）
    val darkVariant = DshThemeId == "deeplook-dark"
    val deepAccent = if (darkVariant) MaterialTheme.colorScheme.onSurface else deep
    val deepBlock = if (darkVariant) MaterialTheme.colorScheme.primary else deep
    val faintIcon = if (darkVariant) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF8A8A8E)
    val chevronTint = if (darkVariant) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFB4B4B8)
    val dashTint = if (darkVariant) MaterialTheme.colorScheme.outline else Color(0xFFC9C9CD)
    var tab by remember { mutableStateOf("new") } // new | sessions
    // 默认工作区（未选择）显示「默认工作区」，与工作区选择面板口径一致
    val wsLabel = when {
        selectedWorkspaceId.isBlank() -> "默认工作区"
        else -> workspaces.firstOrNull { it.workspaceId == selectedWorkspaceId }
            ?.let { it.title.ifBlank { it.path } }
            ?: "工作区 ${selectedWorkspaceId.take(8)}"
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 关键：让开状态栏/灵动岛，顶栏不再被遮挡
            .statusBarsPadding(),
    ) {
        // ── 顶栏 46dp：深蓝黑鲸鱼 logo(30 圆角9) + DeepSeek Harness(17/600) + 设置 ──
        Row(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(deepBlock),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "DeepSeek Harness",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            PendingBell(pendingCount = pendingCount, onPending = onPending)
            Icon(
                Icons.Default.Settings,
                "设置",
                Modifier
                    .size(22.dp)
                    .clickable { onSettings() },
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        // ── 大标题 30/700 ──
        Text(
            if (tab == "new") "新会话" else "会话",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // ── 内容区（分组列表）──
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            if (tab == "new") {
                // 分组「工作区」
                DeepLookGroupTitle("工作区")
                DeepLookCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onWorkspaceClick() }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Dashboard,
                            null,
                            Modifier.size(20.dp),
                            tint = deepAccent,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            wsLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            Icons.Default.ChevronRight,
                            null,
                            Modifier.size(16.dp),
                            tint = chevronTint,
                        )
                    }
                }

                // 分组「给智能体派个任务」
                DeepLookGroupTitle("给智能体派个任务")
                DeepLookCard {
                    Column(Modifier.padding(14.dp)) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = onInputChange,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("描述你的任务…") },
                            minLines = 3,
                            maxLines = 6,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions.Default,
                        )
                        if (pendingImages.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                pendingImages.forEachIndexed { i, _ ->
                                    AssistChip(
                                        onClick = { onRemoveImage(i) },
                                        label = { Text("图片 ${i + 1}") },
                                        trailingIcon = {
                                            Icon(Icons.Default.Close, null, Modifier.size(14.dp))
                                        },
                                    )
                                }
                            }
                        }
                        sendError?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onPickImage) {
                                Icon(Icons.Default.AttachFile, null)
                            }
                            Box {
                                AssistChip(
                                    onClick = { onPresetMenuChange(true) },
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
                                        Icon(Icons.Default.SmartToy, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    },
                                )
                                DropdownMenu(
                                    expanded = presetMenu,
                                    onDismissRequest = { onPresetMenuChange(false) },
                                ) {
                                    presets.forEach { (id, name) ->
                                        DropdownMenuItem(
                                            text = { Text(name) },
                                            onClick = { onPresetMenuChange(false) },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        // 主按钮：全宽 50dp 高、圆角 13、品牌蓝、白字 16/600
                        Button(
                            onClick = onSend,
                            enabled = (input.isNotBlank() || pendingImages.isNotEmpty()) && !sending,
                            shape = RoundedCornerShape(13.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                        ) {
                            if (sending) {
                                CircularProgressIndicator(
                                    Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "发送",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            } else {
                // 会话列表分组卡片
                DeepLookGroupTitle("最近会话")
                if (sessions.isEmpty()) {
                    // 空状态：72dp 虚线圆环 + 内部图标 + 「暂无会话」14sp
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.fillMaxSize()) {
                                drawCircle(
                                    color = dashTint,
                                    style = Stroke(
                                        width = 2.dp.toPx(),
                                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                            floatArrayOf(8f, 8f), 0f,
                                        ),
                                    ),
                                )
                            }
                            Icon(
                                Icons.Default.ChatBubbleOutline,
                                null,
                                Modifier.size(28.dp),
                                tint = dashTint,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "暂无会话",
                            style = MaterialTheme.typography.bodySmall,
                            color = faintIcon,
                        )
                    }
                } else {
                    DeepLookCard {
                        sessions.forEachIndexed { index, s ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onSessionClick(s.sessionId) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        chatGptTitleOf(s),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                                            .format(Date(s.updatedAt)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = faintIcon,
                                    )
                                }
                                if (s.running) {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Icon(
                                    Icons.Default.ChevronRight,
                                    null,
                                    Modifier.size(16.dp),
                                    tint = chevronTint,
                                )
                            }
                            if (index < sessions.lastIndex) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    thickness = 1.dp,
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // ── 底部导航 78dp：三 tab 等宽（会话 | ⊕新会话 深蓝黑方块高亮 | 设置）──
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                )
                .padding(top = 8.dp)
                .navigationBarsPadding()
                .height(70.dp),
        ) {
            // 会话
            DeepLookTab(modifier = Modifier.weight(1f),
                selected = tab == "sessions",
                onClick = { tab = "sessions" },
                deep = deep,
            ) {
                Icon(
                    Icons.Default.ChatBubbleOutline,
                    null,
                    Modifier.size(24.dp),
                    tint = if (tab == "sessions") deep else faintIcon,
                )
                Text(
                    "会话",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tab == "sessions") deep else faintIcon,
                )
            }
            // 新会话（深蓝黑圆角方块 + 白色加号，当前高亮）
            DeepLookTab(modifier = Modifier.weight(1f),
                selected = tab == "new",
                onClick = { tab = "new" },
                deep = deep,
            ) {
                Box(
                    Modifier
                        .size(width = 44.dp, height = 28.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(deepBlock),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(20.dp), tint = Color.White)
                }
                Text(
                    "新会话",
                    style = MaterialTheme.typography.labelSmall,
                    color = deepAccent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // 设置
            DeepLookTab(modifier = Modifier.weight(1f),
                selected = false,
                onClick = { onSettings() },
                deep = deep,
            ) {
                Icon(
                    Icons.Default.Settings,
                    null,
                    Modifier.size(24.dp),
                    tint = faintIcon,
                )
                Text(
                    "设置",
                    style = MaterialTheme.typography.labelSmall,
                    color = faintIcon,
                )
            }
        }
    }
}

/** 分组标题：14/600 次级文字 */
@Composable
private fun DeepLookGroupTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** 分组卡片：纯白、圆角 16、极浅投影 */
@Composable
private fun DeepLookCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(content = content)
    }
}

/** 底部导航 tab（等宽；weight 由调用处传入） */
@Composable
private fun DeepLookTab(
    modifier: Modifier = Modifier,
    selected: Boolean,
    onClick: () -> Unit,
    deep: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
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

// ════════════════════════ ChatGPT 移动端布局（主题包风格）════════════════════════
// 按用户提供的 1:1 设计令牌落地：
// 纯黑 #000000 底、次级 #1C1C1E、品牌 #3B82F6、强调 #60A5FA、二级文本 #9CA3AF
// 顶部导航 56dp / 胶囊输入 52dp(圆角26) / 抽屉 85% 宽 + 50% 遮罩 / 菜单项 56dp / 会话项 48dp

private fun chatGptTitleOf(s: SessionSummary): String = sessionTitleOf(s)
    ?: "会话 ${s.sessionId.take(8)}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatGptHomeLayout(
    sessions: List<SessionSummary>,
    archivedIds: List<String>,
    input: String,
    onInputChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
    sendError: String?,
    preset: String,
    presets: List<Pair<String, String>>,
    presetMenu: Boolean,
    onPresetMenuChange: (Boolean) -> Unit,
    pendingImages: List<DshConnection.ImagePart>,
    onPickImage: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    onSessionClick: (String) -> Unit,
    onSettings: () -> Unit,
    onUpgrade: () -> Unit,
    onPending: () -> Unit,
    pendingCount: Int,
    onRefresh: () -> Unit,
    onRestoreArchived: (String) -> Unit,
) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }

    val filtered = remember(sessions, searchQuery) {
        if (searchQuery.isBlank()) sessions
        else sessions.filter { chatGptTitleOf(it).contains(searchQuery, ignoreCase = true) }
    }

    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = Color.Black.copy(alpha = 0.5f),
        drawerContent = {
            Column(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.85f)
                    .background(Color.Black)
                    .statusBarsPadding(),
            ) {
                // ── 顶部标题区（60dp）：DSH Remote + 搜索圆钮 ──
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "DSH Remote",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(Color(0xFF1C1C1E), CircleShape)
                            .clickable { searchActive = !searchActive },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = Color.White)
                    }
                }
                if (searchActive) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xFF1C1C1E),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                    ) {
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                            cursorBrush = SolidColor(Color.White),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            decorationBox = { inner ->
                                Box {
                                    if (searchQuery.isEmpty()) {
                                        Text("搜索会话…", color = Color(0xFF9CA3AF), style = MaterialTheme.typography.bodyMedium)
                                    }
                                    inner()
                                }
                            },
                        )
                    }
                }

                // ── 功能菜单区：每项 56dp，图标 28 白 + 文字 18 ──
                val menuItems = listOf(
                    Triple(Icons.Default.Add, "新对话") {
                        closeDrawer()
                    },
                    Triple(Icons.Default.MenuBook, "技能") {
                        Toast.makeText(context, "技能请在会话内选择", Toast.LENGTH_SHORT).show()
                        closeDrawer()
                    },
                    Triple(Icons.Default.WorkspacePremium, "Pro 订阅") {
                        closeDrawer()
                        onUpgrade()
                    },
                    Triple(Icons.Default.Archive, "已归档") {
                        showArchived = !showArchived
                    },
                    Triple(Icons.Default.Settings, "设置") {
                        closeDrawer()
                        onSettings()
                    },
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    menuItems.forEach { (icon, label, action) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 12.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { action() },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(icon, null, Modifier.size(28.dp), tint = Color.White)
                            Spacer(Modifier.width(16.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White,
                            )
                        }
                    }
                }

                // ── 最近会话区（可滚动）：分区标题 16/600 #9CA3AF，项 48dp / 17sp ──
                Text(
                    "最近",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF9CA3AF),
                    modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 8.dp),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (filtered.isEmpty() && !showArchived) {
                        // 骨架屏样式空态：40dp #1C1C1E 圆角 8 长条
                        Box(
                            Modifier
                                .padding(horizontal = 32.dp, vertical = 4.dp)
                                .fillMaxWidth()
                                .height(40.dp)
                                .background(Color(0xFF1C1C1E), RoundedCornerShape(8.dp)),
                        )
                    }
                    filtered.forEach { s ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .padding(horizontal = 32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    closeDrawer()
                                    onSessionClick(s.sessionId)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                chatGptTitleOf(s),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (showArchived && archivedIds.isNotEmpty()) {
                        Text(
                            "已归档",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF9CA3AF),
                            modifier = Modifier.padding(start = 32.dp, top = 12.dp, bottom = 4.dp),
                        )
                        archivedIds.forEach { id ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .padding(horizontal = 32.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "会话 ${id.take(8)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF9CA3AF),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(onClick = { onRestoreArchived(id) }) { Text("恢复") }
                            }
                        }
                    }
                }

                // ── 底部操作区：「聊天」主按钮（#3B82F6 52dp 圆角16）+ 设置圆钮 48dp ──
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier
                            .height(52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF3B82F6))
                            .clickable { closeDrawer() }
                            .padding(horizontal = 32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Edit, null, Modifier.size(22.dp), tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("聊天", style = MaterialTheme.typography.labelLarge, color = Color.White)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PendingBell(
                            pendingCount = pendingCount,
                            onPending = {
                                closeDrawer()
                                onPending()
                            },
                            tint = Color.White,
                        )
                        Box(
                            Modifier
                                .size(48.dp)
                                .background(Color(0xFF1C1C1E), CircleShape)
                                .clickable {
                                    closeDrawer()
                                    onSettings()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Settings, null, Modifier.size(24.dp), tint = Color.White)
                        }
                    }
                }
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                // 让开状态栏/灵动岛
                .statusBarsPadding(),
        ) {
            // ── 顶部导航栏 56dp：汉堡 40 圆 | 中间 pill（映射「获取Plus」）| 新对话 40 圆 ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(Color(0xFF1C1C1E), CircleShape)
                        .clickable { scope.launch { drawerState.open() } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Menu, null, Modifier.size(20.dp), tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                // 中间 pill：预设名（#60A5FA 强调，含星标，点击展开预设菜单）
                Box {
                    Row(
                        Modifier
                            .height(40.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF1C1C1E))
                            .clickable { onPresetMenuChange(true) }
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Star,
                            null,
                            Modifier.size(18.dp),
                            tint = Color(0xFF60A5FA),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            presets.firstOrNull { it.first == preset }?.second ?: preset,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF60A5FA),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(
                        expanded = presetMenu,
                        onDismissRequest = { onPresetMenuChange(false) },
                    ) {
                        presets.forEach { (id, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = { onPresetMenuChange(false) },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(40.dp)
                        .background(Color(0xFF1C1C1E), CircleShape)
                        .clickable { onRefresh() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.ChatBubbleOutline, null, Modifier.size(20.dp), tint = Color.White)
                }
            }

            // ── 中间空态区（纯黑 + 灰色提示 + 待发图片）──
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "今天想做点什么？",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF9CA3AF),
                )
                if (pendingImages.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pendingImages.forEachIndexed { i, _ ->
                            AssistChip(
                                onClick = { onRemoveImage(i) },
                                label = { Text("图片 ${i + 1}") },
                                trailingIcon = {
                                    Icon(Icons.Default.Close, null, Modifier.size(14.dp))
                                },
                            )
                        }
                    }
                }
            }

            // ── 底部输入栏：16 外边距 + 安全区；52dp 胶囊容器 #1C1C1E 圆角 26 ──
            sendError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    .height(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color(0xFF1C1C1E))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Add,
                    null,
                    Modifier
                        .size(24.dp)
                        .clickable { onPickImage() },
                    tint = Color.White,
                )
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    decorationBox = { inner ->
                        Box {
                            if (input.isEmpty()) {
                                Text(
                                    "询问 DSH…",
                                    color = Color(0xFF9CA3AF),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(40.dp)
                        .background(
                            if (input.isNotBlank() || pendingImages.isNotEmpty()) Color(0xFF3B82F6)
                            else Color(0xFF2C2C2E),
                            CircleShape,
                        )
                        .clickable(enabled = (input.isNotBlank() || pendingImages.isNotEmpty()) && !sending) {
                            onSend()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (sending) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            null,
                            Modifier.size(20.dp),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}
