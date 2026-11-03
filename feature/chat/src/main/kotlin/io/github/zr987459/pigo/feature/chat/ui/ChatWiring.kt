package io.github.zr987459.pigo.feature.chat.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.ImageInput
import io.github.zr987459.pigo.contract.TimelineExtra
import io.github.zr987459.pigo.core.ui.AppShape
import io.github.zr987459.pigo.core.ui.GlassSurface
import io.github.zr987459.pigo.core.ui.GlassTier
import io.github.zr987459.pigo.feature.chat.logic.ChatRow

/**
 * 时间线里的一项：一条消息行，或挂在某条消息后面的附加项（W1 的 [TimelineExtra]：压缩卡、bash 卡、用户图片、轮末汇总）。
 * 附加项按 [TimelineExtra.anchorKey] 插在对应 [ChatRow.key] 之后；锚点找不到（或为 null）的放在末尾。
 */
sealed interface TimelineEntry {
    val key: String

    data class Row(val row: ChatRow) : TimelineEntry {
        override val key: String get() = row.key
    }

    data class Extra(val extra: TimelineExtra) : TimelineEntry {
        override val key: String get() = "extra:" + extra.key
    }
}

object TimelineMerge {
    /** 纯函数：把附加项按锚点插进消息行（保持各自原有顺序）。 */
    fun merge(rows: List<ChatRow>, extras: List<TimelineExtra>): List<TimelineEntry> {
        if (extras.isEmpty()) return rows.map { TimelineEntry.Row(it) }
        val keys = rows.mapTo(HashSet()) { it.key }
        val byAnchor = extras.filter { it.anchorKey != null && it.anchorKey in keys }.groupBy { it.anchorKey!! }
        val tail = extras.filter { it.anchorKey == null || it.anchorKey !in keys }
        val out = ArrayList<TimelineEntry>(rows.size + extras.size)
        for (r in rows) {
            out += TimelineEntry.Row(r)
            byAnchor[r.key]?.forEach { out += TimelineEntry.Extra(it) }
        }
        tail.forEach { out += TimelineEntry.Extra(it) }
        return out
    }

    /** 一行的纯文本（「复制」）。 */
    fun plainText(row: ChatRow): String? = when (row) {
        is ChatRow.User -> row.text
        is ChatRow.Assistant -> row.item.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n\n") { it.text }.ifBlank { null }
        else -> null
    }
}

/**
 * 消息长按菜单（W5）：复制、复制 Markdown、从这里分叉。
 * @param entryIdOf 时间线 key → pi entryId（W1 `EngineExtras.messageMeta`）；查不到时不显示「从这里分叉」。
 * @param onFork 分叉（[io.github.zr987459.pigo.feature.chat.ChatController.forkFrom]）。
 */
class MessageMenu(
    val entryIdOf: (String) -> String? = { null },
    val onFork: ((entryId: String) -> Unit)? = null,
    val onCopied: (String) -> Unit = {},
)

@Composable
internal fun LongPressMenu(row: ChatRow, menu: MessageMenu?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (menu == null) {
        Box(modifier) { content() }
        return
    }
    val clipboard = LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }
    val text = TimelineMerge.plainText(row)
    val confirmedUser = row is ChatRow.User && row.localId == null && row.delivery != Delivery.FAILED
    val entryId = if (confirmedUser && menu.onFork != null) menu.entryIdOf(row.key) else null
    // 手势协程只随 key 重启：流式回复第一帧 text 为 null，必须读最新值，否则生成完也长按不出菜单（审查第 1 轮 Important 2）。
    val latestText by rememberUpdatedState(text)
    val latestEntryId by rememberUpdatedState(entryId)
    Box(modifier.pointerInput(row.key) { detectTapGestures(onLongPress = { if (latestText != null || latestEntryId != null) open = true }) }) {
        content()
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.testTag("chat:messageMenu")) {
            if (text != null) {
                DropdownMenuItem(text = { Text("复制") }, modifier = Modifier.testTag("menu:copy"), onClick = {
                    open = false
                    clipboard.setText(AnnotatedString(MarkdownPlain.strip(text)))
                    menu.onCopied("已复制")
                })
                DropdownMenuItem(text = { Text("复制 Markdown") }, modifier = Modifier.testTag("menu:copyMarkdown"), onClick = {
                    open = false
                    clipboard.setText(AnnotatedString(text))
                    menu.onCopied("已复制 Markdown")
                })
            }
            if (entryId != null) {
                DropdownMenuItem(text = { Text("从这里分叉") }, modifier = Modifier.testTag("menu:fork"), onClick = {
                    open = false
                    menu.onFork?.invoke(entryId)
                })
            }
        }
    }
}

/** 「复制」给纯文本：去掉最常见的 Markdown 记号（标题、强调、行内代码、代码围栏、链接）。 */
object MarkdownPlain {
    private val fence = Regex("^```.*$", RegexOption.MULTILINE)
    private val heading = Regex("^#{1,6}\\s+", RegexOption.MULTILINE)
    private val emphasis = Regex("(\\*\\*|__|\\*|_)(\\S(?:.*?\\S)?)\\1")
    private val code = Regex("`([^`]+)`")
    private val link = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")

    fun strip(md: String): String = md
        .replace(fence, "")
        .replace(heading, "")
        .replace(link, "$1")
        .replace(code, "$1")
        .replace(emphasis, "$2")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/** 待发送的图片（C1）：输入条上方的胶囊，可清空。缩略图在发送后由时间线的 UserImages 附加项显示。 */
@Composable
internal fun AttachmentStrip(images: List<ImageInput>, onClear: () -> Unit) {
    if (images.isEmpty()) return
    GlassSurface(Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("chat:attachments"), GlassTier.Chrome, AppShape.Lg) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已附 ${images.size} 张图片：" + images.joinToString("、") { it.displayName ?: "图片" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onClear) { Text("清空") }
        }
    }
}
