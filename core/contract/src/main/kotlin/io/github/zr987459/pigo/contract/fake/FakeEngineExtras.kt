package io.github.zr987459.pigo.contract.fake

import io.github.zr987459.pigo.contract.BashEvent
import io.github.zr987459.pigo.contract.BashResult
import io.github.zr987459.pigo.contract.CommandSource
import io.github.zr987459.pigo.contract.CompactResult
import io.github.zr987459.pigo.contract.ContextUsage
import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.contract.EntriesPage
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ExtensionSurface
import io.github.zr987459.pigo.contract.ForkPoint
import io.github.zr987459.pigo.contract.ForkResult
import io.github.zr987459.pigo.contract.ImageInput
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.QueueMode
import io.github.zr987459.pigo.contract.RunOutcome
import io.github.zr987459.pigo.contract.RunOutcomeKind
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.SessionEntry
import io.github.zr987459.pigo.contract.SessionSettings
import io.github.zr987459.pigo.contract.SessionTree
import io.github.zr987459.pigo.contract.SlashCommand
import io.github.zr987459.pigo.contract.TimelineExtra
import io.github.zr987459.pigo.contract.TokenUsage
import io.github.zr987459.pigo.contract.TreeNode
import io.github.zr987459.pigo.contract.UsageStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

/**
 * [EngineExtras] 的内存实现，W4/W5 在 W1 合入前开发用。所有调用记录在 [calls]（`"fork:e2"` 形式），便于断言。
 * 夹具：3 个斜杠命令、两条可分叉消息、一棵 3 个叶子的树、一份用量统计。
 */
