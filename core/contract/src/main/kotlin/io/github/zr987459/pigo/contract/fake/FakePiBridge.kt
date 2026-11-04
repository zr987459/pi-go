package io.github.zr987459.pigo.contract.fake

import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.bridge.BridgeState
import io.github.zr987459.pigo.contract.bridge.BridgeStatus
import io.github.zr987459.pigo.contract.bridge.PiBridge
import io.github.zr987459.pigo.contract.bridge.TreeNavResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [PiBridge] 的 Fake：记录调用（[calls]），标签存在 [labels]；[status] 可改成 UNAVAILABLE 来测退路。
 */
class FakePiBridge(
    var sessionFile: String = "/root/.pi/agent/sessions/--root-pi-cwd--/fake.jsonl",
) : PiBridge {
    val status = MutableStateFlow(BridgeStatus(BridgeState.READY, version = 1))
    override val bridgeStatus: StateFlow<BridgeStatus> get() = status

    val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val labels: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    @Volatile var leafId: String? = null
    @Volatile var reloads = 0

    private val ready get() = status.value.state == BridgeState.READY

    override suspend fun treeNavigate(entryId: String, summarize: Boolean): Outcome<TreeNavResult> {
        calls += "tree-nav $entryId" + if (summarize) " --summarize" else ""
        if (!ready) {
            sessionFile = sessionFile.removeSuffix(".jsonl") + "-fork.jsonl"
            return Outcome.Ok(TreeNavResult(entryId, sessionFile, cancelled = false, viaFork = true))
        }
        leafId = entryId
        return Outcome.Ok(TreeNavResult(entryId, sessionFile, cancelled = false))
    }

    override suspend fun setEntryLabel(entryId: String, label: String?): Outcome<Unit> {
        calls += "label $entryId ${label.orEmpty()}".trimEnd()
        if (!ready) return Outcome.Err(ErrCode.UNKNOWN, "标签不可用：" + (status.value.reason ?: "桥接未加载"))
        if (label.isNullOrBlank()) labels.remove(entryId) else labels[entryId] = label.trim()
        return Outcome.Ok(Unit)
    }

    override suspend fun reloadResources(): Outcome<Unit> {
        calls += "reload"
        if (!ready) return Outcome.Err(ErrCode.ENGINE_START, PiBridge.RELOAD_NEEDS_RESTART)
        reloads++
        return Outcome.Ok(Unit)
    }

    override suspend fun switchSessionViaBridge(sessionPath: String): Outcome<Unit> {
        calls += "switch $sessionPath"
        if (!ready) return Outcome.Err(ErrCode.UNKNOWN, "桥接未加载")
        sessionFile = sessionPath
        leafId = null
        return Outcome.Ok(Unit)
    }
}
