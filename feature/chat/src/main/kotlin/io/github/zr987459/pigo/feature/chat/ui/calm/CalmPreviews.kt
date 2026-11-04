package io.github.zr987459.pigo.feature.chat.ui.calm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.tooling.preview.Preview
import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.NoticeKind
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.ToolStatus
import io.github.zr987459.pigo.contract.fake.FakeSessionHost
import io.github.zr987459.pigo.contract.session.DraftState
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.SessionHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 预览 / 截图用：在 FakeSessionHost 上换一份固定的聊天状态。 */
internal class PreviewHost(fake: FakeSessionHost, state: ChatState) : SessionHost by fake {
    override val chat: StateFlow<ChatState> = MutableStateFlow(state)
}

internal val SampleChat: ChatState = ChatState(
    items = listOf(
        ChatItem.User(key = "u1", text = "帮我把 auth 拆出来"),
        ChatItem.Assistant(key = "a1", blocks = listOf(ContentBlock.Thinking(0, "先读 Login.kt，再看依赖。", durationMs = 6_000)), streaming = false),
        ChatItem.Tool(key = "t1", toolCallId = "c1", toolName = "read", args = buildJsonObject { put("path", "src/auth/Login.kt") }, status = ToolStatus.OK, durationMs = 200),
        ChatItem.Tool(key = "t2", toolCallId = "c2", toolName = "edit", args = buildJsonObject { put("path", "Login.kt"); put("oldText", "a\nb"); put("newText", "a\nb\nc\nd") }, status = ToolStatus.OK, durationMs = 100),
        ChatItem.Tool(key = "t3", toolCallId = "c3", toolName = "bash", args = buildJsonObject { put("command", "./gradlew test") }, status = ToolStatus.RUNNING),
        ChatItem.Assistant(
            key = "a2",
            blocks = listOf(ContentBlock.Text(0, "我把 Auth 拆成了三个文件：\n\n```mermaid\ngraph TD\nA[Login] --> B{Validate}\nB --> C(Home)\n```")),
            streaming = true,
        ),
        ChatItem.Notice(key = "n1", kind = NoticeKind.COMPACTION, text = "已压缩 150k → 32k\n保留了最近 3 轮对话与任务目标。"),
    ),
    running = true,
    queue = QueueSnapshot(steering = emptyList(), followUp = listOf("再跑一次 lint")),
    sessionName = "重构登录流程",
    model = ModelInfo(provider = "anthropic", id = "claude-sonnet-4.5", name = "sonnet-4.5", reasoning = true),
    thinkingLevel = "high",
    contextPercent = 38.0,
    statusLine = "~/proj",
)

@Composable
private fun previewHost(state: ChatState, draft: DraftState = DraftState.EMPTY, overlay: OverlayModel? = null): SessionHost {
    val scope = rememberCoroutineScope()
    return remember {
        val f = FakeSessionHost(scope)
        f.draft.value = draft
        overlay?.let { f.overlay.push(it) }
        PreviewHost(f, state)
    }
}

@Preview(name = "UI 形态 · 浅色", widthDp = 400, heightDp = 820, showBackground = true)
@Composable
private fun CalmLightPreview() = CalmSurface(previewHost(SampleChat), dark = false)

@Preview(name = "UI 形态 · 深色", widthDp = 400, heightDp = 820, showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun CalmDarkPreview() = CalmSurface(previewHost(SampleChat), dark = true)

@Preview(name = "UI 形态 · 有草稿（引导）", widthDp = 400, heightDp = 820)
@Composable
private fun CalmSteerPreview() = CalmSurface(previewHost(SampleChat, DraftState(text = "顺便把测试也补上")), dark = false)

@Preview(name = "UI 形态 · 空会话", widthDp = 400, heightDp = 820)
@Composable
private fun CalmEmptyPreview() = CalmSurface(previewHost(ChatState(model = SampleChat.model, thinkingLevel = "medium")), dark = false)

@Preview(name = "UI 形态 · ⌘ 命令中心", widthDp = 400, heightDp = 820)
@Composable
private fun CalmCommandsPreview() = CalmSurface(previewHost(SampleChat, overlay = OverlayModel.SelectList(id = COMMANDS_OVERLAY_ID, title = "⌘ 命令中心")), dark = false)

@Preview(name = "UI 形态 · ⌘ 拼音搜索 fzs", widthDp = 400, heightDp = 820)
@Composable
private fun CalmCommandsSearchPreview() =
    CalmSurface(previewHost(SampleChat, overlay = OverlayModel.SelectList(id = COMMANDS_OVERLAY_ID, title = "⌘ 命令中心", query = "fzs")), dark = true)