class FakeEngineExtras(
    var commandList: List<SlashCommand> = FIXTURE_COMMANDS,
    var treeFixture: SessionTree = FIXTURE_TREE,
    var stats: UsageStats = FIXTURE_USAGE,
    var models: List<ModelInfo> = listOf(ModelInfo("deepseek", "deepseek-chat"), ModelInfo("deepseek", "deepseek-reasoner", reasoning = true)),
    var thinkingLevels: List<String> = listOf("off", "low", "medium", "high"),
) : EngineExtras {

    val calls = mutableListOf<String>()
    val sentImages = mutableListOf<List<ImageInput>>()
    private var settings = SessionSettings(autoCompaction = true, autoRetry = true, steeringMode = QueueMode.ONE_AT_A_TIME, followUpMode = QueueMode.ONE_AT_A_TIME)
    private var modelIndex = 0
    private var thinkingIndex = 0

    private val _timeline = MutableStateFlow<List<TimelineExtra>>(emptyList())
    override val timelineExtras: StateFlow<List<TimelineExtra>> = _timeline.asStateFlow()

    private val _outcomes = MutableSharedFlow<RunOutcome>(extraBufferCapacity = 16)
    override val runOutcomes: SharedFlow<RunOutcome> = _outcomes.asSharedFlow()

    private val _usage = MutableStateFlow<UsageStats?>(null)
    override val usageState: StateFlow<UsageStats?> = _usage.asStateFlow()

    private val _surface = MutableStateFlow(ExtensionSurface())
    override val extensionSurface: StateFlow<ExtensionSurface> = _surface.asStateFlow()

    /** 测试用：模拟一次回合结束。 */
    fun emitOutcome(kind: RunOutcomeKind, message: String? = null) {
        _outcomes.tryEmit(RunOutcome(kind, "/root/.pi/agent/sessions/fake.jsonl", "假会话", message, "完成了", System.currentTimeMillis()))
    }

    fun setSurface(surface: ExtensionSurface) { _surface.value = surface }

    override suspend fun send(text: String, images: List<ImageInput>, mode: SendMode): Outcome<Disposition> {
        calls += "send:${images.size}:$text"
        sentImages += images
        if (images.isNotEmpty()) {
            _timeline.update { it + TimelineExtra.UserImages("img-${it.size}", "user-fake-${sentImages.size}", images) }
        }
        return Outcome.Ok(if (text.startsWith("/")) Disposition.HANDLED else Disposition.STARTED)
    }

    override suspend fun commands(refresh: Boolean): Outcome<List<SlashCommand>> {
        calls += "commands:$refresh"
        return Outcome.Ok(commandList)
    }

    override suspend fun forkMessages(): Outcome<List<ForkPoint>> {
        calls += "forkMessages"
        return Outcome.Ok(listOf(ForkPoint("e1", "第一条问题"), ForkPoint("e3", "第二条问题")))
    }

    override suspend fun fork(entryId: String): Outcome<ForkResult> {
        calls += "fork:$entryId"
        val node = treeFixture.find(entryId) ?: return Outcome.Err(ErrCode.RPC_REJECTED, "没有这条消息：$entryId")
        return Outcome.Ok(ForkResult(node.entry.preview, cancelled = false))
    }

    override suspend fun clone(): Outcome<Boolean> {
        calls += "clone"
        return Outcome.Ok(false)
    }

    override suspend fun tree(): Outcome<SessionTree> {
        calls += "tree"
        return Outcome.Ok(treeFixture)
    }

    override suspend fun entries(since: String?): Outcome<EntriesPage> {
        calls += "entries:$since"
        val all = flatten(treeFixture.roots)
        val list = if (since == null) all else {
            val i = all.indexOfFirst { it.id == since }
            if (i < 0) return Outcome.Err(ErrCode.RPC_REJECTED, "since 不存在：$since")
            all.drop(i + 1)
        }
        return Outcome.Ok(EntriesPage(list, treeFixture.leafId))
    }

    override suspend fun cycleModel(): Outcome<ModelInfo?> {
        calls += "cycleModel"
        if (models.isEmpty()) return Outcome.Ok(null)
        modelIndex = (modelIndex + 1) % models.size
        return Outcome.Ok(models[modelIndex])
    }

    override suspend fun cycleThinking(): Outcome<String?> {
        calls += "cycleThinking"
        if (thinkingLevels.isEmpty()) return Outcome.Ok(null)
        thinkingIndex = (thinkingIndex + 1) % thinkingLevels.size
        return Outcome.Ok(thinkingLevels[thinkingIndex])
    }

    override suspend fun usage(): Outcome<UsageStats> {
        calls += "usage"
        _usage.value = stats
        return Outcome.Ok(stats)
    }

    override suspend fun compact(customInstructions: String?): Outcome<CompactResult> {
        calls += "compact:${customInstructions ?: ""}"
        val before = stats.context?.tokens ?: 0
        val after = before / 5
        stats = stats.copy(context = ContextUsage(null, stats.context?.contextWindow, null))
        _usage.value = stats
        _timeline.update { it + TimelineExtra.Compaction("cmp-${it.size}", null, before, after, "压缩摘要（假）", manual = true) }
        return Outcome.Ok(CompactResult("压缩摘要（假）", "e3", before, after))
    }

    override suspend fun sessionSettings(): Outcome<SessionSettings> = Outcome.Ok(settings)

    override suspend fun setAutoCompaction(enabled: Boolean): Outcome<Unit> {
        calls += "setAutoCompaction:$enabled"; settings = settings.copy(autoCompaction = enabled); return Outcome.Ok(Unit)
    }

    override suspend fun setAutoRetry(enabled: Boolean): Outcome<Unit> {
        calls += "setAutoRetry:$enabled"; settings = settings.copy(autoRetry = enabled); return Outcome.Ok(Unit)
    }

    override suspend fun setSteeringMode(mode: QueueMode): Outcome<Unit> {
        calls += "setSteeringMode:${mode.wire}"; settings = settings.copy(steeringMode = mode); return Outcome.Ok(Unit)
    }

    override suspend fun setFollowUpMode(mode: QueueMode): Outcome<Unit> {
        calls += "setFollowUpMode:${mode.wire}"; settings = settings.copy(followUpMode = mode); return Outcome.Ok(Unit)
    }

    /** 回显式假 shell：`echo xxx` 输出 xxx，`false` 退出码 1，其他输出命令本身。 */
    override fun bash(command: String, excludeFromContext: Boolean): Flow<BashEvent> = flow {
        calls += "bash:$excludeFromContext:$command"
        val out = when {
            command.startsWith("echo ") -> command.removePrefix("echo ") + "\n"
            command == "false" -> ""
            else -> "$ $command\n"
        }
        out.lines().filter { it.isNotEmpty() }.forEach { emit(BashEvent.Output(it + "\n")) }
        emit(BashEvent.Done(BashResult(out, if (command == "false") 1 else 0)))
    }

    override suspend fun abortBash(): Outcome<Unit> {
        calls += "abortBash"; return Outcome.Ok(Unit)
    }

    override suspend fun exportHtml(outputPath: String?): Outcome<String> {
        calls += "exportHtml:${outputPath ?: ""}"
        return Outcome.Ok(outputPath ?: "/tmp/pi-session-fake.html")
    }

    override suspend fun lastAssistantText(): Outcome<String?> {
        calls += "lastAssistantText"
        return Outcome.Ok("这是最后一条回复。")
    }

    companion object {
        val FIXTURE_COMMANDS = listOf(
            SlashCommand("fix-tests", "修复失败的测试", CommandSource.PROMPT, "/root/.pi/agent/prompts/fix-tests.md", "user", "top-level"),
            SlashCommand("skill:pdf", "处理 PDF", CommandSource.SKILL, "/root/.pi/agent/skills/pdf/SKILL.md", "user", "top-level"),
            SlashCommand("todos", null, CommandSource.EXTENSION),
        )

        private fun e(id: String, parent: String?, role: String, text: String) =
            SessionEntry(id = id, parentId = parent, type = "message", role = role, preview = text)

        /** e1(user) → e2(assistant) → { e3(user) → e4(assistant), e5(user) → { e6, e7 } }：3 个叶子 e4、e6、e7。 */
        val FIXTURE_TREE = SessionTree(
            roots = listOf(
                TreeNode(
                    e("e1", null, "user", "第一条问题"),
                    listOf(
                        TreeNode(
                            e("e2", "e1", "assistant", "第一条回答"),
                            listOf(
                                TreeNode(e("e3", "e2", "user", "第二条问题"), listOf(TreeNode(e("e4", "e3", "assistant", "第二条回答")))),
                                TreeNode(
                                    e("e5", "e2", "user", "换个问法"),
                                    listOf(TreeNode(e("e6", "e5", "assistant", "回答 A")), TreeNode(e("e7", "e5", "assistant", "回答 B"))),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            leafId = "e4",
        )

        val FIXTURE_USAGE = UsageStats(
            sessionFile = "/root/.pi/agent/sessions/fake.jsonl",
            sessionId = "fake",
            userMessages = 5, assistantMessages = 5, toolCalls = 12, toolResults = 12, totalMessages = 22,
            tokens = TokenUsage(input = 50_000, output = 10_000, cacheRead = 40_000, cacheWrite = 5_000, total = 105_000),
            costUsd = 0.45,
            context = ContextUsage(tokens = 60_000, contextWindow = 200_000, percent = 30.0),
        )

        private fun flatten(nodes: List<TreeNode>): List<SessionEntry> {
            val out = ArrayList<SessionEntry>()
            fun walk(n: TreeNode) { out += n.entry; n.children.forEach(::walk) }
            nodes.forEach(::walk)
            return out
        }
    }
}
