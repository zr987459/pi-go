package io.github.zr987459.pigo.core.ui.calm

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * UI 形态的线性图标（DESIGN-r3 §4.2：圆头细线，风格对齐 Material Symbols Rounded）。
 * 用 24dp 画布、2dp 线宽的路径自绘，替代 dsh 字形字体，不引入新依赖。着色交给 `Icon(tint = …)`。
 */
object CalmIcons {
    private fun stroke(name: String, vararg paths: String, fill: String? = null): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (p in paths) {
            b.addPath(
                pathData = addPathNodes(p),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        if (fill != null) b.addPath(pathData = addPathNodes(fill), fill = SolidColor(Color.Black))
        return b.build()
    }

    val Menu: ImageVector by lazy { stroke("calm.menu", "M4 7H20", "M4 12H20", "M4 17H14") }
    val Command: ImageVector by lazy {
        stroke(
            "calm.command",
            "M9 9H15V15H9Z",
            "M9 9V6.5A2.5 2.5 0 1 0 6.5 9H9",
            "M15 9V6.5A2.5 2.5 0 1 1 17.5 9H15",
            "M15 15V17.5A2.5 2.5 0 1 0 17.5 15H15",
            "M9 15V17.5A2.5 2.5 0 1 1 6.5 15H9",
        )
    }
    val More: ImageVector by lazy { stroke("calm.more", fill = "M5 10.5A1.5 1.5 0 1 1 5 13.5A1.5 1.5 0 1 1 5 10.5ZM12 10.5A1.5 1.5 0 1 1 12 13.5A1.5 1.5 0 1 1 12 10.5ZM19 10.5A1.5 1.5 0 1 1 19 13.5A1.5 1.5 0 1 1 19 10.5Z") }
    val Swap: ImageVector by lazy { stroke("calm.swap", "M5 8H19", "M15 4L19 8L15 12", "M19 16H5", "M9 12L5 16L9 20") }
    val ArrowUp: ImageVector by lazy { stroke("calm.up", "M12 19V5", "M6 11L12 5L18 11") }
    val Stop: ImageVector by lazy { stroke("calm.stop", fill = "M8 7H16A1 1 0 0 1 17 8V16A1 1 0 0 1 16 17H8A1 1 0 0 1 7 16V8A1 1 0 0 1 8 7Z") }
    val Expand: ImageVector by lazy { stroke("calm.expand", "M14 4H20V10", "M20 4L14 10", "M10 20H4V14", "M4 20L10 14") }
    val Close: ImageVector by lazy { stroke("calm.close", "M6 6L18 18", "M18 6L6 18") }
    val Plus: ImageVector by lazy { stroke("calm.plus", "M12 5V19", "M5 12H19") }
    val ChevronRight: ImageVector by lazy { stroke("calm.chevron", "M9 6L15 12L9 18") }
    val ChevronDown: ImageVector by lazy { stroke("calm.chevronDown", "M6 9L12 15L18 9") }
    val Search: ImageVector by lazy { stroke("calm.search", "M11 4A7 7 0 1 1 11 18A7 7 0 1 1 11 4Z", "M16 16L20 20") }
    val Gear: ImageVector by lazy {
        stroke("calm.gear", "M12 9A3 3 0 1 1 12 15A3 3 0 1 1 12 9Z", "M12 3V5", "M12 19V21", "M3 12H5", "M19 12H21", "M5.6 5.6L7 7", "M17 17L18.4 18.4", "M5.6 18.4L7 17", "M17 7L18.4 5.6")
    }
    val Star: ImageVector by lazy { stroke("calm.star", "M12 4L14.4 9L20 9.6L15.8 13.4L17 19L12 16.2L7 19L8.2 13.4L4 9.6L9.6 9Z") }
    val StarFilled: ImageVector by lazy { stroke("calm.starFilled", fill = "M12 4L14.4 9L20 9.6L15.8 13.4L17 19L12 16.2L7 19L8.2 13.4L4 9.6L9.6 9Z") }
}
