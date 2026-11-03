// 正文与代码块照 dsh-Go（https://github.com/ccch1mneyyy/dsh-Go，AGPL-3.0）app/lib/ui/chat/render/markdown_body.dart、
// code_block.dart 逐项翻译：正文 bodyLarge（16/1.65），代码井 codeWell 底 + 0.5dp 描边 + 14dp 圆角，
// 36dp 标题条（左语言名 JetBrains Mono 12sp，右「复制」），代码 16/12 内边距。见 THIRD_PARTY_NOTICES.md §8。
package io.github.zr987459.pigo.feature.chat.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import io.github.zr987459.pigo.core.ui.AppIcon
import io.github.zr987459.pigo.core.ui.AppIcons
import io.github.zr987459.pigo.core.ui.AppMotion
import io.github.zr987459.pigo.core.ui.AppShape
import io.github.zr987459.pigo.core.ui.AppType
import io.github.zr987459.pigo.core.ui.DshTheme
import io.github.zr987459.pigo.core.ui.MonoStyle
import io.github.zr987459.pigo.feature.chat.logic.MarkdownSegments
import kotlinx.coroutines.delay

/**
 * Markdown 正文（S2：标题、列表、代码块、表格；代码块可复制）。
 * 按段渲染：流式时只有最后一段在变，前面的段落直接跳过重组。
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val segments = remember(text) { MarkdownSegments.split(text) }
    val scheme = MaterialTheme.colorScheme
    val dsh = DshTheme.colors
    val type = MaterialTheme.typography
    val colors = markdownColor(
        text = scheme.onSurface,
        codeText = scheme.onSurface,
        inlineCodeText = scheme.onSurface,
        linkText = scheme.primary,
        codeBackground = dsh.codeWell,
        inlineCodeBackground = scheme.onSurface.copy(alpha = 0.06f),
        dividerColor = scheme.outlineVariant,
    )
    val body = type.bodyLarge
    val typography = markdownTypography(
        h1 = type.headlineSmall,
        h2 = type.titleLarge,
        h3 = type.titleMedium,
        h4 = type.titleSmall,
        h5 = type.titleSmall,
        h6 = type.titleSmall,
        text = body,
        code = MonoStyle,
        inlineCode = MonoStyle.copy(fontSize = 14.sp),
        quote = body.copy(color = scheme.onSurfaceVariant),
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
    )
    val components = remember {
        markdownComponents(
            codeFence = { model ->
                MarkdownCodeFence(model.content, model.node) { code, lang -> CodeBlock(code, language = lang) }
            },
            codeBlock = { model ->
                MarkdownCodeBlock(model.content, model.node) { code, lang -> CodeBlock(code, language = lang) }
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

/** 代码块（dsh-Go `CodeBlock`）：标题条（语言 + 复制）+ 等宽正文，可横向滚动。 */
@Composable
fun CodeBlock(code: String, modifier: Modifier = Modifier, language: String? = null) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val dsh = DshTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .padding(vertical = 8.dp)
            .fillMaxWidth()
            .clip(AppShape.Md)
            .background(dsh.codeWell)
            .border(0.5.dp, dsh.codeWellBorder, AppShape.Md),
    ) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).background(dsh.codeWellHeader).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                language?.takeIf { it.isNotBlank() } ?: "text",
                style = AppType.MonoLabel,
                color = muted,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier
                    .height(28.dp)
                    .clip(AppShape.Sm)
                    .clickable(role = Role.Button, onClickLabel = "复制代码") {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    }
                    .padding(horizontal = 8.dp)
                    .testTag("code:copy"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedContent(copied, transitionSpec = { fadeIn(tween(AppMotion.Quick)) togetherWith fadeOut(tween(AppMotion.Quick)) }, label = "copy") { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(if (c) AppIcons.check else AppIcons.copy, null, size = 14.dp, tint = if (c) dsh.success else muted)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (c) "已复制" else "复制",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal),
                            color = if (c) dsh.success else muted,
                        )
                    }
                }
            }
        }
        Text(
            code.trimEnd('\n'),
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            softWrap = false,
        )
    }
}
