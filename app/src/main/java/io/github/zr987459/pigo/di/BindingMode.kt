package io.github.zr987459.pigo.di

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * 每组依赖用 Fake 还是 Real（DESIGN.md §2.3）。
 * 本文件**只归 mvp/integration**：MVP 集成后四组全部为真实实现（PRoot 运行环境、pi RPC 引擎、
 * 文件会话库 + Keystore + models.json/settings.json）。界面开发需要回放夹具时，在本地把对应组改回 FAKE，不要提交。
 */
object BindingMode {
    val runtime: Mode = Mode.REAL
    val engine: Mode = Mode.REAL
    val chat: Mode = Mode.REAL
    val store: Mode = Mode.REAL
    /** W2 模型目录、OAuth、自定义端点、代理与费用显示（core/catalog，已在 main）。 */
    val models: Mode = Mode.REAL
    /**
     * W1 的 [io.github.zr987459.pigo.contract.EngineExtras]（图片、斜杠命令、bash、分叉、用量、压缩…）。
     * feat/engine-rpc（PR #13）已合入 main：REAL 为 [io.github.zr987459.pigo.core.engine.PiEngine] 本身（ExtrasBindings.real）；
     * 单测与界面开发仍可用 ExtrasBindings.fake()。
     */
    val extras: Mode = Mode.REAL
    /** W3 的 Workspace（文件、附件、导出），feat/files（PR #9）已合入 main。 */
    val workspace: Mode = Mode.REAL
    /** W4 会话索引、草稿、自动命名、技能与模板（feature/commands，已在 main）。 */
    val commands: Mode = Mode.REAL
    /**
     * r3 的 SessionHost（两种形态共用）。r3/session（PR #20）合入 main 前只有 `FakeSessionHost`：
     * 此时它只承载 `form`（形态切换与偏好），UI 形态仍画现有聊天页（真实引擎）。合入后切 REAL。
     */
    val session: Mode = Mode.FAKE
}

enum class Mode { FAKE, REAL }

/** 各 Bindings 工厂共用的上下文。 */
class BindingContext(
    val appContext: Context,
    val appScope: CoroutineScope,
)

internal fun notWired(group: String): Nothing =
    error("$group 的真实实现尚未接入：请在 di/${group}Bindings.kt 中实现 real()，并由 mvp/integration 切换 BindingMode。")
