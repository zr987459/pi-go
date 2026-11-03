package io.github.zr987459.pigo.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import io.github.zr987459.pigo.core.ui.MonoStyle
import io.github.zr987459.pigo.feature.chat.logic.MarkdownSegments
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect

/**
 * Markdown 正文（S2：标题、列表、代码块、表格；代码块可复制）。
 * 按段渲染：流式时只有最后一段在变，前面的段落直接跳过重组。
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val segments = remember(text) { MarkdownSegments.split(text) }
    val colors = markdownColor()
    val typography = markdownTypography()
    val components = remember {
        markdownComponents(
            codeFence = { model ->
                MarkdownCodeFence(model.content, model.node) { code, _ -> CodeBlock(code) }
            },
            codeBlock = { model ->
                MarkdownCodeBlock(model.content, model.node) { code, _ -> CodeBlock(code) }
            },
        )
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEachIndexed { i, seg ->
            key(i) {
                Markdown(
                    content = seg,
                    colors = colors,
                    typography = typography,
                    components = components,
                )
            }
        }
    }
}

/** 代码块：等宽、可横向滚动、右上角「复制」。 */
@Composable
fun CodeBlock(code: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                },
                modifier = Modifier.testTag("code:copy"),
            ) { Text(if (copied) "已复制" else "复制", style = MaterialTheme.typography.labelSmall) }
        }
        Text(
            code.trimEnd('\n'),
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            softWrap = false,
        )
    }
}
