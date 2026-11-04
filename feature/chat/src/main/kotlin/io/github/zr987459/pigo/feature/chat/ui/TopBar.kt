// 顶栏照 dsh-Go（https://github.com/ccch1mneyyy/dsh-Go，AGPL-3.0）app/lib/ui/chat/chat_header.dart 与
// model_sheet.dart（ModelChip）逐项翻译：12dp 边距、56dp 栏高、44dp 圆键与胶囊、标题 15sp/状态 11sp、
// 6dp 状态点、模型胶囊 12/8 内边距 + 14dp 星标 + 16dp ▾。见 THIRD_PARTY_NOTICES.md §8。
package io.github.zr987459.pigo.feature.chat.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.core.ui.AppIcon
import io.github.zr987459.pigo.core.ui.AppIcons
import io.github.zr987459.pigo.core.ui.AppMotion
import io.github.zr987459.pigo.core.ui.ChromeIconButton
import io.github.zr987459.pigo.core.ui.ChromePill
import io.github.zr987459.pigo.core.ui.DshTheme
import io.github.zr987459.pigo.core.ui.StatusDot
import io.github.zr987459.pigo.core.ui.StatusTone
import io.github.zr987459.pigo.feature.chat.logic.ModelMenu
import kotlinx.coroutines.launch

/** 思考级别的中文名（取值以模型实际支持为准，§3.4）。 */
fun thinkingLabel(level: String?): String = when (level) {
    null -> "默认"
    "off" -> "关闭"
    "minimal" -> "极少"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "很高"
    "max" -> "最高"
    else -> level
}

/** 顶栏会话名下面那一行：状态点的色调与文字（只由已有状态推出，不新增状态）。 */
data class HeaderStatus(val tone: StatusTone, val label: String)

fun headerStatus(engine: EngineStatus, running: Boolean): HeaderStatus = when {
    engine is EngineStatus.Crashed -> HeaderStatus(StatusTone.Error, "已退出")
    engine is EngineStatus.Starting -> HeaderStatus(StatusTone.Busy, "正在启动")
    engine is EngineStatus.Stopped -> HeaderStatus(StatusTone.Idle, "已停止")
    running || engine is EngineStatus.Running -> HeaderStatus(StatusTone.Busy, "生成中")
    else -> HeaderStatus(StatusTone.Ok, "就绪")
}

/** dsh-Go 顶栏的高度（不含状态栏）。列表顶部留白 = 状态栏 + 这个 + 8。 */
val ChatHeaderBarHeight = 56.dp
private val HeaderKey = 44.dp
private val HeaderGap = 8.dp

/**
 * 顶栏（dsh-Go `ChatHeader`）：左「≡」圆键开抽屉 · 会话名 + 状态点胶囊 · 「✦ 模型 ▾」胶囊 · 右「新建对话」圆键。
 * 四个件都是白色悬浮玻璃（Chrome 层），浮在页面渐变上。
 *
 * @param onMenuClick 打开侧边抽屉；为 null 时不显示菜单键。
 * @param onModelClick 「选择模型▾」的入口。为 null 时用内置下拉菜单（模型列表 + 思考级别，走 [loadModels] 等）；
 *   之后接上全量模型选择页时传入即可。
 * @param onNewChat 新建对话；为 null 时不显示该键。
 */
@Deprecated("r3: r3/calm 删除（DESIGN-r3 X13，改用 ui/calm/CalmSurface；待 r3/app 去掉引用后删文件）")
@Composable
fun ChatTopBar(
    title: String,
    model: ModelInfo?,
    thinkingLevel: String?,
    contextPercent: Double?,
    running: Boolean,
    loadModels: suspend () -> Outcome<List<ModelInfo>>,
    onPickModel: (ModelInfo) -> Unit,
    loadThinkingLevels: suspend () -> List<String>,
    onPickThinking: (String) -> Unit,
    modifier: Modifier = Modifier,
    status: HeaderStatus = if (running) HeaderStatus(StatusTone.Busy, "生成中") else HeaderStatus(StatusTone.Ok, "就绪"),
    onMenuClick: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    onModelClick: (() -> Unit)? = null,
    onNewChat: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp)
            .height(ChatHeaderBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onMenuClick != null) {
            ChromeIconButton(AppIcons.menu, "会话列表", onMenuClick, Modifier.testTag("chat:menu"), size = HeaderKey)
            Spacer(Modifier.width(HeaderGap))
        }
        TitlePill(title, status, onTitleClick, Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(HeaderGap))
        Spacer(Modifier.weight(0.001f))
        ModelChip(
            model = model,
            thinkingLevel = thinkingLevel,
            contextPercent = contextPercent,
            onModelClick = onModelClick,
            loadModels = loadModels,
            onPickModel = onPickModel,
            loadThinkingLevels = loadThinkingLevels,
            onPickThinking = onPickThinking,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onNewChat != null) {
            Spacer(Modifier.width(HeaderGap))
            ChromeIconButton(AppIcons.newChat, "新建对话", onNewChat, Modifier.testTag("chat:newChat"), size = HeaderKey)
        }
    }
}

