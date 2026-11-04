package io.github.zr987459.pigo.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.zr987459.pigo.contract.session.SessionHost
import io.github.zr987459.pigo.feature.chat.ui.calm.CalmSurface
import io.github.zr987459.pigo.feature.chat.ui.calm.PanelEventSink
import io.github.zr987459.pigo.feature.term.PanelSourceTracker
import io.github.zr987459.pigo.feature.term.TermEnv
import io.github.zr987459.pigo.feature.term.TermSurface
import io.github.zr987459.pigo.form.FormHost
import io.github.zr987459.pigo.contract.workspace.FileMatch
import io.github.zr987459.pigo.feature.term.FileMatch as TermFileMatch

/**
 * 会话页（DESIGN-r3 §5.2）：FormHost 的两层 + 两形态共用的装配（面板事件、@ 文件搜索、＋ › 图片、返回键、退到后台）。
 * PiGoNavHost 用 AppGraph 的真实依赖调用它；仪器测试（FormSwitchStreamingTest、AppCoverageTest）用同一个函数、换成 Fake 依赖，
 * 所以测到的就是 App 里那份接线（PR #26 审查 I2、Nit 3）。
 */
@Composable
fun ChatPage(
    host: SessionHost,
    panelEvents: PanelEventSink?,
    panelSource: PanelSourceTracker?,
    fileSearch: (suspend (String) -> List<FileMatch>)?,
    cwd: String,
    home: String,
    onOpenSettings: () -> Unit,
    onAttachImage: () -> Unit,
    onBackground: () -> Unit,
) {
    FormHost(
        host,
        calm = {
            CalmSurface(
                host,
                onOpenSettings = onOpenSettings,
                panelEvents = panelEvents,
                fileSearch = fileSearch,
                onAttachImage = onAttachImage,
                backHandler = { enabled, onBack -> BackHandler(enabled, onBack) },
            )
        },
        term = {
            // 终端形态（r3/term）：同一个 SessionHost（会话、引擎、草稿、overlay 栈共用），切换只改 form。
            val env = remember(host, panelEvents, panelSource, fileSearch) {
                TermEnv(
                    fileSearch = fileSearch?.let { fs -> val f: suspend (String) -> List<TermFileMatch> = { q -> fs(q).map { TermFileMatch(it.path, it.isDir, it.completion) } }; f },
                    panelSource = panelSource,
                    onPanelEvent = panelEvents?.let { sink -> { id, k, v -> sink.onEvent(id, k, v) } },
                    onAttachImage = onAttachImage,
                )
            }
            // 返回键：与 Esc 同一条 TermPanelRouter.close（done / cancel 结果落地、清面板提示）；扩展对话框固定在顶不被关；
            // 都没有时不拦截，FormHost 退到后台（PR #26 审查 Nit 1）。
            TermSurface(host, cwd = cwd, home = home, env = env, backHandler = { enabled, onBack -> BackHandler(enabled, onBack) })
        },
        // 各层都关着时按返回：退到后台，不结束 Activity、不打断生成（两形态同理）。
        onBackground = onBackground,
    )
}
