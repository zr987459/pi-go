package io.github.zr987459.pigo.feature.usage.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.feature.usage.logic.TreeRow
import io.github.zr987459.pigo.feature.usage.logic.TreeUiState

/**
 * 会话树（S1）：缩进树，当前分支加粗、当前叶子标「当前」，用户消息行可以「从这里分叉」。
 * 页面外壳（顶栏、返回）由 W5 的导航提供。
 */
@Composable
fun SessionTreeContent(
    state: TreeUiState,
    onToggle: (String) -> Unit,
    onFork: (String) -> Unit,
    onClone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            val leaves = state.tree?.leaves()?.size ?: 0
            Text(if (state.tree == null) "会话树" else "会话树 · $leaves 个分支", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onClone) { Text("复制会话") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (state.loading) CircularProgressIndicator(Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.rows, key = { it.id }) { row -> TreeRowView(row, state.forking == row.id, onToggle, onFork) }
        }
    }
}

@Composable
private fun TreeRowView(row: TreeRow, forking: Boolean, onToggle: (String) -> Unit, onFork: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = row.branches > 1) { onToggle(row.id) }
            .padding(start = (16 + row.depth * 16).dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
            .testTag("tree-row-${row.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val who = when {
            row.role == "user" -> "你"
            row.role == "assistant" -> "pi"
            row.type == "compaction" -> "压缩"
            else -> row.type
        }
        Text(
            "$who：" + row.text.ifBlank { "（无文字）" } + (row.label?.let { "  [$it]" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (row.onCurrentPath) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.isCurrentLeaf) Text("当前", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        if (row.canFork) {
            if (forking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else TextButton(onClick = { onFork(row.id) }, modifier = Modifier.testTag("fork-${row.id}")) { Text("从这里分叉") }
        }
    }
}