@Composable
private fun TitlePill(title: String, status: HeaderStatus, onClick: (() -> Unit)?, modifier: Modifier) {
    ChromePill(onClick, modifier.height(HeaderKey), contentPadding = PaddingValues(horizontal = 16.dp)) {
        Column(Modifier.animateContentSize(tween(AppMotion.Standard)).padding(vertical = 4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = (15 * 1.3).sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp).testTag("chat:title"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status.tone, size = 6.dp)
                Spacer(Modifier.width(5.dp))
                AnimatedContent(status.label, transitionSpec = { fadeIn(tween(AppMotion.Quick)) togetherWith fadeOut(tween(AppMotion.Quick)) }, label = "status") { s ->
                    Text(
                        s,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = (11 * 1.25).sp),
                        color = if (status.tone == StatusTone.Warn) DshTheme.colors.warning else DshTheme.colors.textFaint,
                        maxLines = 1,
                        modifier = Modifier.testTag("chat:status:dot"),
                    )
                }
            }
        }
    }
}

/** dsh-Go `ModelChip`：「✦ 模型名 · 思考 ▾」。点开默认是内置的模型/思考级别下拉（[onModelClick] 为 null 时）。 */
@Composable
private fun ModelChip(
    model: ModelInfo?,
    thinkingLevel: String?,
    contextPercent: Double?,
    onModelClick: (() -> Unit)?,
    loadModels: suspend () -> Outcome<List<ModelInfo>>,
    onPickModel: (ModelInfo) -> Unit,
    loadThinkingLevels: suspend () -> List<String>,
    onPickThinking: (String) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<ModelInfo>?>(null) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var levels by remember { mutableStateOf<List<String>?>(null) }
    val label = model?.name ?: model?.id ?: "选择模型"
    val effort = thinkingLevel?.takeIf { model != null }
    Box(modifier) {
        ChromePill(
            onClick = {
                val custom = onModelClick
                if (custom != null) {
                    custom()
                } else {
                    menu = true
                    models = null
                    modelsError = null
                    levels = null
                    scope.launch {
                        when (val r = loadModels()) {
                            is Outcome.Ok -> models = r.value
                            is Outcome.Err -> modelsError = r.message
                        }
                    }
                    scope.launch { levels = loadThinkingLevels() }
                }
            },
            modifier = Modifier.height(HeaderKey).testTag("chat:model"),
            contentPadding = PaddingValues(start = 12.dp, end = 8.dp),
            onClickLabel = "选择模型",
        ) {
            AppIcon(AppIcons.model, null, size = 14.dp, tint = MaterialTheme.colorScheme.primary)
            val style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp)
            Text(
                buildAnnotatedString {
                    append(label)
                    if (effort != null) {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)) {
                            append(" · " + thinkingLabel(effort))
                        }
                    }
                },
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 130.dp).testTag("chat:thinking"),
            )
            AppIcon(AppIcons.expand, null, size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Text(
                "模型",
                style = MaterialTheme.typography.labelMedium,
                color = DshTheme.colors.textFaint,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            val list = models
            val placeholder = ModelMenu.placeholder(list, modelsError)
            when {
                placeholder != null -> DropdownMenuItem(text = { Text(placeholder) }, onClick = {}, enabled = false)
                list == null -> Unit
                else -> list.forEach { m ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(m.name ?: m.id, style = MaterialTheme.typography.bodyMedium)
                                Text(m.provider, style = MaterialTheme.typography.labelSmall, color = DshTheme.colors.textFaint)
                            }
                        },
                        trailingIcon = if (m.id == model?.id && m.provider == model.provider) {
                            { AppIcon(AppIcons.check, "当前", size = 18.dp, tint = MaterialTheme.colorScheme.primary) }
                        } else {
                            null
                        },
                        onClick = {
                            menu = false
                            onPickModel(m)
                        },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "思考强度",
                style = MaterialTheme.typography.labelMedium,
                color = DshTheme.colors.textFaint,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            val lv = levels
            when {
                lv == null -> DropdownMenuItem(text = { Text("正在读取…") }, onClick = {}, enabled = false)
                lv.isEmpty() -> DropdownMenuItem(text = { Text("当前模型不支持调整思考级别") }, onClick = {}, enabled = false)
                else -> lv.forEach { l ->
                    DropdownMenuItem(
                        text = { Text(thinkingLabel(l)) },
                        trailingIcon = if (l == thinkingLevel) {
                            { AppIcon(AppIcons.check, "当前", size = 18.dp, tint = MaterialTheme.colorScheme.primary) }
                        } else {
                            null
                        },
                        onClick = {
                            menu = false
                            onPickThinking(l)
                        },
                    )
                }
            }
            if (contextPercent != null) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "上下文已用 ${contextPercent.toInt().coerceIn(0, 100)}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = DshTheme.colors.textFaint,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("chat:context"),
                )
            }
        }
    }
}
