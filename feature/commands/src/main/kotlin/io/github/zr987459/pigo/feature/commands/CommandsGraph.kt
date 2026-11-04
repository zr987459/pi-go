package io.github.zr987459.pigo.feature.commands

import io.github.zr987459.pigo.contract.DraftStore
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.LaunchEnvProvider
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.SessionIndex
import io.github.zr987459.pigo.contract.fake.FakeDraftStore
import io.github.zr987459.pigo.contract.fake.FakeSessionIndex
import io.github.zr987459.pigo.feature.commands.data.FileDraftStore
import io.github.zr987459.pigo.feature.commands.data.PiSessionIndex
import io.github.zr987459.pigo.feature.commands.data.SessionMetaStore
import io.github.zr987459.pigo.feature.commands.skills.SkillTemplateManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope

/**
 * W4 的装配（feature-gap §3.2 W4、§3.3）：W5 的 `app/di/CommandsBindings.kt` 只需调用这里。
 *
 * app 模块目前没有依赖 `:feature:commands` / `:feature:usage`（build 文件归 scaffold 维护者），
 * 所以本分支不在 app 里新增 CommandsBindings.kt，依赖项写在 PR 描述里，由 W5 接线时加上。
 */
class CommandsGraph private constructor(
    val sessionIndex: SessionIndex,
    val drafts: DraftStore,
    val skills: SkillTemplateManager?,
) {
    companion object {
        /** 界面开发用：内存实现，带夹具。 */
        fun fake(): CommandsGraph = CommandsGraph(FakeSessionIndex(), FakeDraftStore(), null)

        /**
         * 真实实现。[filesDir] 为 App 私有目录（`context.filesDir`），索引缓存、置顶归档、草稿都写在 `commands/` 下，
         * 不进容器、不改 pi 文件。
         * [launchEnv] 与引擎启动用的是同一个：自动命名的 `pi --print` 要从进程环境拿 Key（DESIGN §3.10，Key 不写进容器文件）。
         */
        @Suppress("UNUSED_PARAMETER") // engine / extras / launchEnv 原供自动起名用；r3 删除自动起名后保留签名，等 r3/app 接线时收窄
        fun real(
            filesDir: File,
            scope: CoroutineScope,
            runtime: RuntimeManager,
            engine: EngineClient,
            extras: EngineExtras,
            launchEnv: LaunchEnvProvider,
        ): CommandsGraph {
            val dir = File(filesDir, "commands").apply { mkdirs() }
            val index = PiSessionIndex(
                sessionsHostDir = runtime.hostFile(SESSIONS_GUEST_DIR),
                sessionsGuestDir = SESSIONS_GUEST_DIR,
                metaStore = SessionMetaStore(File(dir, "session-meta.json")),
                cacheFile = File(dir, "session-index.json"),
            )
            return CommandsGraph(index, FileDraftStore(File(dir, "drafts.json")), SkillTemplateManager(runtime::hostFile))
        }

        const val SESSIONS_GUEST_DIR = "/root/.pi/agent/sessions"
    }
}
