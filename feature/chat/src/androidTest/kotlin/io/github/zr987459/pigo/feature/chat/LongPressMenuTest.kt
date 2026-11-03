package io.github.zr987459.pigo.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.core.ui.PiGoTheme
import io.github.zr987459.pigo.feature.chat.logic.ChatRow
import io.github.zr987459.pigo.feature.chat.ui.LongPressMenu
import io.github.zr987459.pigo.feature.chat.ui.MessageMenu
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 审查第 1 轮 Important 2：流式回复第一帧正文为空；生成结束后（同一个 row.key）长按应弹出「复制」。
 * 需要模拟器或真机运行（CI 只编译）。
 */
@RunWith(AndroidJUnit4::class)
class LongPressMenuTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun streamedAssistantReplyCanBeCopiedAfterItFinishes() {
        val row = mutableStateOf<ChatRow>(ChatRow.Assistant("a-1", ChatItem.Assistant("a-1", emptyList(), streaming = true)))
        compose.setContent {
            PiGoTheme {
                LongPressMenu(row.value, MessageMenu(), Modifier.testTag("row")) { Text("回复") }
            }
        }
        compose.waitForIdle()
        // 流式结束：同一个 key，正文出现
        row.value = ChatRow.Assistant(
            "a-1",
            ChatItem.Assistant("a-1", listOf(ContentBlock.Text(0, "生成好的**回答**")), streaming = false, stopReason = "stop"),
        )
        compose.waitForIdle()
        compose.onNodeWithTag("row").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("menu:copy").assertIsDisplayed()
        compose.onNodeWithTag("menu:copyMarkdown").assertIsDisplayed()
    }
}
