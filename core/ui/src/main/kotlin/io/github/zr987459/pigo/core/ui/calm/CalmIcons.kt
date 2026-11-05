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
    val Back: ImageVector by lazy { stroke("calm.back", "M15 6L9 12L15 18") }
    val Check: ImageVector by lazy { stroke("calm.check", "M5 12.5L10 17.5L19 7") }
    val Alert: ImageVector by lazy { stroke("calm.alert", "M12 3A9 9 0 1 1 12 21A9 9 0 1 1 12 3Z", "M12 7.5V13", "M12 16.5V16.6") }
    val Bell: ImageVector by lazy { stroke("calm.bell", "M6 16V11A6 6 0 0 1 18 11V16L19.5 17.5H4.5Z", "M10 20H14") }
    val Star: ImageVector by lazy { stroke("calm.star", "M12 4L14.4 9L20 9.6L15.8 13.4L17 19L12 16.2L7 19L8.2 13.4L4 9.6L9.6 9Z") }
    val StarFilled: ImageVector by lazy { stroke("calm.starFilled", fill = "M12 4L14.4 9L20 9.6L15.8 13.4L17 19L12 16.2L7 19L8.2 13.4L4 9.6L9.6 9Z") }

    // r5/openminis：OpenMinis 用到的 Material 图标的线性版（同一套 24dp / 2dp 圆头线）。
    val ArrowBack: ImageVector by lazy { stroke("calm.arrowBack", "M19 12H5", "M11 6L5 12L11 18") }
    val Terminal: ImageVector by lazy { stroke("calm.terminal", "M4 5H20A1 1 0 0 1 21 6V18A1 1 0 0 1 20 19H4A1 1 0 0 1 3 18V6A1 1 0 0 1 4 5Z", "M7 10L10 12.5L7 15", "M12 15H17") }
    val Brush: ImageVector by lazy { stroke("calm.brush", "M14.5 4.5L19.5 9.5L12 17L7 12Z", "M7 12L5 14C4 15 4 17 3 19C5 18 7 18 8 17L10 15") }
    val Keyboard: ImageVector by lazy { stroke("calm.keyboard", "M4 6H20A1 1 0 0 1 21 7V17A1 1 0 0 1 20 18H4A1 1 0 0 1 3 17V7A1 1 0 0 1 4 6Z", "M7 10H7.1", "M11 10H11.1", "M15 10H15.1", "M8 14H16") }
    val KeyboardHide: ImageVector by lazy { stroke("calm.keyboardHide", "M4 4H20A1 1 0 0 1 21 5V13A1 1 0 0 1 20 14H4A1 1 0 0 1 3 13V5A1 1 0 0 1 4 4Z", "M8 10H16", "M9 18L12 21L15 18") }
    val Copy: ImageVector by lazy { stroke("calm.copy", "M9 9H19V19H9Z", "M5 15V5H15") }
    val Trash: ImageVector by lazy { stroke("calm.trash", "M4 7H20", "M9 7V4H15V7", "M6 7L7 20H17L18 7") }
    val Compose: ImageVector by lazy { stroke("calm.compose", "M12 4H6A2 2 0 0 0 4 6V18A2 2 0 0 0 6 20H18A2 2 0 0 0 20 18V12", "M17.5 3.5L20.5 6.5L12 15H9V12Z") }
    val Forum: ImageVector by lazy { stroke("calm.forum", "M4 5H15V13H8L4 16Z", "M18 9H20V19L16.5 16H10V15") }
    val Share: ImageVector by lazy { stroke("calm.share", "M12 3V15", "M7 8L12 3L17 8", "M5 12V20H19V12") }
    val Pencil: ImageVector by lazy { stroke("calm.pencil", "M15 5L19 9L8 20H4V16Z") }
    val Branch: ImageVector by lazy { stroke("calm.branch", "M6 4V20", "M6 14C6 10 18 12 18 7", "M18 4V7") }
    val Info: ImageVector by lazy { stroke("calm.info", "M12 3A9 9 0 1 1 12 21A9 9 0 1 1 12 3Z", "M12 11V16.5", "M12 7.5V7.6") }
    val Folder: ImageVector by lazy { stroke("calm.folder", "M3 6H9L11 8H21V19H3Z") }
    val Sparkle: ImageVector by lazy { stroke("calm.sparkle", fill = "M12 2L14 9L21 11L14 13L12 20L10 13L3 11L10 9Z") }
    val Wrench: ImageVector by lazy { stroke("calm.wrench", "M14.5 6.5A4 4 0 0 0 19 12L21 10A6 6 0 0 1 14 3L12 5A4 4 0 0 0 14.5 6.5Z", "M13 9L4 18L6 20L15 11") }
    val Cancel: ImageVector by lazy { stroke("calm.cancel", fill = "M12 2A10 10 0 1 1 12 22A10 10 0 1 1 12 2ZM8.7 7.3L7.3 8.7L10.6 12L7.3 15.3L8.7 16.7L12 13.4L15.3 16.7L16.7 15.3L13.4 12L16.7 8.7L15.3 7.3L12 10.6Z") }
    val Lightbulb: ImageVector by lazy { stroke("calm.bulb", "M9 18H15", "M10 21H14", "M12 3A6 6 0 0 1 16 13.5C15.3 14.2 15 15 15 16H9C9 15 8.7 14.2 8 13.5A6 6 0 0 1 12 3Z") }
}
