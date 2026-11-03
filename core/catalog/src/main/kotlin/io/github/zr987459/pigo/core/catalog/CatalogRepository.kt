package io.github.zr987459.pigo.core.catalog

import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.Phase
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.core.store.BuiltinCatalog
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * pi 模型目录（M1）：不手写服务商表。
 *
 * 1. 启动时先用 APK 内置的 1.5.0 快照（运行环境没装好也能浏览）；若宿主有当前 pi 版本的缓存则用缓存；
 * 2. 运行环境就绪后 [refresh]：在容器里跑 `node pigo-catalog.mjs`（读 `@earendil-works/pi-ai` 的目录，不联网、不读凭据），
 *    结果按 pi 版本缓存到 [cacheDir]/catalog-<版本>.json；pi 升级后版本变了自动重新导出。
 */
class CatalogRepository(
    private val runtime: RuntimeManager,
    private val scripts: GuestScripts,
    private val cacheDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow(initial())
    val snapshot: StateFlow<BuiltinCatalog.Snapshot> = _snapshot.asStateFlow()

    private fun cacheFile(version: String) = File(cacheDir, "catalog-${version.replace(Regex("[^0-9A-Za-z._-]"), "_")}.json")

    private fun initial(): BuiltinCatalog.Snapshot {
        val v = runtime.status.value.pi
        if (v != null) {
            runCatching { BuiltinCatalog.parse(cacheFile(v).readText()) }.getOrNull()?.let { return it }
        }
        return BuiltinCatalog.bundled()
    }

    /** 重新导出（[force] 为 false 时有缓存就用缓存）。运行环境没就绪时返回内置快照。 */
    suspend fun refresh(force: Boolean = false): Outcome<BuiltinCatalog.Snapshot> = mutex.withLock {
        withContext(io) {
            val st = runtime.status.value
            if (st.phase != Phase.READY) return@withContext Outcome.Ok(_snapshot.value)
            val version = st.pi ?: "unknown"
            val cache = cacheFile(version)
            if (!force && cache.isFile) {
                runCatching { BuiltinCatalog.parse(cache.readText()) }.getOrNull()?.let {
                    _snapshot.value = it
                    return@withContext Outcome.Ok(it)
                }
            }
            try {
                scripts.ensure()
                val r = runtime.exec(listOf(GuestScripts.NODE, GuestScripts.CATALOG), timeoutMs = 60_000)
                if (r.exitCode != 0 || r.timedOut) {
                    return@withContext Outcome.Err(ErrCode.UNKNOWN, "导出模型目录失败：" + r.stderr.takeLast(300).ifBlank { "退出码 ${r.exitCode}" })
                }
                val snap = BuiltinCatalog.parse(r.stdout)
                cacheDir.mkdirs()
                cacheFile(snap.piVersion).writeText(r.stdout.lineSequence().last { it.trim().startsWith("{") })
                _snapshot.value = snap
                Outcome.Ok(snap)
            } catch (e: BuiltinCatalog.CatalogFormatException) {
                Outcome.Err(ErrCode.UNKNOWN, e.message ?: "模型目录格式不对", e)
            } catch (e: Exception) {
                Outcome.Err(ErrCode.UNKNOWN, "导出模型目录失败：${e.message}", e)
            }
        }
    }
}
