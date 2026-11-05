package io.github.zr987459.pigo.feature.term

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.core.ui.calm.JetBrainsMono

/**
 * 终端外壳（r5：照 OpenMinis `ui/terminal/TerminalScreen.kt`，GPL-3.0，见 THIRD_PARTY_NOTICES.md）：
 * 纯黑底、52dp 顶栏（左 36dp 圆形 ✕、居中等宽标题、右绿色 🖌）、底部圆角深灰「键盘附件条」（#404040 小按钮 + 绿色等宽字，
 * 激活态 iOS 蓝）。内容区仍是 pi TUI 的 Compose 渲染（同一个 SessionHost），所以 pi 的斜杠命令、`!` bash、扩展面板、排队、Esc 中止都在。
 */
internal object TermChrome {
    val Bg = Color(0xFF000000)
    val Fg = Color(0xFFD4D4D4)
    val Green = Color(0xFF34C759)
    val AccessoryBg = Color(0xFF1F1F1F)
    val ButtonBg = Color(0xFF404040)
    val ButtonActive = Color(0xFF007AFF)
    val TopButtonBg = Color(0xFF2C2C2E)

    private val mono = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp)

    /** 附件条按钮（OpenMinis QuickCommandButton：28dp 高、6dp 圆角、左小图标 / 字形、右标签）。 */
    @Composable
    fun QuickButton(label: String, modifier: Modifier = Modifier, iconText: String? = null, active: Boolean = false, onClick: () -> Unit) {
        val fg = if (active) Color.White else Green
        Row(
            modifier.height(28.dp).clip(RoundedCornerShape(6.dp)).background(if (active) ButtonActive else ButtonBg)
                .clickable(onClick = onClick).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (iconText != null) Text(iconText, style = mono, color = fg)
            Text(label, style = mono, color = fg, maxLines = 1)
        }
    }

    /** 全部输出的纯文本（「复制全部」用）：用户行加 `> `，助手正文 / 思考、工具名与输出按顺序。 */
    fun transcriptText(items: List<ChatItem>): String = buildString {
        for (it in items) {
            when (it) {
                is ChatItem.User -> appendLine("> " + it.text)
                is ChatItem.Assistant -> it.blocks.forEach { b ->
                    when (b) {
                        is ContentBlock.Text -> appendLine(b.text)
                        is ContentBlock.Thinking -> appendLine(b.text)
                        is ContentBlock.ToolCall -> Unit
                    }
                }
                is ChatItem.Tool -> appendLine("$ ${it.toolName}")
                is ChatItem.Notice -> appendLine(it.text)
                is ChatItem.Raw -> Unit
            }
        }
    }.trimEnd()
}

/** 终端顶栏（OpenMinis TerminalTopBar）。✕ 的测试标签沿用 `form:toggle` / `persist:form.toggle`（动作表里「终端 ⇄ 聊天」的入口）。 */
@Composable
internal fun TermTopBar(title: String, subtitle: String?, onClose: () -> Unit, onClearScreen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(TermChrome.Bg).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.testTag("form:toggle")) {
            CircleButton("✕", TermChrome.Fg, "关闭终端", Modifier.testTag("persist:form.toggle"), onClose)
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 16.sp), color = TermChrome.Fg)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp), color = TermChrome.Fg.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(200.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Spacer(Modifier.weight(1f))
        CircleButton("🖌", TermChrome.Green, "清屏", Modifier.testTag("term:clear"), onClearScreen)
    }
}

@Composable
private fun CircleButton(glyph: String, tint: Color, description: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(36.dp).clip(CircleShape).background(TermChrome.TopButtonBg).clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = tint, fontSize = 15.sp)
    }
}

/**
 * 🖌 清屏（OpenMinis TerminalScreen 的 Brush → `terminalSession.clearOutput()`，PR #31 审查 I2）：只清终端回显，不碰输入行 / 共享草稿。
 * 终端形态没有自己的模拟器缓冲，回显是会话条目排版出的行：清屏时记下每条消息已排出的行数和已有的本地块，之后只显示新出现的行
 * （流式中的消息接着往下长的行照常显示）。纯视图状态：会话、草稿、阅读锚点都不改，「复制全部」仍复制完整记录。
 */
data class TermClearMark(val linesByItem: Map<String, Int> = emptyMap(), val localKeys: Set<String> = emptySet(), val cleared: Boolean = false)

object TermClear {
    fun mark(rows: List<io.github.zr987459.pigo.feature.term.format.TermRow>, localKeys: Collection<String>): TermClearMark =
        TermClearMark(rows.groupBy { it.itemKey }.mapValues { (_, rs) -> rs.maxOf { it.lineIndex } + 1 }, localKeys.toSet(), cleared = true)

    fun visible(rows: List<io.github.zr987459.pigo.feature.term.format.TermRow>, mark: TermClearMark): List<io.github.zr987459.pigo.feature.term.format.TermRow> =
        if (!mark.cleared) rows else rows.filter { it.lineIndex >= (mark.linesByItem[it.itemKey] ?: 0) }
}
