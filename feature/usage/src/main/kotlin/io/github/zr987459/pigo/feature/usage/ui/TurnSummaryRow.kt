package io.github.zr987459.pigo.feature.usage.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.TimelineExtra
import io.github.zr987459.pigo.feature.usage.logic.TurnSummary

/** 轮末汇总（C10）：耗时 · 速率，下面列出本轮改过的文件，点文件交给 [onOpenFile]（W3 的预览或 diff）。 */
@Composable
@Deprecated("r3: r3/app 删除（DESIGN-r3 X3），新代码不得引用")
fun TurnSummaryRow(item: TimelineExtra.TurnSummary, onOpenFile: (String) -> Unit, modifier: Modifier = Modifier) {
    val head = listOfNotNull(
        item.durationMs?.let { "用时 " + TurnSummary.formatDuration(it) },
        item.tokensPerSecond?.takeIf { it.isFinite() && it > 0 }?.let { "%.1f tok/s".format(it) },
        item.writtenFiles.size.takeIf { it > 0 }?.let { "改了 $it 个文件" },
    ).joinToString(" · ")
    if (head.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(head, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        item.writtenFiles.forEach { f ->
            Text(
                f.substringAfterLast('/'),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onOpenFile(f) }.padding(vertical = 2.dp),
            )
        }
    }
}
