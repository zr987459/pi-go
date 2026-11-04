package io.github.zr987459.pigo.form

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.session.SessionHost
import io.github.zr987459.pigo.contract.session.UiForm

/** 切换形态的交叉淡化时长（DESIGN-r3 §4.6：120ms，只淡化渲染层，不重建状态）。 */
const val FORM_CROSSFADE_MS = 120

/**
 * 顶层形态宿主（DESIGN-r3 §5.1、§5.2）：按 [SessionHost.form] 决定画哪一层，`Crossfade(form)` 切换。
 *
 * - 两层都只是渲染层：会话、引擎、草稿、overlay 栈都在 [host] 里（AppGraph 单例），切换不重建、不发 RPC。
 * - [calm]、[term] 由装配层传入：r3/calm 合入后 [calm] = `CalmSurface(host)`；r3/term 合入前 [term] 用 [TermPlaceholder]。
 * - 这里是 `form` 的两个读者之一（另一个是形态偏好 [FormPersistence]）。
 */
@Composable
fun FormHost(
    host: SessionHost,
    calm: @Composable () -> Unit,
    term: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form by host.form.collectAsState()
    Crossfade(
        targetState = form,
        animationSpec = tween(FORM_CROSSFADE_MS),
        label = "form",
        modifier = modifier.testTag("form:host"),
    ) { f ->
        when (f) {
            UiForm.CALM -> calm()
            UiForm.TERM -> term()
        }
    }
}

/** 一键切换形态（⇄）：只改 `form`，不碰引擎、不发 RPC（硬性要求 ②）。 */
fun SessionHost.toggleForm() {
    form.value = FormSwitch.next(form.value)
}

object FormSwitch {
    fun next(f: UiForm): UiForm = when (f) {
        UiForm.CALM -> UiForm.TERM
        UiForm.TERM -> UiForm.CALM
    }
}

/**
 * 终端形态尚未合入（r3/term，PR 2 接线）时的占位。DESIGN-r3 §8.3 第 3 步：此时终端入口显示占位、不发布。
 */
@Composable
fun TermPlaceholder(onBackToUi: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize().testTag("term:placeholder"), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("终端形态尚未合入", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "会话与引擎不受影响，切回 UI 形态即可继续。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = onBackToUi, modifier = Modifier.testTag("form:toggle")) { Text("⇄ UI") }
        }
    }
}
