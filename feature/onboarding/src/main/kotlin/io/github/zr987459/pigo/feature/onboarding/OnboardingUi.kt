// 安装引导组件移植自 dsh-Go（https://github.com/ccch1mneyyy/dsh-Go，AGPL-3.0）：
// app/lib/ui/onboarding/onboarding_scaffold.dart（OnboardingScaffold / OnboardingStepBar / OnboardingHeader / OnboardingFact）、
// common/progress_halo.dart（ProgressHalo）、onboarding/setup_progress.dart（SetupPercent）。
// Flutter → Compose 逐项翻译，尺寸、颜色、时长照搬。
package io.github.zr987459.pigo.feature.onboarding

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zr987459.pigo.core.ui.AppBackdrop
import io.github.zr987459.pigo.core.ui.AppIcon
import io.github.zr987459.pigo.core.ui.AppMotion
import io.github.zr987459.pigo.core.ui.AppShape
import io.github.zr987459.pigo.core.ui.DshTheme
import io.github.zr987459.pigo.core.ui.Glyph

/**
 * OnboardingScaffold：渐变背景，内容最宽 440dp 居中；顶部 56dp 放步骤条；
 * 内容 padding (24, 0, 24, 20)，可滚动且至少撑满一屏（子项可用 weight 当 Spacer）。
 */
@Composable
internal fun OnboardingScaffold(
    step: Int,
    steps: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppBackdrop(modifier) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 440.dp).fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                    OnboardingStepBar(step, steps)
                }
                // 可滚动 + 至少一屏高：Column 的 weight 在 verticalScroll 里不生效，所以用 heightIn(min) 由外层 BoxWithConstraints 撑开。
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val minH = maxHeight
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = minH)
                            .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
                        content = content,
                    )
                }
            }
        }
    }
}

/** OnboardingStepBar：120dp 宽、3dp 高的分段条，段间 6dp；当前段 480ms 填满。 */
@Composable
internal fun OnboardingStepBar(step: Int, steps: Int) {
    val scheme = MaterialTheme.colorScheme
    val track = scheme.onSurface.copy(alpha = if (DshTheme.isDark) 0.14f else 0.10f)
    Row(
        Modifier.width(120.dp).semantics { contentDescription = "第 $step 步，共 $steps 步" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (i in 1..steps) {
            val fill by animateFloatAsState(
                targetValue = if (i <= step) 1f else 0f,
                animationSpec = tween(AppMotion.Slow, easing = AppMotion.Enter),
                label = "segment$i",
            )
            Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(3.dp)).background(track)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(scheme.primary))
            }
        }
    }
}

/** OnboardingHeader：52dp 方圆主色容器里 26dp 图标 → 22dp → headlineMedium 标题 → 6dp → bodyMedium 次级色说明。 */
@Composable
internal fun OnboardingHeader(icon: Glyph, title: String, message: String? = null) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Box(Modifier.size(52.dp).clip(AppShape.Md).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
            AppIcon(icon, null, size = 26.dp, tint = scheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(22.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, color = scheme.onSurface)
        if (message != null) {
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}

/** OnboardingFact：上下 8dp，20dp 图标 + 14dp + bodyMedium 文本；[color] 同时给图标和文字（如警告色）。 */
@Composable
internal fun OnboardingFact(icon: Glyph, text: String, color: Color? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(icon, null, size = 20.dp, tint = color ?: MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color ?: MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

/**
 * ProgressHalo：背后一团随 2.4s 呼吸起伏的主色光（半径 ±6%、透明度 0.6↔1）；14% 透明的底环；
 * 进度弧从 12 点钟方向起、圆头、35%→100% 的扫掠渐变，数值变化 480ms 过渡；[value] 为 null 时是 1.6s 一圈的 90° 转弧。
 */
@Composable
internal fun ProgressHalo(
    value: Float?,
    modifier: Modifier = Modifier,
    size: Dp = 184.dp,
    strokeWidth: Dp = 4.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit = {},
) {
    val glowAlpha = if (DshTheme.isDark) 0.16f else 0.20f
    val loop = rememberInfiniteTransition(label = "halo")
    val breath by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(AppMotion.Breath, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val spin by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "spin")
    val shown by animateFloatAsState((value ?: 0f).coerceIn(0f, 1f), tween(AppMotion.Slow, easing = FastOutSlowInEasing), label = "value")
    Box(
        modifier.size(size).semantics { contentDescription = value?.let { "进度 ${(it.coerceIn(0f, 1f) * 100).toInt()}%" } ?: "正在准备" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val radius = (this.size.minDimension - stroke) / 2 - 8.dp.toPx()
            val c = center
            val glowRadius = radius * (1.18f + 0.06f * breath)
            drawCircle(
                Brush.radialGradient(
                    listOf(color.copy(alpha = glowAlpha * (0.6f + 0.4f * breath)), color.copy(alpha = 0f)),
                    center = c,
                    radius = glowRadius,
                ),
                radius = glowRadius,
                center = c,
            )
            drawCircle(color.copy(alpha = 0.14f), radius = radius, center = c, style = Stroke(stroke))
            val startDeg: Float
            val sweepDeg: Float
            if (value != null) {
                startDeg = -90f
                sweepDeg = 360f * shown
            } else {
                startDeg = -90f + 360f * spin
                sweepDeg = 90f
            }
            if (sweepDeg > 0f) {
                val frac = (sweepDeg / 360f).coerceIn(0.05f, 1f)
                rotate(startDeg, c) {
                    drawArc(
                        brush = Brush.sweepGradient(0f to color.copy(alpha = 0.35f), frac to color, center = c),
                        startAngle = 0f,
                        sweepAngle = sweepDeg,
                        useCenter = false,
                        topLeft = Offset(c.x - radius, c.y - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
        content()
    }
}

/** SetupPercent：40sp 细体数字 + titleMedium 的「%」。 */
@Composable
internal fun SetupPercent(value: Float) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            "${(value.coerceIn(0f, 1f) * 100).toInt()}",
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Light),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(2.dp))
        Text("%", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
    }
}

/** filledButtonTheme：最小高 50，14dp 圆角，15sp。 */
@Composable
internal fun OnboardingFilledButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = AppShape.Md,
        elevation = null,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp),
        modifier = modifier.heightIn(min = 50.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp)) }
}

/** outlinedButtonTheme：最小高 50，14dp 圆角，outline 40% 描边，onSurface 文字。 */
@Composable
internal fun OnboardingOutlinedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        shape = AppShape.Md,
        border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outline.copy(alpha = 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.onSurface),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp),
        modifier = modifier.heightIn(min = 50.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp)) }
}
