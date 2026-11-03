package io.github.zr987459.pigo.feature.sessions

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SessionRepository
import io.github.zr987459.pigo.contract.SessionSummary
import io.github.zr987459.pigo.core.ui.AppBackdrop
import io.github.zr987459.pigo.core.ui.PiGoTheme
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 预览用的只读仓库（不碰文件）。 */
private class PreviewSessions(private val list: List<SessionSummary>, private val trash: List<SessionSummary> = emptyList()) : SessionRepository {
    override fun observe(): Flow<List<SessionSummary>> = flowOf(list)
    override suspend fun refresh() = Unit
    override suspend fun moveToTrash(guestPath: String): Outcome<String> = Outcome.Ok(guestPath)
    override suspend fun restore(trashPath: String): Outcome<String> = Outcome.Ok(trashPath)
    override fun observeTrash(): Flow<List<SessionSummary>> = flowOf(trash)
}

private fun sample(): List<SessionSummary> {
    val now = Instant.now()
    fun s(i: Int, name: String?, preview: String, ago: Duration) =
        SessionSummary("/root/.pi/sessions/$i.jsonl", "$i", "/root/pi-cwd", name, preview, now.minus(ago), now.minus(ago), 2048)
    return listOf(
        s(1, "修复登录页的样式问题", "", Duration.ofMinutes(3)),
        s(2, null, "帮我写一个 README，介绍项目怎么构建", Duration.ofHours(2)),
        s(3, "重构会话列表", "", Duration.ofDays(1)),
        s(4, null, "解释一下这个报错：NullPointerException", Duration.ofDays(1).plusHours(3)),
        s(5, "整理 Gradle 依赖", "", Duration.ofDays(12)),
    )
}

@Composable
private fun DrawerSample() {
    val list = sample()
    AppBackdrop {
        SessionsScreen(
            repository = PreviewSessions(list),
            currentGuestPath = list.first().guestPath,
            onOpen = {},
            onNewSession = {},
            onBeforeDeleteCurrent = { null },
            onOpenSettings = {},
        )
    }
}

@Preview(name = "会话抽屉 · 浅色", widthDp = 320, heightDp = 680)
@Composable
private fun SessionsDrawerLightPreview() = PiGoTheme(darkTheme = false) { DrawerSample() }

@Preview(name = "会话抽屉 · 深色", widthDp = 320, heightDp = 680)
@Composable
private fun SessionsDrawerDarkPreview() = PiGoTheme(darkTheme = true) { DrawerSample() }

@Preview(name = "会话抽屉 · 空 · 深色", widthDp = 320, heightDp = 480)
@Composable
private fun SessionsDrawerEmptyDarkPreview() = PiGoTheme(darkTheme = true) {
    AppBackdrop { SessionsScreen(repository = PreviewSessions(emptyList()), onNewSession = {}) }
}
