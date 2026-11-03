package io.github.zr987459.pigo.feature.usage

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** W0 占位；W4 feat/commands 替换为真实页面。入口在 W5（feat/wiring）接线。 */
@Composable
internal fun UsagePlaceholder(modifier: Modifier = Modifier) {
    Text("用量与会话树（开发中）", modifier = modifier)
}
