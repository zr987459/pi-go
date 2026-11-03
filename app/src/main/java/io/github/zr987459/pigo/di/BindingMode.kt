package io.github.zr987459.pigo.di

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * 每组依赖用 Fake 还是 Real（DESIGN.md §2.3）。
 * scaffold 初版全部为 FAKE；之后本文件**只归 mvp/integration**。
 * 各工作流可在本地把自己那组切到 REAL 调试，但不要提交本文件。
 */
object BindingMode {
    val runtime: Mode = Mode.FAKE
    val engine: Mode = Mode.FAKE
    val chat: Mode = Mode.FAKE
    val store: Mode = Mode.FAKE
}

enum class Mode { FAKE, REAL }

/** 各 Bindings 工厂共用的上下文。 */
class BindingContext(
    val appContext: Context,
    val appScope: CoroutineScope,
)

internal fun notWired(group: String): Nothing =
    error("$group 的真实实现尚未接入：请在 di/${group}Bindings.kt 中实现 real()，并由 mvp/integration 切换 BindingMode。")
