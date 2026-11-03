package io.github.zr987459.pigo.feature.settings.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.QueueMode
import io.github.zr987459.pigo.contract.SessionSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * M8 会话默认设置：自动压缩、自动重试、插话（steering）与排队（follow-up）的投递方式。
 * 经 W1 的 [EngineExtras]（`set_auto_compaction` / `set_auto_retry` / `set_steering_mode` / `set_follow_up_mode`）设置；
 * pi 1.5.0 收到这些命令后会写进全局 `settings.json`（`compaction.enabled`、`retry.enabled`、`steeringMode`、`followUpMode`），
 * 所以新会话、重启引擎后仍然生效。只有真实 extras 时才显示（W5 在 BindingMode.extras == REAL 时接入）。
 */
class SessionDefaultsController(private val extras: EngineExtras, private val scope: CoroutineScope) {
    data class State(
        val settings: SessionSettings = SessionSettings(),
        val loading: Boolean = true,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun load() = scope.launch {
        _state.value = when (val r = extras.sessionSettings()) {
            is Outcome.Ok -> State(r.value, loading = false)
            is Outcome.Err -> _state.value.copy(loading = false, error = "读取会话设置失败：" + r.message)
        }
    }

    fun setAutoCompaction(on: Boolean) = apply({ it.copy(autoCompaction = on) }) { extras.setAutoCompaction(on) }
    fun setAutoRetry(on: Boolean) = apply({ it.copy(autoRetry = on) }) { extras.setAutoRetry(on) }
    fun setSteeringMode(m: QueueMode) = apply({ it.copy(steeringMode = m) }) { extras.setSteeringMode(m) }
    fun setFollowUpMode(m: QueueMode) = apply({ it.copy(followUpMode = m) }) { extras.setFollowUpMode(m) }

    /** 乐观更新；失败时回滚并给出原因。 */
    private fun apply(change: (SessionSettings) -> SessionSettings, call: suspend () -> Outcome<Unit>) = scope.launch {
        val before = _state.value.settings
        _state.value = _state.value.copy(settings = change(before), error = null)
        when (val r = call()) {
            is Outcome.Ok -> Unit
            is Outcome.Err -> _state.value = _state.value.copy(settings = before, error = "保存失败：" + r.message)
        }
    }
}

@Composable
fun SessionDefaultsScreen(controller: SessionDefaultsController, modifier: Modifier = Modifier) {
    LaunchedEffect(controller) { controller.load() }
    val state by controller.state.collectAsState()
    val s = state.settings
    Column(modifier.fillMaxWidth().padding(16.dp).testTag("settings:sessionDefaults"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ToggleRow("自动压缩", "上下文快满时自动压缩旧对话", s.autoCompaction, "m8:autoCompaction", controller::setAutoCompaction)
        ToggleRow("自动重试", "模型服务出错（限流、过载）时自动重试", s.autoRetry, "m8:autoRetry", controller::setAutoRetry)
        ModeRow("生成中插话", "插话（steer）是一次全发还是逐条发", s.steeringMode, "m8:steering", controller::setSteeringMode)
        ModeRow("生成中排队", "排队消息（follow-up）是一次全发还是逐条发", s.followUpMode, "m8:followUp", controller::setFollowUpMode)
        Text("设置写入 pi 的 settings.json，新会话与重启后仍然生效。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("m8:error")) }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, value: Boolean?, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(if (value == null) "$subtitle（当前未知）" else subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // 未知（1.5.0 的 get_state 不给 autoRetry）时显示为关，但文案标「未知」；拨动后即为确定值。
        Switch(checked = value == true, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun ModeRow(title: String, subtitle: String, value: QueueMode?, tag: String, onChange: (QueueMode) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(value == QueueMode.ONE_AT_A_TIME, { onChange(QueueMode.ONE_AT_A_TIME) }, { Text("逐条") }, Modifier.testTag("$tag:one"))
            FilterChip(value == QueueMode.ALL, { onChange(QueueMode.ALL) }, { Text("一次全发") }, Modifier.testTag("$tag:all"))
        }
    }
}
