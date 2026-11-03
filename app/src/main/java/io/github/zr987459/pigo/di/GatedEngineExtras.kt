package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.BashEvent
import io.github.zr987459.pigo.contract.CompactResult
import io.github.zr987459.pigo.contract.CycleModelResult
import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.contract.EntriesPage
import io.github.zr987459.pigo.contract.ForkPoint
import io.github.zr987459.pigo.contract.ForkResult
import io.github.zr987459.pigo.contract.ImageInput
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.QueueMode
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.SessionSettings
import io.github.zr987459.pigo.contract.SessionTree
import io.github.zr987459.pigo.contract.SlashCommand
import io.github.zr987459.pigo.contract.UsageStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * 给真实 [EngineExtras] 套上 [WarmUpGate]：会按需启动引擎的命令先等预热结束；可观察状态与
 * 只作用于已运行进程的 [abortBash] 直接透传。默认方法（[cycleModelResult]）也显式转发，免得绕过闸门。
 */
class GatedEngineExtras(val inner: EngineExtras, private val gate: WarmUpGate) : EngineExtras by inner {
    override suspend fun send(text: String, images: List<ImageInput>, mode: SendMode): Outcome<Disposition> { gate.await(); return inner.send(text, images, mode) }
    override suspend fun commands(refresh: Boolean): Outcome<List<SlashCommand>> { gate.await(); return inner.commands(refresh) }
    override suspend fun forkMessages(): Outcome<List<ForkPoint>> { gate.await(); return inner.forkMessages() }
    override suspend fun fork(entryId: String): Outcome<ForkResult> { gate.await(); return inner.fork(entryId) }
    override suspend fun clone(): Outcome<Boolean> { gate.await(); return inner.clone() }
    override suspend fun tree(): Outcome<SessionTree> { gate.await(); return inner.tree() }
    override suspend fun entries(since: String?): Outcome<EntriesPage> { gate.await(); return inner.entries(since) }
    override suspend fun cycleModel(): Outcome<ModelInfo?> { gate.await(); return inner.cycleModel() }
    override suspend fun cycleModelResult(): Outcome<CycleModelResult?> { gate.await(); return inner.cycleModelResult() }
    override suspend fun cycleThinking(): Outcome<String?> { gate.await(); return inner.cycleThinking() }
    override suspend fun usage(): Outcome<UsageStats> { gate.await(); return inner.usage() }
    override suspend fun compact(customInstructions: String?): Outcome<CompactResult> { gate.await(); return inner.compact(customInstructions) }
    override suspend fun sessionSettings(): Outcome<SessionSettings> { gate.await(); return inner.sessionSettings() }
    override suspend fun setAutoCompaction(enabled: Boolean): Outcome<Unit> { gate.await(); return inner.setAutoCompaction(enabled) }
    override suspend fun setAutoRetry(enabled: Boolean): Outcome<Unit> { gate.await(); return inner.setAutoRetry(enabled) }
    override suspend fun setSteeringMode(mode: QueueMode): Outcome<Unit> { gate.await(); return inner.setSteeringMode(mode) }
    override suspend fun setFollowUpMode(mode: QueueMode): Outcome<Unit> { gate.await(); return inner.setFollowUpMode(mode) }
    override fun bash(command: String, excludeFromContext: Boolean): Flow<BashEvent> = flow { gate.await(); emitAll(inner.bash(command, excludeFromContext)) }
    override suspend fun exportHtml(outputPath: String?): Outcome<String> { gate.await(); return inner.exportHtml(outputPath) }
    override suspend fun lastAssistantText(): Outcome<String?> { gate.await(); return inner.lastAssistantText() }
}
