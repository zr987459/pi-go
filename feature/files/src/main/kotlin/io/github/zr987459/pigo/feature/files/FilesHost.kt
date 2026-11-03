package io.github.zr987459.pigo.feature.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import io.github.zr987459.pigo.contract.FileNode
import io.github.zr987459.pigo.feature.files.browser.FilesController
import io.github.zr987459.pigo.feature.files.ui.DiffScreen
import io.github.zr987459.pigo.feature.files.ui.FilePreviewScreen
import io.github.zr987459.pigo.feature.files.ui.FilesBrowserScreen
import io.github.zr987459.pigo.feature.files.ui.GitStatusScreen

/** 文件页内部的页面栈（不占用 App 导航，W5 只需挂一个「文件」路由）。 */
sealed interface FilesPage {
    data object Browser : FilesPage
    data class Preview(val path: String) : FilesPage
    data object Git : FilesPage
    data class Diff(val path: String, val staged: Boolean) : FilesPage
}

/**
 * 文件功能的入口（F1–F4）。W5 在抽屉「文件」与顶栏入口挂这个 Composable。
 *
 * @param sessionCwd 当前会话 cwd；文件页从这里开始，Git 状态也按它
 * @param openPath 直接打开某个文件（轮末汇总里点文件、`@路径` 链接）
 * @param highlight 本轮被改动的文件（高亮）
 * @param onNewSessionHere 选一个目录作为新会话的 cwd（F2，接 `EngineClient.newSession(cwd)`）
 * @param onMention 把文件以 `@路径` 引用到输入框
 */
@Composable
fun FilesHost(
    deps: FilesDeps,
    sessionCwd: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    openPath: String? = null,
    highlight: Set<String> = emptySet(),
    onNewSessionHere: ((String) -> Unit)? = null,
    onMention: ((FileNode) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val startDir = openPath?.substringBeforeLast('/')?.ifEmpty { "/" }
    val controller = remember(deps, sessionCwd) { FilesController(deps.fs, scope, sessionCwd, startDir) }
    val stack = remember(sessionCwd, openPath) {
        mutableStateListOf<FilesPage>(FilesPage.Browser).apply { if (openPath != null) add(FilesPage.Preview(openPath)) }
    }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onClose() }
    val top by remember { androidx.compose.runtime.derivedStateOf { stack.last() } }
    when (val p = top) {
        FilesPage.Browser -> FilesBrowserScreen(
            controller = controller,
            onOpenFile = { stack.add(FilesPage.Preview(it.guestPath)) },
            onOpenGit = { stack.add(FilesPage.Git) },
            onClose = onClose,
            modifier = modifier,
            highlight = highlight,
            onNewSessionHere = onNewSessionHere,
            onMention = onMention,
        )
        is FilesPage.Preview -> FilePreviewScreen(
            fs = deps.fs,
            path = p.path,
            onBack = ::pop,
            modifier = modifier,
            gitCwd = sessionCwd?.takeIf { controller.state.value.git?.isRepo == true },
            onMention = onMention,
            onOpenDir = { dir -> controller.open(dir); while (stack.size > 1) stack.removeAt(stack.lastIndex) },
        )
        FilesPage.Git -> GitStatusScreen(
            fs = deps.fs,
            cwd = sessionCwd ?: FilesDeps.DEFAULT_CWD,
            onOpenDiff = { path, staged -> stack.add(FilesPage.Diff(path, staged)) },
            onBack = ::pop,
            modifier = modifier,
        )
        is FilesPage.Diff -> DiffScreen(deps.fs, sessionCwd ?: FilesDeps.DEFAULT_CWD, p.path, p.staged, onBack = ::pop, modifier = modifier)
    }
}
