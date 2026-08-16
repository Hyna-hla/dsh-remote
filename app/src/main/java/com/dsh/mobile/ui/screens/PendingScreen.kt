package com.dsh.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dsh.mobile.data.*
import com.dsh.mobile.ui.components.QuestionCard
import com.dsh.mobile.ui.theme.DshShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingScreen(
    center: ApprovalCenter,
    connection: DshConnection,
    onBack: () -> Unit,
    onOpenSession: (sessionId: String, focusSeq: Long?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val items by center.items.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var titles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // 会话标题映射（一次拉取；失败回退 sessionId 前 8 位）
    LaunchedEffect(Unit) {
        titles = runCatching { connection.listSessions() }.getOrDefault(emptyList())
            .associate { it.sessionId to (sessionTitleOf(it) ?: it.sessionId.take(8)) }
    }

    suspend fun runBatch(label: String, block: suspend () -> List<String>) {
        busy = true
        val failures = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listOf(e.message ?: "unknown")
        }
        busy = false
        if (failures.isNotEmpty()) {
            snackbar.showSnackbar("$label：${failures.size} 条失败 —— ${failures.first().take(80)}")
        }
    }

    val approvals = items.filterIsInstance<PendingItem.Approval>()
    val questions = items.filterIsInstance<PendingItem.Question>()
    val errors = items.filterIsInstance<PendingItem.Error>()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("待办中心") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("暂无待办", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "审批、问答与智能体报错会收在这里",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
                if (approvals.isNotEmpty()) {
                    item(key = "ap-h") { SectionHeader("审批（${approvals.size}）") }
                    item(key = "ap-b") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        val nonHigh = approvals.filter {
                                            RiskClassifier.level(it.toolName, it.reason) != RiskLevel.HIGH
                                        }
                                        val skipped = approvals.size - nonHigh.size
                                        val failures = mutableListOf<String>()
                                        for (item in nonHigh) {
                                            try { center.allow(item.sessionId, item.approvalId) }
                                            catch (e: CancellationException) { throw e }
                                            catch (e: Exception) { failures += e.message ?: "unknown" }
                                        }
                                        busy = false
                                        val msg = buildString {
                                            if (failures.isNotEmpty()) append("${failures.size} 条失败 —— ${failures.first().take(80)}")
                                            if (skipped > 0) {
                                                if (isNotEmpty()) append("；")
                                                append("$skipped 项高危已跳过，需逐项确认")
                                            }
                                        }
                                        if (msg.isNotEmpty()) snackbar.showSnackbar(msg)
                                    }
                                },
                                enabled = !busy, modifier = Modifier.weight(1f),
                            ) { Text("全部允许") }
                            OutlinedButton(
                                onClick = { scope.launch { runBatch("全部拒绝") { center.rejectAllApprovals() } } },
                                enabled = !busy, modifier = Modifier.weight(1f),
                            ) { Text("全部拒绝") }
                        }
                    }
                    items(approvals, key = { "ap-" + it.sessionId + "-" + it.approvalId }) { a ->
                        val risk = remember(a.approvalId) { RiskClassifier.level(a.toolName, a.reason) }
                        var confirmDialog by remember(a.approvalId) { mutableStateOf(false) }
                        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = DshShape.card) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        titles[a.sessionId] ?: a.sessionId.take(8),
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "工具 · ${a.toolName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    if (risk == RiskLevel.HIGH) {
                                        Badge(containerColor = MaterialTheme.colorScheme.error) { Text("高危") }
                                    } else if (risk == RiskLevel.MEDIUM) {
                                        Badge(containerColor = MaterialTheme.colorScheme.tertiary) { Text("中危") }
                                    }
                                }
                                a.reason?.let {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "原因：$it",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                runCatching { center.reject(a.sessionId, a.approvalId) }
                                                    .onFailure { e -> scope.launch { snackbar.showSnackbar(e.message ?: "失败") } }
                                            }
                                        },
                                        enabled = !busy,
                                    ) { Text("拒绝") }
                                    Button(
                                        onClick = {
                                            if (risk == RiskLevel.HIGH) confirmDialog = true
                                            else scope.launch {
                                                runCatching { center.allow(a.sessionId, a.approvalId) }
                                                    .onFailure { e -> scope.launch { snackbar.showSnackbar(e.message ?: "失败") } }
                                            }
                                        },
                                        enabled = !busy,
                                    ) { Text("允许一次") }
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = { onOpenSession(a.sessionId, null) }) { Text("去会话") }
                                }
                            }
                        }
                        if (confirmDialog) {
                            AlertDialog(
                                onDismissRequest = { confirmDialog = false },
                                title = { Text("高危操作二次确认") },
                                text = { Text("工具「${a.toolName}」${a.reason?.let { "\n原因：$it" } ?: ""}\n允许该操作执行一次？") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        confirmDialog = false
                                        scope.launch {
                                            runCatching { center.allow(a.sessionId, a.approvalId) }
                                                .onFailure { e -> scope.launch { snackbar.showSnackbar(e.message ?: "失败") } }
                                        }
                                    }) { Text("仍要允许", color = MaterialTheme.colorScheme.error) }
                                },
                                dismissButton = { TextButton(onClick = { confirmDialog = false }) { Text("取消") } },
                            )
                        }
                    }
                }

                if (questions.isNotEmpty()) {
                    item(key = "q-h") { SectionHeader("问答（${questions.size}）") }
                    item(key = "q-b") {
                        OutlinedButton(
                            onClick = { scope.launch { runBatch("全部跳过") { center.skipAllQuestions() } } },
                            enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        ) { Text("全部跳过") }
                    }
                    items(questions, key = { "q-" + it.sessionId }) { q ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = DshShape.card) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    titles[q.sessionId] ?: q.sessionId.take(8),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Spacer(Modifier.height(8.dp))
                                QuestionCard(
                                    questions = q.questions,
                                    onSubmit = { answers ->
                                        scope.launch {
                                            runCatching { center.answerQuestions(q.sessionId, answers) }
                                                .onFailure { e -> scope.launch { snackbar.showSnackbar(e.message ?: "失败") } }
                                        }
                                    },
                                    onSkip = {
                                        scope.launch {
                                            runCatching { center.skipQuestions(q.sessionId) }
                                                .onFailure { e -> scope.launch { snackbar.showSnackbar(e.message ?: "失败") } }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                if (errors.isNotEmpty()) {
                    item(key = "e-h") { SectionHeader("异常（${errors.size}）") }
                    items(errors, key = { "e-" + it.sessionId + "-" + it.seq }) { err ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            shape = DshShape.card,
                            onClick = { onOpenSession(err.sessionId, err.seq) },
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    titles[err.sessionId] ?: err.sessionId.take(8),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    err.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}
