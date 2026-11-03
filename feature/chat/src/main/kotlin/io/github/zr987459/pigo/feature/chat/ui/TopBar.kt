package io.github.zr987459.pigo.feature.chat.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.Outcome
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

/** S4：顶栏显示当前模型、思考级别（可切换）、上下文占用百分比。 */
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
) {
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<ModelInfo>?>(null) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var levels by remember { mutableStateOf<List<String>?>(null) }
    var modelMenu by remember { mutableStateOf(false) }
    var levelMenu by remember { mutableStateOf(false) }

    Surface(tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                title + if (running) " · 生成中" else "",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("chat:title"),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box {
                    AssistChip(
                        onClick = {
                            modelMenu = true
                            models = null
                            modelsError = null
                            scope.launch {
                                when (val r = loadModels()) {
                                    is Outcome.Ok -> models = r.value
                                    is Outcome.Err -> modelsError = r.message
                                }
                            }
                        },
                        label = {
                            Text(
                                "模型：" + (model?.name ?: model?.id ?: "未选择"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        modifier = Modifier.testTag("chat:model"),
                    )
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        val list = models
                        val placeholder = ModelMenu.placeholder(list, modelsError)
                        when {
                            placeholder != null -> DropdownMenuItem(text = { Text(placeholder) }, onClick = {}, enabled = false)
                            list == null -> Unit
                            else -> list.forEach { m ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(m.name ?: m.id)
                                            Text(m.provider, style = MaterialTheme.typography.labelSmall)
                                        }
                                    },
                                    onClick = {
                                        modelMenu = false
                                        onPickModel(m)
                                    },
                                )
                            }
                        }
                    }
                }
                Box {
                    AssistChip(
                        onClick = {
                            levelMenu = true
                            scope.launch { levels = loadThinkingLevels() }
                        },
                        label = { Text("思考：" + thinkingLabel(thinkingLevel)) },
                        modifier = Modifier.testTag("chat:thinking"),
                    )
                    DropdownMenu(expanded = levelMenu, onDismissRequest = { levelMenu = false }) {
                        val list = levels
                        when {
                            list == null -> DropdownMenuItem(text = { Text("正在读取…") }, onClick = {}, enabled = false)
                            list.isEmpty() -> DropdownMenuItem(text = { Text("当前模型不支持调整思考级别") }, onClick = {}, enabled = false)
                            else -> list.forEach { l ->
                                DropdownMenuItem(
                                    text = { Text(thinkingLabel(l) + if (l == thinkingLevel) "（当前）" else "") },
                                    onClick = {
                                        levelMenu = false
                                        onPickThinking(l)
                                    },
                                )
                            }
                        }
                    }
                }
                AssistChip(
                    onClick = {},
                    label = { Text("上下文 " + (contextPercent?.let { "${it.toInt().coerceIn(0, 100)}%" } ?: "—")) },
                    modifier = Modifier.testTag("chat:context"),
                )
            }
        }
    }
}
