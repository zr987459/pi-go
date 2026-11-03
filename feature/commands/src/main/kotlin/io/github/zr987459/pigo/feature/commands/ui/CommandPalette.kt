package io.github.zr987459.pigo.feature.commands.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.SlashCommand

/** 候选面板的一组（来源 + 标题 + 命令）。由 W5 从 ChatScreenState.slash 映射过来。 */
data class PaletteGroup(val title: String, val commands: List<SlashCommand>)

/**
 * 斜杠命令候选面板（C3），挂在输入框上方。分组显示扩展命令、技能、提示模板，每项带描述；
 * 点选即 [onPick]（ChatController.pickCommand 会把草稿补成 `/name `）。
 */
@Composable
fun CommandPalette(
    groups: List<PaletteGroup>,
    loading: Boolean,
    onPick: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth().testTag("command-palette"), tonalElevation = 3.dp, shape = MaterialTheme.shapes.medium) {
        Column {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!loading && groups.all { it.commands.isEmpty() }) {
                Text("没有匹配的命令", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                for (g in groups) {
                    if (g.commands.isEmpty()) continue
                    item(key = "h:" + g.title) {
                        Text(g.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp))
                    }
                    items(g.commands, key = { "c:" + g.title + ":" + it.name }) { c ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(c) }.padding(horizontal = 12.dp, vertical = 6.dp).testTag("cmd-${c.name}")) {
                            Text(c.invocation, style = MaterialTheme.typography.bodyMedium)
                            c.description?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
