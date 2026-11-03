package io.github.zr987459.pigo.feature.files.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.FileNode
import io.github.zr987459.pigo.feature.files.browser.GitBadge
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun CenterMessage(text: String, modifier: Modifier = Modifier, loading: Boolean = false) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        if (loading) CircularProgressIndicator()
        else Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

internal object Fmt {
    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private val day = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun time(t: Instant?): String = t?.let { day.format(it.atZone(ZoneId.systemDefault())) } ?: ""

    /** 列表里的一个小图标字符（material-icons-core 没有文件夹图标）。 */
    fun glyph(n: FileNode): String = when {
        n.isDir -> "📁"
        n.isSymlink && n.mimeType == null && n.sizeBytes == 0L -> "🔗"
        n.mimeType?.startsWith("image/") == true -> "🖼"
        n.mimeType?.startsWith("audio/") == true -> "🎵"
        n.mimeType?.startsWith("video/") == true -> "🎬"
        n.mimeType == "application/pdf" -> "📕"
        n.name.endsWith(".md", true) -> "📝"
        n.name.substringAfterLast('.', "").lowercase() in CODE -> "📜"
        else -> "📄"
    }

    private val CODE = setOf("kt", "kts", "java", "js", "ts", "tsx", "jsx", "py", "go", "rs", "c", "cpp", "h", "sh", "json", "yaml", "yml", "toml", "gradle", "rb", "php", "swift", "dart", "sql", "html", "css")
}

@Composable
internal fun badgeColor(b: GitBadge): Color = when (b) {
    GitBadge.MODIFIED -> Color(0xFFC4A000)
    GitBadge.ADDED, GitBadge.UNTRACKED -> Color(0xFF2E9D5B)
    GitBadge.DELETED, GitBadge.CONFLICT -> MaterialTheme.colorScheme.error
    GitBadge.RENAMED -> Color(0xFF2472C8)
}
