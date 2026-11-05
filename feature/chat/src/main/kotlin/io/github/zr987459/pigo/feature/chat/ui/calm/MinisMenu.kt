@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.zr987459.pigo.feature.chat.ui.calm

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.zr987459.pigo.core.ui.calm.Calm

/**
 * 弹出菜单（移植自 OpenMinis `ui/components/MinisMenu.kt`，GPL-3.0，见 THIRD_PARTY_NOTICES.md）：
 * 20dp 圆角、白 / 深灰卡片、0.5dp 细描边、从锚点角落 0.85 倍缩放 + 淡入（200ms），下方放不下自动翻到上方，
 * 贴着屏幕边留 8dp。所有菜单（⋯、长按消息、发送键、会话行、＋）共用它，保证同一种手感。
 */
@Composable
internal fun MinisMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    minWidth: Dp = 180.dp,
    alignEnd: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val c = Calm.colors
    val density = LocalDensity.current
    val offsetXPx = with(density) { offset.x.roundToPx() }
    val offsetYPx = with(density) { offset.y.roundToPx() }
    val marginPx = with(density) { MinisMenuPosition.MARGIN.roundToPx() }
    Popup(
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
        popupPositionProvider = remember(offsetXPx, offsetYPx, alignEnd, marginPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
                    MinisMenuPosition.compute(anchorBounds, windowSize, popupContentSize, alignEnd, offsetXPx, offsetYPx, marginPx, marginPx)
            }
        },
    ) {
        BoxWithConstraints {
            val maxMenuHeight = (maxHeight - MinisMenuPosition.MARGIN * 2).coerceAtLeast(0.dp)
            val visible = remember { MutableTransitionState(false).apply { targetState = true } }
            val origin = if (alignEnd) TransformOrigin(1f, 0f) else TransformOrigin(0f, 0f)
            AnimatedVisibility(
                visibleState = visible,
                enter = scaleIn(tween(200), initialScale = 0.85f, transformOrigin = origin) + fadeIn(tween(200)),
                exit = scaleOut(tween(120)) + fadeOut(tween(120)),
            ) {
                Surface(
                    modifier = modifier.width(IntrinsicSize.Max).widthIn(min = minWidth, max = 280.dp).heightIn(max = maxMenuHeight),
                    shape = RoundedCornerShape(20.dp),
                    color = if (c.isDark) Color(0xFF2C2C2E) else Color.White,
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp,
                    border = BorderStroke(0.5.dp, if (c.isDark) c.stroke else Color.Black.copy(alpha = 0.05f)),
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp), content = content)
                }
            }
        }
    }
}

/** 菜单一行：左侧 20dp 线性图标（OpenMinis MenuItemIcon，强调色），文字 16sp；[destructive] = 红色。 */
@Composable
internal fun MinisMenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    trailing: String? = null,
) {
    val c = Calm.colors
    val fg = if (destructive) c.danger else c.text
    DropdownMenuItem(
        text = { Text(label, style = Calm.type.body, color = fg) },
        onClick = onClick,
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, tint = if (destructive) c.danger else c.accent, modifier = Modifier.size(20.dp)) } },
        trailingIcon = trailing?.let { { Text(it, style = Calm.type.label, color = c.accent) } },
        contentPadding = PaddingValues(horizontal = 14.dp),
        colors = MenuDefaults.itemColors(textColor = fg),
        modifier = modifier,
    )
}

@Composable
internal fun MinisMenuDivider(modifier: Modifier = Modifier) {
    val tint = if (Calm.colors.isDark) Color.White else Color.Black
    HorizontalDivider(modifier = modifier.padding(horizontal = 14.dp, vertical = 4.dp), thickness = 1.dp, color = tint.copy(alpha = 0.12f))
}

/** 菜单定位（OpenMinis MinisMenuPosition）：下方放得下就放下方，否则翻到上方；两边都放不下时居中。 */
internal object MinisMenuPosition {
    val MARGIN: Dp = 8.dp

    fun compute(anchor: IntRect, window: IntSize, popup: IntSize, alignEnd: Boolean, offsetX: Int, offsetY: Int, marginX: Int, marginY: Int): IntOffset {
        val rawX = if (alignEnd) anchor.right - popup.width + offsetX else anchor.left + offsetX
        val x = clampOrCenter(rawX, popup.width, window.width, marginX)
        val belowY = anchor.bottom + offsetY
        val aboveY = anchor.top - popup.height - offsetY
        val fitsBelow = belowY + popup.height <= window.height - marginY
        val y = if (fitsBelow || aboveY < marginY) belowY else aboveY
        return IntOffset(x, clampOrCenter(y, popup.height, window.height, marginY))
    }

    fun clampOrCenter(value: Int, size: Int, window: Int, margin: Int): Int {
        val max = window - margin - size
        return if (max < margin) (window - size) / 2 else value.coerceIn(margin, max)
    }
}

/**
 * 对话框（OpenMinis MinisAlertDialog：16dp 圆角卡片、标题 24dp 内边距、右下角文字按钮「取消 / 确定」）。
 * 画在本页面内（遮罩 + 居中卡片），不另开窗口，返回键与测试都按页面层级处理。
 */
@Composable
internal fun MinisDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String = "取消",
    destructive: Boolean = false,
    confirmTag: String? = null,
    tag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Calm.colors
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Column(
            modifier.padding(horizontal = 32.dp).widthIn(max = 420.dp)
                .background(if (c.isDark) Color(0xFF2C2C2E) else Color.White, RoundedCornerShape(16.dp))
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
                .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        ) {
            Text(title, style = Calm.type.title, color = c.text, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp))
            Column(Modifier.padding(horizontal = 24.dp), content = content)
            androidx.compose.foundation.layout.Row(
                Modifier.padding(8.dp).align(androidx.compose.ui.Alignment.End),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
            ) {
                Text(dismissText, style = Calm.type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = c.accent,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onDismiss).padding(horizontal = 14.dp, vertical = 10.dp))
                Text(confirmText, style = Calm.type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = if (destructive) c.danger else c.accent,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onConfirm).padding(horizontal = 14.dp, vertical = 10.dp)
                        .then(if (confirmTag != null) Modifier.testTag(confirmTag) else Modifier))
            }
        }
    }
}

