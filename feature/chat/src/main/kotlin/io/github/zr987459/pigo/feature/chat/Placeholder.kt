package io.github.zr987459.pigo.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.core.ui.InfoCard
import io.github.zr987459.pigo.core.ui.MonoStyle
import kotlinx.coroutines.launch

/** 聊天占位页（mvp/chat-ui 替换）。可回放 Fake 引擎的内置夹具。 */
@Composable
fun ChatPlaceholder(engine: EngineClient, modifier: Modifier = Modifier) {
    val chat by engine.chat.collectAsState()
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().padding(16.dp).testTag("page:chat"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("聊天", style = MaterialTheme.typography.headlineSmall)
        Text(
            "占位页：原生聊天界面由 mvp/chat-ui 实现。" + if (chat.running) " · 生成中" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(chat.items, key = { it.key }) { item ->
                when (item) {
                    is ChatItem.User -> InfoCard("你 · ${item.delivery}", item.text)
                    is ChatItem.Assistant -> InfoCard(
                        "pi" + if (item.streaming) " …" else "",
                        item.blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text },
                    )
                    is ChatItem.Tool -> InfoCard("工具 ${item.toolName} · ${item.status}", item.args?.toString() ?: "")
                    is ChatItem.Notice -> Text(item.text, style = MaterialTheme.typography.labelMedium)
                    is ChatItem.Raw -> Text(item.json.toString(), style = MonoStyle)
                }
            }
        }
        if (!chat.queue.isEmpty) {
            Text("待排队 ${chat.queue.followUp.size} · 待插话 ${chat.queue.steering.size}", style = MaterialTheme.typography.labelMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { engine.send("列出当前目录并读 README") } }) { Text("回放夹具") }
            OutlinedButton(onClick = { scope.launch { engine.send("再检查一遍", SendMode.FOLLOW_UP) } }) { Text("排队") }
            OutlinedButton(onClick = { scope.launch { engine.stop() } }) { Text("停止") }
        }
    }
}
