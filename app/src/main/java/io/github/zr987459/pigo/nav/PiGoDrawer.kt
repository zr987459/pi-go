// 抽屉照 dsh-Go（https://github.com/ccch1mneyyy/dsh-Go，AGPL-3.0）app/lib/ui/shell/sessions_drawer.dart、
// home_shell.dart 逐项翻译：宽 min(320, 屏宽-56)，Sheet 层玻璃，右侧 28dp 圆角；
// 面板内容（品牌行 +「新对话」胶囊、搜索、筛选、分组会话、身份栏）由 feature/sessions 的 SessionsScreen 提供。
// 见 THIRD_PARTY_NOTICES.md §8。
package io.github.zr987459.pigo.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.fake.FakeSessionRepository
import io.github.zr987459.pigo.core.ui.AppIcon
import io.github.zr987459.pigo.core.ui.AppIcons
import io.github.zr987459.pigo.core.ui.AppShape
import io.github.zr987459.pigo.core.ui.DshTheme
import io.github.zr987459.pigo.core.ui.GlassSurface
import io.github.zr987459.pigo.core.ui.GlassTier
import io.github.zr987459.pigo.core.ui.Glyph
import io.github.zr987459.pigo.core.ui.PiGoTheme
import io.github.zr987459.pigo.core.ui.StatusDot
import io.github.zr987459.pigo.core.ui.StatusTone
import io.github.zr987459.pigo.feature.sessions.SessionsScreen

private val DrawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)

private fun Destination.glyph(): Glyph = when (this) {
    Destination.Chat -> AppIcons.conversations
    Destination.Sessions -> AppIcons.history
    Destination.Settings -> AppIcons.settings
    Destination.Onboarding -> AppIcons.terminal
}

/**
 * 侧边抽屉容器：玻璃面板 + 右侧 28dp 圆角，里面直接放会话面板（与 dsh-Go 一样，抽屉即会话列表）。
 *
 * @param panel 抽屉内容，通常是 [SessionsScreen]；参数是要放进面板 `footer` 的「安装与运行环境」入口行。
 */
@Composable
fun PiGoDrawer(
    current: String?,
    onDestination: (Destination) -> Unit,
    engineStatus: EngineStatus,
    modifier: Modifier = Modifier,
    panel: @Composable (footer: @Composable () -> Unit) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val width = min(320.dp, maxWidth - 56.dp)
        GlassSurface(Modifier.width(width).fillMaxHeight().testTag("drawer"), GlassTier.Sheet, DrawerShape) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                panel {
                    DrawerRow(
                        Destination.Onboarding,
                        selected = current == Destination.Onboarding.route,
                        modifier = Modifier.padding(horizontal = 10.dp),
                        trailing = { EngineDot(engineStatus) },
                    ) { onDestination(Destination.Onboarding) }
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
private fun DrawerRow(
    d: Destination,
    selected: Boolean,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (selected) {
        if (DshTheme.isDark) Color.White.copy(alpha = 0.08f) else scheme.onSurface.copy(alpha = 0.06f)
    } else {
        Color.Transparent
    }
    Row(
        modifier
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

@Composable
private fun DrawerPreviewContent() {
    val repo = FakeSessionRepository()
    Box(Modifier.fillMaxSize().background(DshTheme.colors.backdropMid)) {
        PiGoDrawer(Destination.Chat.route, {}, EngineStatus.Stopped) { footer ->
            SessionsScreen(repository = repo, onNewSession = {}, onOpenSettings = {}, footer = footer)
        }
    }
}

@Preview(name = "抽屉 · 浅色", widthDp = 380, heightDp = 700)
@Composable
private fun DrawerLightPreview() = PiGoTheme(darkTheme = false) { DrawerPreviewContent() }

@Preview(name = "抽屉 · 深色", widthDp = 380, heightDp = 700)
@Composable
private fun DrawerDarkPreview() = PiGoTheme(darkTheme = true) { DrawerPreviewContent() }
