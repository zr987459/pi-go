package io.github.zr987459.pigo.form

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.zr987459.pigo.contract.session.SessionHost
import io.github.zr987459.pigo.contract.session.UiForm

/** 切换形态的交叉淡化时长（DESIGN-r3 §4.6：120ms，只淡化渲染层，不重建状态）。 */
const val FORM_CROSSFADE_MS = 120

/**
 * 顶层形态宿主（DESIGN-r3 §5.1、§5.2）：按 [SessionHost.form] 决定画哪一层，`Crossfade(form)` 切换。
 *
 * - 两层都只是渲染层：会话、引擎、草稿、overlay 栈都在 [host] 里（AppGraph 单例），切换不重建、不发 RPC。
 * - [calm]、[term] 由装配层传入：[calm] = `CalmSurface(host)`，[term] = `TermSurface(host)`（r3/app PR 2 接线）。
 * - 这里是 `form` 的两个读者之一（另一个是形态偏好 [FormPersistence]）。
 */
@Composable
fun FormHost(
    host: SessionHost,
    calm: @Composable () -> Unit,
    term: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 两种形态都没有可关的层时按返回（PR #21 审查第 1 轮 Important 3）：退到后台（`moveTaskToBack`），不结束 Activity、不打断生成。
     * 这个 BackHandler 先注册、优先级最低：形态内部（CalmSurface 的面板 / 会话列表）启用的 BackHandler 先处理。
     */
    onBackground: (() -> Unit)? = null,
) {
    if (onBackground != null) BackHandler(onBack = onBackground)
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
