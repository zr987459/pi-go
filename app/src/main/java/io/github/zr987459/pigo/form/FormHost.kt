package io.github.zr987459.pigo.form

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.zr987459.pigo.contract.session.SessionHost
import io.github.zr987459.pigo.contract.session.UiForm

/**
 * 聊天页 ⇄ 终端页的转场时长（r5/openminis：照 OpenMinis 打开终端的导航推入——终端从右侧推入、聊天页向左退半屏；
 * 关闭终端反向）。只动渲染层，不重建状态（DESIGN-r3 §4.6 的约束不变）。
 */
const val FORM_CROSSFADE_MS = 280

/**
 * 顶层页面宿主（DESIGN-r3 §5.1、§5.2；r5 改为 OpenMinis 式「聊天页 / 终端页」两页导航）：按 [SessionHost.form] 决定画哪一页
 * （CALM = 聊天页，TERM = 终端页），推入 / 推出转场。
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
    AnimatedContent(
        targetState = form,
        transitionSpec = {
            val toTerm = targetState == UiForm.TERM
            val ease = FastOutSlowInEasing
            (slideInHorizontally(tween(FORM_CROSSFADE_MS, easing = ease)) { w -> if (toTerm) w else -w / 4 } + fadeIn(tween(FORM_CROSSFADE_MS / 2)))
                .togetherWith(slideOutHorizontally(tween(FORM_CROSSFADE_MS, easing = ease)) { w -> if (toTerm) -w / 4 else w } + fadeOut(tween(FORM_CROSSFADE_MS)))
                .apply { targetContentZIndex = if (toTerm) 1f else 0f }
        },
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
