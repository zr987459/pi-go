package io.github.zr987459.pigo.feature.sessions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.zr987459.pigo.contract.SessionRepository
import io.github.zr987459.pigo.core.ui.InfoCard
import io.github.zr987459.pigo.core.ui.PlaceholderScreen

/** 会话列表占位页（mvp/sessions-settings 替换）。 */
@Composable
fun SessionsPlaceholder(repository: SessionRepository, modifier: Modifier = Modifier) {
    val sessions by repository.observe().collectAsState(initial = emptyList())
    PlaceholderScreen(
        title = "会话",
        description = "占位页：会话抽屉/列表由 mvp/sessions-settings 实现。",
        modifier = modifier,
        testTag = "page:sessions",
    ) {
        sessions.forEach { InfoCard(it.name ?: it.preview, "${it.cwd} · ${it.updatedAt}") }
    }
}
