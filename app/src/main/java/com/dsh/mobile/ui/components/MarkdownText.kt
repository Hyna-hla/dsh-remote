package com.dsh.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.ui.theme.DshBrandSoft
import com.dsh.mobile.ui.theme.DshSurfaceHigh

/**
 * 进程级解析缓存（LRU）：LazyColumn 条目滚出组合后 remember 缓存随之销毁，
 * 滚回时同样的大段 Markdown 会重新解析。用带哈希校验的全局缓存兜住重复解析。
 */
private object MarkdownCache {
    private const val MAX_ENTRIES = 64
    private val map = object : LinkedHashMap<String, List<MdBlock>>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MdBlock>>): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(text: String): List<MdBlock>? = map[text]

    @Synchronized
    fun put(text: String, blocks: List<MdBlock>) {
        if (text.length <= 64 * 1024) map[text] = blocks
    }
}

/** 轻量 Markdown 渲染：标题 / 加粗 / 行内代码 / 代码块 / 无序列表 / 段落 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val blocks = remember(text) {
        MarkdownCache.get(text) ?: parseMarkdownBlocks(text).also { MarkdownCache.put(text, it) }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val size = when (block.level) {
                        1 -> 17.sp
                        2 -> 15.sp
                        else -> 14.sp
                    }
                    Text(
                        text = renderInline(block.text),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = size,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }

                is MdBlock.Code -> {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DshSurfaceHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = block.text,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            ),
                        )
                    }
                }

                is MdBlock.Bullet -> {
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            "•  ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = DshBrandSoft,
                        )
                        Text(
                            text = renderInline(block.text),
                            style = MaterialTheme.typography.bodyMedium,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }

                is MdBlock.Paragraph -> {
                    if (block.text.isNotBlank()) {
                        Text(
                            text = renderInline(block.text),
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                            maxLines = maxLines,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
        }
    }
}

private fun renderInline(text: String): AnnotatedString = buildAnnotatedString {
    // 支持 **加粗** 与 `行内代码`
    val bold = Regex("""\*\*(.+?)\*\*""")
    val code = Regex("""`([^`\n]+)`""")
    var rest = text
    while (rest.isNotEmpty()) {
        val boldMatch = bold.find(rest)
        val codeMatch = code.find(rest)
        val b = boldMatch?.range
        val c = codeMatch?.range
        val next = when {
            b != null && (c == null || b.first <= c.first) -> boldMatch
            c != null -> codeMatch
            else -> null
        }
        if (next == null) {
            append(rest)
            rest = ""
        } else {
            append(rest.substring(0, next.range.first))
            if (next == boldMatch) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(next.groupValues[1])
                }
            } else {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = DshSurfaceHigh,
                        color = DshBrandSoft,
                    )
                ) {
                    append(next.groupValues[1])
                }
            }
            rest = rest.substring(next.range.last + 1)
        }
    }
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

private fun parseMarkdownBlocks(text: String): List<MdBlock> {
    val lines = text.split("\n")
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trimStart().startsWith("```") -> {
                val buf = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    buf.append(lines[i]).append("\n")
                    i++
                }
                i++ // 跳过结束围栏
                blocks.add(MdBlock.Code(buf.toString().trimEnd('\n')))
            }

            line.isBlank() -> i++

            line.startsWith("#") -> {
                val match = Regex("""^(#{1,4})\s+(.*)$""").find(line)
                if (match != null) {
                    blocks.add(MdBlock.Heading(match.groupValues[1].length, match.groupValues[2].trim()))
                } else {
                    blocks.add(MdBlock.Paragraph(line))
                }
                i++
            }

            line.startsWith("- ") || line.startsWith("* ") -> {
                blocks.add(MdBlock.Bullet(line.substring(2).trim()))
                i++
            }

            else -> {
                val buf = StringBuilder(line)
                i++
                while (i < lines.size && lines[i].isNotBlank() &&
                    !lines[i].trimStart().startsWith("```") &&
                    !lines[i].startsWith("#") &&
                    !lines[i].startsWith("- ") &&
                    !lines[i].startsWith("* ")
                ) {
                    buf.append("\n").append(lines[i])
                    i++
                }
                blocks.add(MdBlock.Paragraph(buf.toString().trim()))
            }
        }
    }
    return blocks
}
