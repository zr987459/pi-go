package io.github.zr987459.pigo.feature.usage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zr987459.pigo.feature.usage.logic.DiffKind
import io.github.zr987459.pigo.feature.usage.logic.DiffLine
import io.github.zr987459.pigo.feature.usage.logic.LineDiff

/** `edit` 工具的行级 diff（C11）：增绿、删红、段头灰；超过 [maxLines] 行时截断并提示。 */
@Composable
@Deprecated("r3: r3/app 删除（DESIGN-r3 X11），新代码不得引用")
fun DiffView(lines: List<DiffLine>, modifier: Modifier = Modifier, maxLines: Int = 400) {
    val (add, del) = LineDiff.stats(lines)
    Column(modifier.fillMaxWidth()) {
        Text("+$add −$del", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            lines.take(maxLines).forEach { l -> DiffRow(l) }
        }
        if (lines.size > maxLines) Text("……还有 ${lines.size - maxLines} 行", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DiffRow(l: DiffLine) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val bg = when (l.kind) {
        DiffKind.ADDED -> if (dark) Color(0x332EA043) else Color(0x222EA043)
        DiffKind.REMOVED -> if (dark) Color(0x33F85149) else Color(0x22F85149)
        DiffKind.HUNK, DiffKind.FILE -> MaterialTheme.colorScheme.surfaceVariant
        DiffKind.CONTEXT -> Color.Transparent
    }
    val sign = when (l.kind) {
        DiffKind.ADDED -> "+"
        DiffKind.REMOVED -> "-"
        DiffKind.CONTEXT -> " "
        else -> ""
    }
    Row(Modifier.background(bg).padding(horizontal = 4.dp)) {
        val no = (l.newNo ?: l.oldNo)?.toString().orEmpty()
        Text(no, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(36.dp))
        Text(sign + l.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
