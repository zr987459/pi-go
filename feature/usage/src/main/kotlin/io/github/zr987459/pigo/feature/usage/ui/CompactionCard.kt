package io.github.zr987459.pigo.feature.usage.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.TimelineExtra
import io.github.zr987459.pigo.feature.usage.logic.CompactionSummary
import io.github.zr987459.pigo.feature.usage.logic.UsageFormat

/** 时间线里的压缩卡（U3、U4）：「已压缩：150k → 32k」，点开看摘要与读过、改过的文件。 */
@Composable
fun CompactionCard(item: TimelineExtra.Compaction, modifier: Modifier = Modifier) {
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val parsed = item.summary?.let(CompactionSummary::parse)
    val read = item.readFiles.ifEmpty { parsed?.readFiles.orEmpty() }
    val modified = item.modifiedFiles.ifEmpty { parsed?.modifiedFiles.orEmpty() }
    Card(modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = parsed != null) { open = !open }) {
        Column(Modifier.padding(12.dp)) {
            val title = when {
                item.running -> "正在压缩上下文…"
                else -> (if (item.manual) "已手动压缩：" else "已自动压缩：") +
                    UsageFormat.tokens(item.tokensBefore) + " → " + UsageFormat.tokens(item.estimatedTokensAfter)
            }
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (parsed != null && !open) Text("点开查看摘要", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedVisibility(open && parsed != null) {
                Column(Modifier.padding(top = 8.dp)) {
                    Text(parsed?.body.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    if (modified.isNotEmpty()) FileList("改过的文件", modified)
                    if (read.isNotEmpty()) FileList("读过的文件", read)
                }
            }
        }
    }
}

@Composable
private fun FileList(title: String, files: List<String>) {
    Text(title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    files.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
}
