package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import io.github.zr987459.pigo.contract.fake.FakePiSettings
import io.github.zr987459.pigo.contract.fake.FakeScriptedEngine
import io.github.zr987459.pigo.contract.fake.FakeSessionCatalog
import kotlinx.coroutines.CoroutineScope

/** 记录命令的 [SessionOps] 替身：写进 Fake 引擎的命令日志，类型名与 pi RPC 一致。 */
class RecordingOps(private val engine: FakeScriptedEngine) : SessionOps {
    override suspend fun abortRetry(): Outcome<Unit> { engine.record("abort_retry"); return Outcome.Ok(Unit) }
    override suspend fun dequeueAll(): Outcome<QueueSnapshot> { engine.record("dequeue"); return Outcome.Ok(QueueSnapshot.EMPTY) }
    override suspend fun stopEngine(): Outcome<Unit> { engine.record("stop_engine"); return Outcome.Ok(Unit) }
    override suspend fun setLabel(entryId: String, label: String?): Outcome<Unit> { engine.record("label", mapOf("entryId" to entryId)); return Outcome.Ok(Unit) }
    override suspend fun reload(): Outcome<Unit> { engine.record("reload"); return Outcome.Ok(Unit) }
}

fun testHost(scope: CoroutineScope, engine: FakeScriptedEngine = FakeScriptedEngine(scope)): Pair<SessionHostImpl, FakeScriptedEngine> {
    engine.start()
    val host = SessionHostImpl(
        engine = engine,
        extras = FakeEngineExtras(),
        scope = scope,
        settings = FakePiSettings(),
        sessions = FakeSessionCatalog(engine),
        actionsFactory = { h -> PiActionsImpl(h, RecordingOps(engine)) },
    )
    return host to engine
}
