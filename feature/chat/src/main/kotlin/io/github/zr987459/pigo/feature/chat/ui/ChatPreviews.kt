package io.github.zr987459.pigo.feature.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.NoticeKind
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.ToolStatus
import io.github.zr987459.pigo.core.ui.AppBackdrop
import io.github.zr987459.pigo.core.ui.PiGoTheme
import io.github.zr987459.pigo.core.ui.StatusTone
import io.github.zr987459.pigo.feature.chat.logic.ChatRow
import io.github.zr987459.pigo.feature.chat.logic.ComposerState
import io.github.zr987459.pigo.feature.chat.logic.PrimaryAction
import java.time.LocalTime
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val SampleModel = ModelInfo(provider = "deepseek", id = "deepseek-chat", name = "DeepSeek V3")

@Composable
private fun SampleTopBar(status: HeaderStatus) = ChatTopBar(
    title = "新会话",
    model = SampleModel,
    thinkingLevel = "high",
    contextPercent = 12.0,
    running = false,
    loadModels = { Outcome.Ok(listOf(SampleModel)) },
    onPickModel = {},
    loadThinkingLevels = { listOf("off", "low", "high") },
    onPickThinking = {},
    status = status,
    onMenuClick = {},
    onNewChat = {},
)

/** 聊天主页：空会话、引擎未运行（问候 + 「启动引擎」）。 */
@Composable
private fun ChatHomeSample(engine: EngineStatus) {
    AppBackdrop {
        ChatEmptyState(engine, {}, Modifier.fillMaxSize().padding(top = 64.dp, bottom = 80.dp), now = LocalTime.of(15, 0))
        SampleTopBar(headerStatus(engine, false))
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.BottomCenter) {
            ComposerBar(
                composer = ComposerState(),
                primary = PrimaryAction.DISABLED,
                running = false,
                onDraftChange = {},
                onSubmit = {},
                onStop = {},
                connected = engine is EngineStatus.Idle,
            )
        }
    }
}

@Preview(name = "聊天主页 · 浅色", widthDp = 390, heightDp = 780)
@Composable
private fun ChatHomeLight() = PiGoTheme(darkTheme = false) { ChatHomeSample(EngineStatus.Stopped) }

@Preview(name = "聊天主页 · 深色", widthDp = 390, heightDp = 780)
@Composable
private fun ChatHomeDark() = PiGoTheme(darkTheme = true) { ChatHomeSample(EngineStatus.Stopped) }

/** 一轮对话：用户气泡、思考行、工具行（运行中 / 失败展开）、Markdown + 代码块、提示行、生成中输入条。 */
@Composable
private fun ChatTurnSample() {
    val rows = listOf(
        ChatRow.User("u1", "列出当前目录并读 README", Delivery.SENT, localId = "l1"),
        ChatRow.Assistant(
            "a1",
            ChatItem.Assistant("a1", listOf(ContentBlock.Thinking(0, "先看看目录结构，再读 README。")), streaming = false),
            thinkingMs = mapOf(0 to 3_200L),
        ),
        ChatRow.Tool("t1", ChatItem.Tool("t1", "c1", "bash", buildJsonObject { put("command", "ls -la") }, ToolStatus.OK, durationMs = 420)),
        ChatRow.Tool(
            "t2",
            ChatItem.Tool("t2", "c2", "read", buildJsonObject { put("path", "README.md") }, ToolStatus.ERROR, result = JsonPrimitive("ENOENT: no such file")),
        ),
        ChatRow.Assistant(
            "a2",
            ChatItem.Assistant("a2", listOf(ContentBlock.Text(0, "目录里没有 README。可以这样建一个：\n\n```bash\necho '# demo' > README.md\n```")), streaming = false),
        ),
        ChatRow.Notice("n1", ChatItem.Notice("n1", NoticeKind.STOPPED, "已停止生成")),
    )
    AppBackdrop {
        Column(Modifier.fillMaxSize().padding(top = 64.dp, start = 16.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { ChatRowView(it, isLatestUser = it.key == "u1", actions = RowActions()) }
        }
        SampleTopBar(HeaderStatus(StatusTone.Busy, "生成中"))
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.BottomCenter) {
            ComposerBar(
                composer = ComposerState(draft = "再检查一遍格式"),
                primary = PrimaryAction.QUEUE,
                running = true,
                onDraftChange = {},
                onSubmit = {},
                onStop = {},
                modifier = Modifier.fillMaxWidth(),
                contextPercent = 34.0,
            )
        }
    }
}

@Preview(name = "消息 · 浅色", widthDp = 390, heightDp = 860)
@Composable
private fun ChatTurnLight() = PiGoTheme(darkTheme = false) { ChatTurnSample() }

@Preview(name = "消息 · 深色", widthDp = 390, heightDp = 860)
@Composable
private fun ChatTurnDark() = PiGoTheme(darkTheme = true) { ChatTurnSample() }
