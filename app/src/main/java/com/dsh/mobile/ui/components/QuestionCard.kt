package com.dsh.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dsh.mobile.data.DshConnection
import com.dsh.mobile.data.QuestionItem
import com.dsh.mobile.ui.theme.DshShape
import com.dsh.mobile.ui.theme.brandGradient

/** 问答题卡片：选项勾选/文本输入 + 提交；跳过（取消）交给调用方处理 */
@Composable
fun QuestionCard(
    questions: List<QuestionItem>,
    onSubmit: (List<DshConnection.QuestionAnswer>) -> Unit,
    onSkip: () -> Unit,
) {
    var selections by remember(questions) {
        mutableStateOf(
            questions.associate { q -> q.id to mutableListOf<String>() }.toMutableMap()
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        questions.forEach { q ->
            Column {
                Text(
                    q.header ?: q.question,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (q.options.isEmpty()) {
                    var custom by remember(q.id) { mutableStateOf("") }
                    OutlinedTextField(
                        value = custom,
                        onValueChange = {
                            custom = it
                            selections[q.id] = mutableListOf(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("输入你的回答") },
                        maxLines = 3,
                    )
                } else {
                    q.options.forEach { opt ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        ) {
                            val checked = selections[q.id]?.contains(opt.label) == true
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { on ->
                                    val cur = selections[q.id] ?: mutableListOf()
                                    if (q.multiSelect) {
                                        if (on) cur.add(opt.label) else cur.remove(opt.label)
                                    } else {
                                        cur.clear()
                                        if (on) cur.add(opt.label)
                                    }
                                    selections = selections.toMutableMap().apply { this[q.id] = cur }
                                },
                            )
                            Text(
                                opt.label,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
                Text("取消")
            }
            Button(
                onClick = {
                    val answers = questions.map { q ->
                        DshConnection.QuestionAnswer(
                            id = q.id,
                            selected = selections[q.id] ?: emptyList(),
                        )
                    }
                    onSubmit(answers)
                },
                modifier = Modifier
                    .weight(1f)
                    .background(brandGradient(), DshShape.small),
                enabled = questions.all { q -> (selections[q.id] ?: emptyList()).isNotEmpty() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("提交")
            }
        }
    }
}
