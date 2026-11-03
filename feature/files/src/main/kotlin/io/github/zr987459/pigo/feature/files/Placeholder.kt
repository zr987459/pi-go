package io.github.zr987459.pigo.feature.files

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** W0 占位；W3 feat/files 替换为真实页面。入口在 W5（feat/wiring）接线。 */
@Composable
internal fun FilesPlaceholder(modifier: Modifier = Modifier) {
    Text("文件（开发中）", modifier = modifier)
}
