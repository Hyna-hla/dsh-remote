package com.dsh.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.mobile.data.*
import com.dsh.mobile.ui.theme.DshBrand
import com.dsh.mobile.ui.theme.DshSuccess
import com.dsh.mobile.ui.theme.DshWarn
import kotlinx.coroutines.launch
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

    val connState by connection.state.collectAsState()

    fun refreshSessions() {
        scope.launch {
            try {
                sessions = connection.listSessions()
                    .sortedByDescending { it.updatedAt }
            } catch (_: Exception) {}
        }
    }

    fun refreshArchived() {
        scope.launch {
            try {
                val w = connection.workspaceList()
                workspaces = w.items
                archivedIds = w.archivedSessionIds
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(Unit) {
        refreshSessions()
        refreshArchived()
        presets = try {
            val p = connection.agentPresets()
            if (p.isNotEmpty()) p else listOf("cordis" to "Cordis")
        } catch (_: Exception) {
            listOf("cordis" to "Cordis")
        }
    }

    // 事件驱动的列表刷新
    LaunchedEffect(Unit) {
        connection.events.collect { ev ->
            when (ev) {
                is DshConnection.Event.SessionAdded,
                is DshConnection.Event.SessionRemoved,
                is DshConnection.Event.SessionStatus,
                -> refreshSessions()
                else -> {}
            }
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isBlank() || sending) return
        sending = true
        sendError = null
        scope.launch {
            try {
                val sid = connection.createSession(agentPreset = preset)
                connection.prompt(sid, text)
                input = ""
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
                Text(
                    "你好 👋",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "今天想交给智能体什么任务？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("描述你的任务，例如：审查最近改动并给出改进建议") },
                            minLines = 3,
                            maxLines = 8,
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
                            // 预设选择
                            Box {
                                AssistChip(
                                    onClick = { presetMenu = true },
                                    label = {
                                        Text(
                                            presets.firstOrNull { it.first == preset }?.second ?: preset,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
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
                                enabled = input.isNotBlank() && !sending,
                                shape = RoundedCornerShape(14.dp),
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
                        Icon(
                            Icons.Default.Inbox,
                            null,
                            Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(10.dp))
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
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
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

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                if (session.running) DshBrand else MaterialTheme.colorScheme.outline,
                                CircleShape,
                            ),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (preview.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!model.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    model,
                    style = MaterialTheme.typography.labelSmall,
                    color = DshWarn,
                )
            }
        }
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
