package io.github.zr987459.pigo.feature.sessions

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.core.ui.DshTheme

/**
 * 抽屉专用的 dsh-Go 尺寸与颜色（照抄 dsh-Go `app/lib/ui/shell/sessions_drawer.dart`、
 * `shell/drawer/session_tile.dart`、`common/capsule_chip.dart`、`theme/semantic_colors.dart` 的值）。
 * 通用颜色/形状/动效一律走 core/ui 的 [DshTheme]、`AppShape`、`AppMotion`。
 */
internal object DrawerStyle {
    val rowHeight = 44.dp
    val contentEdge = 10.dp
    val identityKey = 44.dp

    /** sessionSelectionColor：当前会话的柔和底色（primary 9%，深色 18%）。 */
    @Composable @ReadOnlyComposable
    fun selection(): Color = MaterialTheme.colorScheme.primary.copy(alpha = if (DshTheme.isDark) 0.18f else 0.09f)

    /** CapsuleChoiceChip 未选中底。 */
    @Composable @ReadOnlyComposable
    fun chipFill(): Color =
        if (DshTheme.isDark) Color.White.copy(alpha = 0.07f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)

    /** DshColors.identity[6] / onIdentity[6]（薄荷）——身份栏「本机」圆标。 */
    @Composable @ReadOnlyComposable
    fun identity(): Pair<Color, Color> =
        if (DshTheme.isDark) Color(0xFF174038) to Color(0xFFBCE5DB) else Color(0xFFD3F1E9) to Color(0xFF00483D)
}
