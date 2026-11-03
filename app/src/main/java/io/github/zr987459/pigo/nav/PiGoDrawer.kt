// 抽屉照 dsh-Go（https://github.com/ccch1mneyyy/dsh-Go，AGPL-3.0）app/lib/ui/shell/sessions_drawer.dart、
// home_shell.dart 逐项翻译：宽 min(320, 屏宽-56)，Sheet 层玻璃，右侧 28dp 圆角；
// 顶部品牌字（20/14/10/10 内边距）+ 主色「新对话」胶囊；行高 44、左右 10、图标 20 + 间距 10、15sp 正文。
// 见 THIRD_PARTY_NOTICES.md §8。
package io.github.zr987459.pigo.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.core.ui.AppIcon
import io.github.zr987459.pigo.core.ui.AppIcons
import io.github.zr987459.pigo.core.ui.AppShape
import io.github.zr987459.pigo.core.ui.AppType
import io.github.zr987459.pigo.core.ui.DshTheme
import io.github.zr987459.pigo.core.ui.GlassSurface
import io.github.zr987459.pigo.core.ui.GlassTier
import io.github.zr987459.pigo.core.ui.Glyph
import io.github.zr987459.pigo.core.ui.PiGoTheme
import io.github.zr987459.pigo.core.ui.PrimaryPillButton
import io.github.zr987459.pigo.core.ui.SectionHeader
import io.github.zr987459.pigo.core.ui.StatusDot
import io.github.zr987459.pigo.core.ui.StatusTone

private val DrawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)

private fun Destination.glyph(): Glyph = when (this) {
    Destination.Chat -> AppIcons.chats
    Destination.Sessions -> AppIcons.history
    Destination.Settings -> AppIcons.settings
    Destination.Onboarding -> AppIcons.terminal
}

/**
 * 侧边抽屉内容。dsh-Go 的抽屉里直接列会话；pi-go 的会话列表在 `feature/sessions`（工作者 B 的范围），
 * 这里先给「全部会话」入口，会话列表嵌入抽屉列为差距（见 docs/status/ui-dsh.md）。
 */
@Composable
fun PiGoDrawer(
    current: String?,
    onDestination: (Destination) -> Unit,
    onNewChat: () -> Unit,
    engineStatus: EngineStatus,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val width = min(320.dp, maxWidth - 56.dp)
        GlassSurface(Modifier.width(width).fillMaxHeight().testTag("drawer"), GlassTier.Sheet, DrawerShape) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 14.dp, end = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("pi-go", style = AppType.Wordmark, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    PrimaryPillButton("新对话", onNewChat, Modifier.height(40.dp).testTag("drawer:newChat"), leading = AppIcons.newChat)
                }
                Column(Modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(Destination.Chat, Destination.Sessions).forEach { d ->
                        DrawerRow(d, selected = current == d.route) { onDestination(d) }
                    }
                }
                Spacer(Modifier.weight(1f))
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionHeader("系统", Modifier.padding(start = 10.dp))
                    DrawerRow(Destination.Onboarding, selected = current == Destination.Onboarding.route, trailing = { EngineDot(engineStatus) }) {
                        onDestination(Destination.Onboarding)
                    }
                    DrawerRow(Destination.Settings, selected = current == Destination.Settings.route) { onDestination(Destination.Settings) }
                }
            }
        }
    }
}

@Composable
private fun EngineDot(status: EngineStatus) {
    val tone = when (status) {
        is EngineStatus.Idle -> StatusTone.Ok
        is EngineStatus.Running, is EngineStatus.Starting -> StatusTone.Busy
        is EngineStatus.Crashed -> StatusTone.Error
        is EngineStatus.Stopped -> StatusTone.Idle
    }
    StatusDot(tone, size = 7.dp)
}

@Composable
private fun DrawerRow(d: Destination, selected: Boolean, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (selected) {
        if (DshTheme.isDark) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f) else scheme.onSurface.copy(alpha = 0.06f)
    } else {
        androidx.compose.ui.graphics.Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(AppShape.Sm)
            .background(bg)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp)
            .testTag("nav:${d.route}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(d.glyph(), null, size = 20.dp, tint = if (selected) scheme.primary else scheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(
            d.label,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) trailing()
    }
}

@Preview(name = "抽屉 · 浅色", widthDp = 380, heightDp = 700)
@Composable
private fun DrawerLightPreview() = PiGoTheme(darkTheme = false) {
    Box(Modifier.fillMaxSize().background(DshTheme.colors.backdropMid)) {
        PiGoDrawer(Destination.Chat.route, {}, {}, EngineStatus.Stopped)
    }
}

@Preview(name = "抽屉 · 深色", widthDp = 380, heightDp = 700)
@Composable
private fun DrawerDarkPreview() = PiGoTheme(darkTheme = true) {
    Box(Modifier.fillMaxSize().background(DshTheme.colors.backdropMid)) {
        PiGoDrawer(Destination.Chat.route, {}, {}, EngineStatus.Stopped)
    }
}
