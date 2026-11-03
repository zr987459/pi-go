package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.ImageInput
import io.github.zr987459.pigo.contract.TimelineExtra
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.NoticeKind
import io.github.zr987459.pigo.contract.PiState
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.RetryBanner
import io.github.zr987459.pigo.contract.ToolStatus
import io.github.zr987459.pigo.contract.rpc.AssistantMessageEvent
import io.github.zr987459.pigo.contract.rpc.BashResultRecord
import io.github.zr987459.pigo.contract.rpc.CompactionResultRecord
import io.github.zr987459.pigo.contract.rpc.EngineEvent
import io.github.zr987459.pigo.contract.rpc.EngineEvents
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 把 pi 的事件流归约成界面状态 [ChatState]（DESIGN.md §3.5、§3.8）。
 *
 * 规则要点：
 * - `running` 只由 `agent_start` 置真、`agent_settled` 置假；`agent_end` 不结束本轮。
 * - `message_update` 只带 delta：按 `contentIndex` 拼接；`*_end` 用权威内容覆盖；`message_end` 整体替换。
 * - `queue_update` 每次都是完整队列，整体替换；客户端不自建队列。
 * - 未知事件、未知 role、字段缺失都不抛异常：未知事件忽略，未知 role 渲染为 [ChatItem.Raw]。
 * - 列表项 key 稳定：工具卡 `tool-<toolCallId>`，其余为单调递增序号；乐观气泡沿用调用方给的 key。
 *
 * 归约器带少量内部簿记（序号、正在流式的助手条目、未确认的乐观气泡、思考计时），
 * 因此一个实例只服务一条事件流，且必须在单个协程里串行调用。
 */
class ChatReducer {
    private var seq = 0
    private var streamingKey: String? = null
    /** 发出后尚未被 pi 的 `message_start(user)` 确认的乐观气泡，按发送顺序。 */
    private val unconfirmed = LinkedHashSet<String>()
    /** 被扩展命令消费（`disposition:"handled"`）的用户气泡：标「已发送」，但不会写入会话文件。 */
    private val notPersisted = HashSet<String>()

    /** 尚未写入会话文件的用户气泡（乐观气泡、扩展消费掉的输入）：[MessageMetaTracker] 配对时跳过。返回副本。 */
    val unpersistedKeys: Set<String> get() = HashSet(notPersisted).apply { addAll(unconfirmed) }
    /** (助手条目 key, contentIndex) → 思考块开始时间。 */
    private val thinkingStartedAt = HashMap<Pair<String, Int>, Long>()

    /** 用户气泡 key → 随消息发送 / 历史里带的图片（C1）。ChatItem.User 本身只有文本，图片以 [TimelineExtra.UserImages] 输出。 */
    private val images = LinkedHashMap<String, List<ImageInput>>()

    /** 当前所有带图片的用户气泡（key → 图片）。只在归约协程里读写；返回副本。 */
    val userImages: Map<String, List<ImageInput>> get() = LinkedHashMap(images)

    /**
     * 时间线附加项（压缩卡、bash 卡、轮末汇总），按产生顺序。W0 契约不许新增 [ChatItem] 子类，这些卡片经
     * `EngineExtras.timelineExtras` 输出，用 [TimelineExtra.anchorKey] 挂在某条 [ChatItem] 之后。
     * key 用独立序号（`x-…`），不占用 [ChatItem] 的序号，原有时间线的 key 与快照不变。
     */
    private val extras = ArrayList<TimelineExtra>()
    private var extraSeq = 0

    /** 当前全部附加项：用户图片在前（按气泡顺序），其余按产生顺序。只在归约协程里调用；返回新列表。 */
    val timelineExtras: List<TimelineExtra>
        get() = images.map { (k, v) -> TimelineExtra.UserImages("img-$k", k, v) } + extras

    /** 本轮计时（C10），与归约同序喂入。 */
    val turnClock = TurnClock()

    // ------------------------------------------------------------------ 事件

    /** 最近一次事件的时间（中断时结束计时用）。 */
    private var lastNow = 0L

    fun reduce(state: ChatState, event: EngineEvent, nowMillis: Long): ChatState {
        lastNow = nowMillis
        turnClock.onEvent(event, nowMillis)
        return reduceEvent(state, event, nowMillis)
    }

    private fun reduceEvent(state: ChatState, event: EngineEvent, nowMillis: Long): ChatState = when (event) {
        is EngineEvent.AgentStart -> state.copy(running = true)
        is EngineEvent.AgentEnd -> state
        is EngineEvent.AgentSettled -> onSettled(state, event.aborted).also { if (!event.aborted) addTurnSummary(it, nowMillis) }
        is EngineEvent.TurnStart, is EngineEvent.TurnEnd -> state
        is EngineEvent.MessageStart -> onMessageStart(state, event.message)
        is EngineEvent.MessageUpdate -> onUpdate(state, event.event, nowMillis)
        is EngineEvent.MessageEnd -> onMessageEnd(state, event.message)
        is EngineEvent.ToolExecutionStart -> onToolStart(state, event, nowMillis)
        is EngineEvent.ToolExecutionUpdate -> state.updateTool(event.toolCallId) {
            // 内置 bash/read/edit/write 的 partialResult 是累计快照，其他工具也按「替换」显示最后一次。
            it.copy(partialResult = event.partialResult, status = if (it.status == ToolStatus.PENDING) ToolStatus.RUNNING else it.status)
        }
        is EngineEvent.ToolExecutionEnd -> onToolEnd(state, event, nowMillis)
        is EngineEvent.QueueUpdate -> state.copy(queue = QueueSnapshot(event.steering, event.followUp))
        is EngineEvent.AutoRetryStart -> onRetryStart(state, event)
        is EngineEvent.AutoRetryEnd -> {
            val s = state.copy(retry = null)
            if (event.success) s else s.withNotice(NoticeKind.ERROR, "重试 ${event.attempt} 次仍失败：" + ProviderErrors.hint(event.finalError).text)
        }
        is EngineEvent.CompactionStart -> onCompactionStart(state, event)
        is EngineEvent.CompactionEnd -> onCompactionEnd(state, event)
        is EngineEvent.SessionInfoChanged -> state.copy(sessionName = event.name)
        is EngineEvent.ThinkingLevelChanged -> state.copy(thinkingLevel = event.level)
        is EngineEvent.ExtensionError -> state.withNotice(
            NoticeKind.INFO,
            "扩展出错" + (event.extensionPath?.let { "（${it.substringAfterLast('/')}）" } ?: "") + "：" + (event.error ?: "未知错误"),
        )
        is EngineEvent.Unknown -> state
        // ---- pi 1.5.0 其余事件（feat/engine-rpc） ----
        is EngineEvents.BashExecutionUpdate -> onBashDelta(state, event.id, event.delta)
        is EngineEvents.EntryAppended -> state
        is EngineEvents.SummarizationRetryScheduled -> state.copy(
            statusLine = "生成摘要失败，${(event.delayMs + 999) / 1000} 秒后重试（第 ${event.attempt}/${event.maxAttempts} 次）",
        )
        is EngineEvents.SummarizationRetryAttemptStart ->
            if (event.source == "compaction") state.copy(statusLine = COMPACTING) else state.copy(statusLine = SUMMARIZING)
        is EngineEvents.SummarizationRetryFinished ->
            if (state.statusLine?.startsWith("生成摘要失败") == true || state.statusLine == SUMMARIZING) state.copy(statusLine = null) else state
    }

    private fun onSettled(state: ChatState, aborted: Boolean): ChatState {
        streamingKey = null
        thinkingStartedAt.clear()
        var items = state.items.map { item ->
            when {
                item is ChatItem.Assistant && item.streaming -> item.copy(streaming = false, stopReason = item.stopReason ?: if (aborted) "aborted" else null)
                // 本轮已结束但工具没有结束事件：只可能是被中止或进程侧异常，标为失败。
                item is ChatItem.Tool && item.status == ToolStatus.RUNNING -> item.copy(status = ToolStatus.ERROR)
                // 本轮已结束仍未开始执行的工具：pi 不会再执行它，不能一直转圈。
                item is ChatItem.Tool && item.status == ToolStatus.PENDING -> item.notExecuted()
                else -> item
            }
        }
        if (aborted) items = items + ChatItem.Notice(nextKey("n"), NoticeKind.STOPPED, "已停止")
        return state.copy(
            running = false,
            items = items,
            retry = null,
            statusLine = if (state.statusLine == COMPACTING) null else state.statusLine,
        )
    }

    private fun onMessageStart(state: ChatState, message: JsonObject): ChatState = when (message.str("role")) {
        "user" -> confirmUser(state, message["content"])
        "assistant" -> {
            val key = nextKey("a")
            streamingKey = key
            val blocks = Messages.blocks(message, previous = emptyList())
            state.copy(items = state.items + ChatItem.Assistant(key, blocks, streaming = true))
        }
        // 其余 role 在 message_end 时一次性落地，避免 start/end 各算一次。
        else -> state
    }

    private fun confirmUser(state: ChatState, content: JsonElement?): ChatState {
        val text = Messages.text(content)
        val textOnly = ExtraResponses.textOnly(content)
        val pics = ExtraResponses.images(content).map { ImageInput(it.mimeType, it.data) }
        val items = state.items
        fun pendingAt(key: String) = items.indexOfFirst { it.key == key }
        // 文本 + 图片签名一起匹配（移植自 Pi-Web lib/prompt-recovery.ts:39-47 的 userMessageKey 思路）：
        // 带图的乐观气泡显示的是纯文本，pi 回来的内容是「文本 + 图片」。
        fun matches(k: String): Boolean {
            val u = items.getOrNull(pendingAt(k)) as? ChatItem.User ?: return false
            if (u.text == text && pics.isEmpty() && images[k].isNullOrEmpty()) return true
            return u.text == textOnly && sameImages(images[k].orEmpty(), pics)
        }
        val key = unconfirmed.firstOrNull { k -> matches(k) }
            // pi 可能展开模板/技能命令，文本与发送时不同：退而取最早一条「发送中/已发送」但未确认的气泡。
            // 已排队的气泡只按原文匹配（它们送达时文本不变），避免被别的消息误确认。
            ?: unconfirmed.firstOrNull { k ->
                (items.getOrNull(pendingAt(k)) as? ChatItem.User)?.delivery.let { it == Delivery.SENDING || it == Delivery.SENT }
            }
        if (key == null) {
            val k = nextKey("u")
            if (pics.isNotEmpty()) images[k] = pics
            return state.copy(items = items + ChatItem.User(k, text, Delivery.SENT))
        }
        unconfirmed.remove(key)
        if (pics.isNotEmpty()) images[key] = pics
        val idx = pendingAt(key)
        val bubble = (items[idx] as ChatItem.User).copy(text = text, delivery = Delivery.SENT)
        // 排队消息在真正送达时才进入时间线：移到末尾。
        val rest = items.toMutableList().apply { removeAt(idx) }
        return state.copy(items = rest + bubble)
    }

    private fun onUpdate(state: ChatState, e: AssistantMessageEvent, now: Long): ChatState {
        val key = streamingKey ?: (state.items.lastOrNull { it is ChatItem.Assistant && it.streaming }?.key) ?: run {
            // 没收到 message_start 就来了 update：补建条目，保证不丢字。
            if (e !is AssistantMessageEvent.TextDelta && e !is AssistantMessageEvent.ThinkingDelta &&
                e !is AssistantMessageEvent.TextStart && e !is AssistantMessageEvent.ThinkingStart &&
                e !is AssistantMessageEvent.ToolcallStart
            ) return state
            val k = nextKey("a")
            streamingKey = k
            return onUpdate(state.copy(items = state.items + ChatItem.Assistant(k)), e, now)
        }
        return state.updateItem(key) { item ->
            val a = item as? ChatItem.Assistant ?: return@updateItem item
            when (e) {
                is AssistantMessageEvent.TextStart -> a.ensure(e.contentIndex) { ContentBlock.Text(it, "") }
                is AssistantMessageEvent.TextDelta -> a.edit(e.contentIndex, { ContentBlock.Text(it, "") }) { b ->
                    if (b is ContentBlock.Text) b.copy(text = b.text + e.delta) else ContentBlock.Text(b.index, e.delta)
                }
                is AssistantMessageEvent.TextEnd -> {
                    val content = e.content
                    if (content == null) a else a.put(ContentBlock.Text(e.contentIndex, content))
                }
                is AssistantMessageEvent.ThinkingStart -> {
                    thinkingStartedAt.putIfAbsent(key to e.contentIndex, now)
                    a.ensure(e.contentIndex) { ContentBlock.Thinking(it, "") }
                }
                is AssistantMessageEvent.ThinkingDelta -> {
                    thinkingStartedAt.putIfAbsent(key to e.contentIndex, now)
                    a.edit(e.contentIndex, { ContentBlock.Thinking(it, "") }) { b ->
                        if (b is ContentBlock.Thinking) b.copy(text = b.text + e.delta) else ContentBlock.Thinking(b.index, e.delta)
                    }
                }
                is AssistantMessageEvent.ThinkingEnd -> {
                    val started = thinkingStartedAt.remove(key to e.contentIndex)
                    val dur = started?.let { (now - it).coerceAtLeast(0) }
                    val prev = a.blocks.firstOrNull { it.index == e.contentIndex } as? ContentBlock.Thinking
                    a.put(ContentBlock.Thinking(e.contentIndex, e.content ?: prev?.text ?: "", dur ?: prev?.durationMs))
                }
                is AssistantMessageEvent.ToolcallStart -> a.ensure(e.contentIndex) {
                    ContentBlock.ToolCall(it, e.id ?: "", e.toolName ?: "", "")
                }
                is AssistantMessageEvent.ToolcallDelta -> a.edit(e.contentIndex, { ContentBlock.ToolCall(it, "", "", "") }) { b ->
                    if (b is ContentBlock.ToolCall) b.copy(argsJson = b.argsJson + e.delta) else b
                }
                is AssistantMessageEvent.ToolcallEnd -> {
                    val tc = e.toolCall
                    if (tc == null) a else a.put(Messages.toolCallBlock(e.contentIndex, tc))
                }
                AssistantMessageEvent.Start, is AssistantMessageEvent.Done, is AssistantMessageEvent.Error,
                is AssistantMessageEvent.Other -> a
            }
        }
    }

    private fun onMessageEnd(state: ChatState, message: JsonObject): ChatState = when (val role = message.str("role")) {
        "assistant" -> {
            val key = streamingKey ?: state.items.lastOrNull { it is ChatItem.Assistant && it.streaming }?.key
            streamingKey = null
            var s = state
            val k = key ?: nextKey("a").also { s = s.copy(items = s.items + ChatItem.Assistant(it)) }
            val prev = s.items.firstOrNull { it.key == k } as? ChatItem.Assistant
            val finalItem = Messages.assistant(k, message, previous = prev?.blocks.orEmpty())
            thinkingStartedAt.keys.removeAll { it.first == k }
            s = s.updateItem(k) { finalItem }
            s = s.withToolPlaceholders(finalItem)
            s.withStopNotices(finalItem)
        }
        "user" -> {
            // 正常流程已在 message_start 处理；只在漏了 start 时补一条。
            val text = Messages.text(message["content"])
            val last = state.items.lastOrNull { it is ChatItem.User } as? ChatItem.User
            if (last != null && last.text == text) state else confirmUser(state, message["content"])
        }
        "toolResult" -> applyToolResult(state, message)
        "system" -> state
        else -> appendAux(state, role, message)
    }

    private fun onToolStart(state: ChatState, e: EngineEvent.ToolExecutionStart, now: Long): ChatState {
        val key = toolKey(e.toolCallId)
        return if (state.items.any { it.key == key }) {
            state.updateItem(key) {
                (it as ChatItem.Tool).copy(
                    toolName = e.toolName.ifEmpty { it.toolName },
                    args = e.args ?: it.args,
                    status = ToolStatus.RUNNING,
                    startedAtMillis = now,
                )
            }
        } else {
            state.copy(
                items = state.items + ChatItem.Tool(
                    key = key,
                    toolCallId = e.toolCallId,
                    toolName = e.toolName,
                    args = e.args,
                    status = ToolStatus.RUNNING,
                    startedAtMillis = now,
                ),
            )
        }
    }

    private fun onToolEnd(state: ChatState, e: EngineEvent.ToolExecutionEnd, now: Long): ChatState {
        val key = toolKey(e.toolCallId)
        val base = if (state.items.any { it.key == key }) state else state.copy(
            items = state.items + ChatItem.Tool(key, e.toolCallId, e.raw.str("toolName") ?: "", e.raw.obj("args")),
        )
        return base.updateItem(key) {
            val t = it as ChatItem.Tool
            t.copy(
                status = if (e.isError) ToolStatus.ERROR else ToolStatus.OK,
                result = e.result,
                durationMs = e.durationMs ?: t.startedAtMillis?.let { s -> (now - s).coerceAtLeast(0) },
            )
        }
    }

    private fun applyToolResult(state: ChatState, message: JsonObject): ChatState {
        val id = message.str("toolCallId") ?: return appendAux(state, "toolResult", message)
        val key = toolKey(id)
        val isError = message.bool("isError") ?: false
        val result = Messages.toolResultPayload(message)
        val base = if (state.items.any { it.key == key }) state else state.copy(
            items = state.items + ChatItem.Tool(key, id, message.str("toolName") ?: ""),
        )
        return base.updateItem(key) {
            val t = it as ChatItem.Tool
            // tool_execution_end 已给出终态时保留其结果与耗时；否则（如 get_messages 重建）以 toolResult 为准。
            if (t.status == ToolStatus.OK || t.status == ToolStatus.ERROR) t
            else t.copy(status = if (isError) ToolStatus.ERROR else ToolStatus.OK, result = result)
        }
    }

    private fun appendAux(state: ChatState, role: String?, message: JsonObject): ChatState {
        val item = Messages.auxItem(role, message, nextKey(if (Messages.isKnownAuxRole(role)) "n" else "raw")) ?: return state
        return state.copy(items = state.items + item)
    }

    private fun onRetryStart(state: ChatState, e: EngineEvent.AutoRetryStart): ChatState {
        // 自动重试时，刚失败的那条（无内容的）助手消息与它的错误提示由重试结果取代，不留在时间线上。
        var items = state.items
        val lastErrIdx = items.indexOfLast { it is ChatItem.Assistant && it.stopReason == "error" }
        if (lastErrIdx >= 0 && items.drop(lastErrIdx + 1).none { it is ChatItem.User || it is ChatItem.Assistant }) {
            val a = items[lastErrIdx] as ChatItem.Assistant
            val tail = items.drop(lastErrIdx + 1).filterNot { it is ChatItem.Notice && it.kind == NoticeKind.ERROR }
            val keepAssistant = a.blocks.any { b -> (b is ContentBlock.Text && b.text.isNotBlank()) || b is ContentBlock.ToolCall }
            items = items.take(lastErrIdx) + (if (keepAssistant) listOf(a) else emptyList()) + tail
        }
        return state.copy(items = items, retry = RetryBanner(e.attempt, e.maxAttempts, e.delayMs, e.errorMessage))
    }

    /** `compaction_start`：附加一张「正在压缩」的卡（U3）；已有进行中的卡（重复事件）不再加。 */
    private fun onCompactionStart(state: ChatState, e: EngineEvent.CompactionStart): ChatState {
        if (extras.none { it is TimelineExtra.Compaction && it.running }) {
            extras += TimelineExtra.Compaction(
                key = nextExtraKey("c"),
                anchorKey = state.items.lastOrNull()?.key,
                tokensBefore = null,
                estimatedTokensAfter = null,
                summary = null,
                manual = e.reason == "manual",
                running = true,
            )
        }
        return state.copy(statusLine = COMPACTING)
    }

    /**
     * `compaction_end`：时间线行为与 main 一致（一行提示）；压缩卡定稿：成功 → tokensBefore/After、摘要、读改过的文件，
     * 并把上下文占用置空（U2：pi 压缩后 percent 为 null，到下一次回复前不显示旧值）；中止 → aborted；失败 → error。
     */
    private fun onCompactionEnd(state: ChatState, e: EngineEvent.CompactionEnd): ChatState {
        val s = state.copy(statusLine = if (state.statusLine == COMPACTING) null else state.statusLine)
        val result = ExtraResponses.compaction(e.result)
        val manual = e.raw.str("reason") == "manual"
        return when {
            result != null -> applyCompaction(s, result, manual).withNotice(NoticeKind.COMPACTION, "已压缩上下文")
            e.aborted -> {
                finishRunningCompaction { it.copy(running = false, aborted = true) }
                s.withNotice(NoticeKind.INFO, "已取消压缩上下文")
            }
            e.errorMessage != null -> {
                finishRunningCompaction { it.copy(running = false, error = e.errorMessage) }
                s.withNotice(NoticeKind.ERROR, "压缩上下文失败：" + e.errorMessage)
            }
            else -> {
                finishRunningCompaction { it.copy(running = false) }
                s
            }
        }
    }

    private fun finishRunningCompaction(f: (TimelineExtra.Compaction) -> TimelineExtra.Compaction) {
        val i = extras.indexOfLast { it is TimelineExtra.Compaction && it.running }
        if (i >= 0) extras[i] = f(extras[i] as TimelineExtra.Compaction)
    }

    /**
     * `compact` 的响应（或 `compaction_end.result`）定稿压缩卡，并把上下文占用置空。幂等：事件已经定稿过同一结果时只补全缺的字段；
     * 没有进行中的卡（例如事件丢了）就新建一张，挂在时间线末尾。
     */
    fun applyCompaction(state: ChatState, result: CompactionResultRecord, manual: Boolean = true): ChatState {
        val parsed = CompactionSummary.parse(result.summary)
        fun card(key: String, anchor: String?, prev: TimelineExtra.Compaction?) = TimelineExtra.Compaction(
            key = key,
            anchorKey = anchor,
            tokensBefore = result.tokensBefore ?: prev?.tokensBefore,
            estimatedTokensAfter = result.estimatedTokensAfter ?: prev?.estimatedTokensAfter,
            summary = parsed.body,
            readFiles = parsed.readFiles,
            modifiedFiles = parsed.modifiedFiles,
            manual = manual || prev?.manual == true,
            running = false,
        )
        val running = extras.indexOfLast { it is TimelineExtra.Compaction && it.running }
        val done = extras.indexOfLast { it is TimelineExtra.Compaction }
        when {
            running >= 0 -> (extras[running] as TimelineExtra.Compaction).let { extras[running] = card(it.key, it.anchorKey, it) }
            done >= 0 && (extras[done] as TimelineExtra.Compaction).summary == parsed.body ->
                (extras[done] as TimelineExtra.Compaction).let { extras[done] = card(it.key, it.anchorKey, it) }
            else -> extras += card(nextExtraKey("c"), state.items.lastOrNull()?.key, null)
        }
        return state.copy(contextPercent = null, statusLine = if (state.statusLine == COMPACTING) null else state.statusLine)
    }

    // ------------------------------------------------------------------ bash（C6）

    /** 发出 `bash` 命令时附加一张运行中的终端卡；key 为 `bash-<命令 id>`，挂在当前时间线末尾。 */
    fun startBash(state: ChatState, bashId: String, command: String, excludeFromContext: Boolean): ChatState {
        extras += TimelineExtra.Bash(
            key = bashKey(bashId),
            anchorKey = state.items.lastOrNull()?.key,
            command = command,
            output = "",
            running = true,
            excludedFromContext = excludeFromContext,
        )
        return state
    }

    /** `bash_execution_update`：按 id 追加到对应的卡；没有 id 时追加到最后一张运行中的卡。 */
    private fun onBashDelta(state: ChatState, id: String?, delta: String): ChatState {
        if (delta.isEmpty()) return state
        val i = extras.indexOfLast { it is TimelineExtra.Bash && it.running && (id == null || it.key == bashKey(id)) }
        if (i < 0) return state
        val b = extras[i] as TimelineExtra.Bash
        val out = b.output + delta
        extras[i] = if (out.length > MAX_BASH_OUTPUT) b.copy(output = out.takeLast(MAX_BASH_OUTPUT), truncated = true) else b.copy(output = out)
        return state
    }

    /** `bash` 的响应：以 pi 的 `output` 为准定稿；[error] 为命令被拒绝 / 引擎退出等（追加在输出末尾）。 */
    fun finishBash(state: ChatState, bashId: String, result: BashResultRecord?, error: String?): ChatState {
        val i = extras.indexOfFirst { it.key == bashKey(bashId) }
        if (i < 0) return state
        val b = extras[i] as? TimelineExtra.Bash ?: return state
        extras[i] = if (result != null) {
            b.copy(
                running = false,
                output = result.output.ifEmpty { b.output },
                exitCode = result.exitCode,
                truncated = result.truncated || b.truncated,
                fullOutputPath = result.fullOutputPath,
            )
        } else {
            b.copy(running = false, output = b.output + (if (b.output.isEmpty() || b.output.endsWith("\n")) "" else "\n") + "[" + (error ?: "命令未完成") + "]")
        }
        return state
    }

    // ------------------------------------------------------------------ 轮末汇总（C10）

    /** `agent_settled`（未中止）后附加轮末汇总：本轮写过的文件、耗时、输出 token 与速率。没有可显示的内容时不加。 */
    private fun addTurnSummary(state: ChatState, now: Long) {
        val st = TurnStats.derive(state, turnClock.snapshot(), now)
        if (st.startedAtMillis == null) return
        if (st.writtenFiles.isEmpty() && st.durationMs == null && st.outputTokens == null) return
        extras += TimelineExtra.TurnSummary(
            key = nextExtraKey("t"),
            anchorKey = state.items.lastOrNull()?.key,
            writtenFiles = st.writtenFiles,
            durationMs = st.durationMs,
            outputTokens = st.outputTokens,
            tokensPerSecond = st.tokensPerSecond,
        )
    }

    /** 重建：每轮（两条用户消息之间）有写文件或输出 token 时补一条汇总；历史里没有可靠的耗时，durationMs/速率为 null。 */
    private fun rebuildTurnSummaries(state: ChatState, outputByAssistant: Map<String, Long>) {
        val items = state.items
        val userIdx = items.indices.filter { items[it] is ChatItem.User }
        for ((n, ui) in userIdx.withIndex()) {
            val endExcl = userIdx.getOrNull(n + 1) ?: items.size
            if (endExcl - ui <= 1) continue
            val turn = state.copy(items = items.subList(ui, endExcl))
            val files = TurnFiles.written(turn)
            val tokens = items.subList(ui, endExcl).sumOf { outputByAssistant[it.key] ?: 0L }
            if (files.isEmpty() && tokens <= 0) continue
            extras += TimelineExtra.TurnSummary(nextExtraKey("t"), items[endExcl - 1].key, files, null, tokens.takeIf { it > 0 }, null)
        }
    }

    // ------------------------------------------------------------------ 重建（get_messages）

    /**
     * 用 `get_messages` 的结果重建时间线（DESIGN.md §3.8）。保留 [base] 里的会话元信息（模型、标题、队列等），
     * 清空条目与内部簿记；assistant 的 toolCall 与后续 `toolResult` 按 `toolCallId` 合并成一张卡。
     */
    fun fromMessages(
        messages: JsonArray,
        base: ChatState = ChatState(),
        carry: List<ChatItem.User> = emptyList(),
        live: Boolean = false,
    ): ChatState {
        // 时间线里已写入会话的用户消息数（排除 handled 气泡），必须在 reset() 清掉簿记之前算出。
        val confirmedCount = base.items.count { it is ChatItem.User && it.delivery == Delivery.SENT && it.key !in notPersisted }
        val carryImages = carry.mapNotNull { c -> images[c.key]?.let { c.key to it } }
        reset()
        images.putAll(carryImages)
        // 队列与上下文占用属于进程 / 旧会话：重建后以新的 queue_update、get_session_stats 为准。
        // 例外：live（同一进程里仍在生成）时保留 running 与队列，否则会被当成「这一轮结束」（释放唤醒锁、停止键消失）。
        val keepRun = live && base.running
        val outputByAssistant = HashMap<String, Long>()
        var s = base.copy(
            items = emptyList(),
            running = keepRun,
            retry = null,
            queue = if (keepRun) base.queue else QueueSnapshot.EMPTY,
            contextPercent = null,
        )
        for (m in messages) {
            val o = m as? JsonObject ?: continue
            s = when (val role = o.str("role")) {
                "user" -> {
                    val k = nextKey("u")
                    ExtraResponses.images(o["content"]).takeIf { it.isNotEmpty() }?.let { l -> images[k] = l.map { ImageInput(it.mimeType, it.data) } }
                    s.copy(items = s.items + ChatItem.User(k, Messages.text(o["content"]), Delivery.SENT))
                }
                "assistant" -> {
                    val a = Messages.assistant(nextKey("a"), o, previous = emptyList())
                    var t = s.copy(items = s.items + a).withToolPlaceholders(a).withStopNotices(a)
                    if (a.stopReason == "aborted") t = t.withNotice(NoticeKind.STOPPED, "已停止")
                    t
                }
                "toolResult" -> applyToolResult(s, o)
                "system" -> s
                else -> appendAux(s, role, o).also { t -> t.items.lastOrNull()?.takeIf { t !== s }?.let { historyExtra(role, o, it.key) } }
            }
            if (o.str("role") == "assistant") (s.items.lastOrNull { it is ChatItem.Assistant })?.let { a ->
                val out = o.obj("usage")?.long("output") ?: 0
                if (out > 0) outputByAssistant[a.key] = out
            }
        }
        rebuildTurnSummaries(s, outputByAssistant)
        // 没有 toolResult 的工具调用：会话不在运行中时 pi 不会再执行它们，标「未执行」，不留转圈的卡片。
        if (!live) s = s.copy(items = s.items.map { if (it is ChatItem.Tool && it.status == ToolStatus.PENDING) it.notExecuted() else it })
        // 进程崩溃 / 强制重启时没能送达的消息（排队中、发送中），以「发送失败」气泡接在历史后面，由用户点「重试」，不自动重发。
        if (carry.isNotEmpty()) {
            val historyUserTexts = s.items.filterIsInstance<ChatItem.User>().map { it.text }
            // 只在历史尾部找：新增条数 = 历史条数 − 已确认条数，且不超过 carry 里 SENT 项的条数。
            // 已确认条数大于历史条数（压缩过）时 n = 0，宁可保留为「发送失败」也不悄悄丢掉。
            val n = (historyUserTexts.size - confirmedCount).coerceIn(0, carry.count { it.delivery == Delivery.SENT })
            val newTexts = historyUserTexts.takeLast(n).toMutableList()
            val kept = ArrayList<ChatItem.User>()
            for (c in carry) {
                // 「已发送」但没等到 message_start 的消息可能其实已写进会话：只有 SENT 项且能在 newTexts 中按多重集消费到时才视为已写入并丢掉。
                if (c.delivery == Delivery.SENT && newTexts.remove(c.text)) {
                    continue
                }
                kept.add(c)
            }
            if (kept.isNotEmpty()) {
                s = s.copy(items = s.items + kept.map { it.copy(delivery = Delivery.FAILED) })
                    .withNotice(NoticeKind.INFO, withdrawnText(kept.size))
            }
        }
        return s
    }

    // ------------------------------------------------------------------ 客户端侧操作（非 pi 事件）

    /** 发送后立即出现的乐观气泡（「发送中」）。 */
    fun addPendingUser(state: ChatState, key: String, text: String, images: List<ImageInput> = emptyList()): ChatState {
        unconfirmed += key
        if (images.isNotEmpty()) this.images[key] = images
        return state.copy(items = state.items + ChatItem.User(key, text, Delivery.SENDING))
    }

    /**
     * 收到 `prompt` 的 response 后更新气泡：started → 已发送，queued → 已排队，失败 → 标红。
     * 已被 `message_start` 确认（SENT）的气泡不会被降级为 QUEUED；`handled` 不会有 message_start，直接确认。
     */
    fun setDelivery(
        state: ChatState,
        key: String,
        delivery: Delivery,
        confirmed: Boolean = false,
        uncertain: Boolean = false,
    ): ChatState {
        val wasConfirmed = key !in unconfirmed
        // uncertain：prompt 应答超时，pi 可能已经收下。气泡先标红，但仍留在待确认集合里，
        // 之后若收到同文本的 message_start(user)，就把这条气泡确认为「已发送」，而不是再追加一条。
        if (confirmed || (delivery == Delivery.FAILED && !uncertain)) unconfirmed.remove(key)
        if (confirmed && delivery == Delivery.SENT) notPersisted += key
        return state.updateItem(key) {
            val u = it as? ChatItem.User ?: return@updateItem it
            if (u.delivery == Delivery.SENT && wasConfirmed && delivery != Delivery.FAILED) u else u.copy(delivery = delivery)
        }
    }

    /** 失败气泡用户点「重试」或撤回时移除。 */
    fun removeItem(state: ChatState, key: String): ChatState {
        unconfirmed.remove(key)
        notPersisted.remove(key)
        images.remove(key)
        return state.copy(items = state.items.filterNot { it.key == key })
    }

    /** 合并 `get_state`：模型、思考级别、会话名与文件。 */
    fun applyPiState(state: ChatState, pi: PiState): ChatState = state.copy(
        model = pi.model,
        thinkingLevel = pi.thinkingLevel ?: state.thinkingLevel,
        sessionName = pi.sessionName,
        sessionFile = pi.sessionFile ?: state.sessionFile,
    )

    fun addNotice(state: ChatState, kind: NoticeKind, text: String): ChatState = state.withNotice(kind, text)

    /**
     * 进程被强杀或异常退出：结束一切流式状态，未结束的工具标失败，追加一条提示。
     * 队列清空（进程里的队列随进程一起没了，以重启后的 `queue_update` 为准）。
     */
    fun interrupt(state: ChatState, kind: NoticeKind, text: String): ChatState = interruptWithdrawing(state, kind, text).state

    /** [interrupt] 的结果，加上随进程一起丢掉、需要用户决定是否重发的消息（按时间线顺序）。 */
    data class Interrupted(val state: ChatState, val withdrawn: List<ChatItem.User>)

    /**
     * 同 [interrupt]，并把没能送达的消息标为「发送失败」（可重试、不会自动重发）：
     * - 「已排队」的气泡：pi 的队列只在进程内存里，进程没了它们一定没发出；
     * - 「发送中」或「已发送」但还没等到 `message_start(user)` 确认的气泡；
     * - `queue_update` 里有、但时间线上没有对应气泡的文本（例如扩展排进去的），补一条失败气泡，文本不丢。
     * 返回的 [Interrupted.withdrawn] 由调用方在重建（[fromMessages] 的 `carry`）后接回时间线。
     */
    fun interruptWithdrawing(state: ChatState, kind: NoticeKind, text: String): Interrupted {
        streamingKey = null
        thinkingStartedAt.clear()
        val queued = (state.queue.steering + state.queue.followUp).toMutableList()
        val withdrawn = ArrayList<ChatItem.User>()
        val items = state.items.map { item ->
            when {
                item is ChatItem.Assistant && item.streaming -> item.copy(streaming = false, stopReason = item.stopReason ?: "aborted")
                item is ChatItem.Tool && (item.status == ToolStatus.RUNNING) -> item.copy(status = ToolStatus.ERROR)
                item is ChatItem.Tool && item.status == ToolStatus.PENDING -> item.notExecuted()
                item is ChatItem.User && (
                    item.delivery == Delivery.QUEUED || item.delivery == Delivery.SENDING ||
                        (item.key in unconfirmed && item.delivery != Delivery.FAILED)
                    ) -> {
                    queued.remove(item.text)
                    withdrawn += item
                    unconfirmed.remove(item.key)
                    item.copy(delivery = Delivery.FAILED)
                }
                else -> item
            }
        }
        val orphans = queued.map { ChatItem.User(nextKey("u"), it, Delivery.FAILED) }
        withdrawn += orphans
        val notices = buildList {
            if (withdrawn.isNotEmpty()) {
                add(ChatItem.Notice(nextKey("n"), NoticeKind.INFO, withdrawnText(withdrawn.size)))
            }
            add(ChatItem.Notice(nextKey("n"), kind, text))
        }
        turnClock.interrupt(lastNow)
        for (i in extras.indices) {
            val x = extras[i]
            if (x is TimelineExtra.Bash && x.running) extras[i] = x.copy(running = false, output = x.output + (if (x.output.isEmpty() || x.output.endsWith("\n")) "" else "\n") + "[引擎已退出，命令未完成]")
            if (x is TimelineExtra.Compaction && x.running) extras[i] = x.copy(running = false, aborted = true)
        }
        val next = state.copy(
            items = items + orphans + notices,
            running = false,
            queue = QueueSnapshot.EMPTY,
            retry = null,
            statusLine = if (state.statusLine == COMPACTING) null else state.statusLine,
        )
        return Interrupted(next, withdrawn)
    }

    /** 新会话：清空时间线与簿记，保留模型与思考级别。 */
    fun cleared(state: ChatState): ChatState {
        reset()
        turnClock.reset()
        return ChatState(model = state.model, thinkingLevel = state.thinkingLevel)
    }

    private fun reset() {
        streamingKey = null
        unconfirmed.clear()
        notPersisted.clear()
        thinkingStartedAt.clear()
        images.clear()
        extras.clear()
    }

    // ------------------------------------------------------------------ 工具

    private fun nextKey(prefix: String) = "$prefix-${seq++}"

    private fun nextExtraKey(prefix: String) = "x-$prefix-${extraSeq++}"

    /** 历史里的 `bashExecution` / `compactionSummary` 消息：时间线仍是一行提示（同 main），另附一张卡挂在该提示之后。 */
    private fun historyExtra(role: String?, o: JsonObject, anchor: String) {
        when (role) {
            "bashExecution" -> extras += TimelineExtra.Bash(
                key = nextExtraKey("b"),
                anchorKey = anchor,
                command = o.str("command") ?: "",
                output = o.str("output") ?: "",
                running = false,
                exitCode = o.long("exitCode")?.toInt(),
                excludedFromContext = o.bool("excludeFromContext") ?: false,
                truncated = o.bool("truncated") ?: false,
                fullOutputPath = o.str("fullOutputPath"),
            )
            "compactionSummary" -> CompactionSummary.parse(o.str("summary") ?: "").let { p ->
                extras += TimelineExtra.Compaction(
                    key = nextExtraKey("c"),
                    anchorKey = anchor,
                    tokensBefore = o.long("tokensBefore"),
                    estimatedTokensAfter = null,
                    summary = p.body,
                    readFiles = p.readFiles,
                    modifiedFiles = p.modifiedFiles,
                )
            }
        }
    }

    private fun ChatState.withNotice(kind: NoticeKind, text: String) = copy(items = items + ChatItem.Notice(nextKey("n"), kind, text))

    /**
     * assistant 消息里的每个 toolCall 先放一张 PENDING 卡（紧跟在该消息后），实时与重建两条路径顺序一致。
     * 只有 `stopReason == "toolUse"` 时 pi 才会执行这些工具；中止 / 出错 / 截断的消息里的 toolCall 不会执行，
     * 直接放「未执行」的失败卡，不让它一直转圈。
     */
    private fun ChatState.withToolPlaceholders(a: ChatItem.Assistant): ChatState {
        val willRun = a.stopReason == null || a.stopReason == "toolUse"
        val calls = a.blocks.filterIsInstance<ContentBlock.ToolCall>().filter { it.id.isNotEmpty() }
        if (calls.isEmpty()) return this
        val missing = calls.filter { c -> items.none { it.key == toolKey(c.id) } }
        if (missing.isEmpty()) return this
        val idx = items.indexOfFirst { it.key == a.key }
        val cards = missing.map { c ->
            val card = ChatItem.Tool(toolKey(c.id), c.id, c.name, args = Messages.parseArgs(c.argsJson), status = ToolStatus.PENDING)
            if (willRun) card else card.notExecuted()
        }
        val list = items.toMutableList()
        list.addAll(if (idx < 0) list.size else idx + 1, cards)
        return copy(items = list)
    }

    private fun ChatState.withStopNotices(a: ChatItem.Assistant): ChatState = when (a.stopReason) {
        "error" -> withNotice(NoticeKind.ERROR, ProviderErrors.hint(a.errorMessage).text)
        "length" -> withNotice(NoticeKind.TRUNCATED, "输出被截断")
        else -> this
    }

    companion object {
        const val COMPACTING = "正在压缩上下文…"
        const val SUMMARIZING = "正在生成分支摘要…"

        /** 终端卡最多保留的输出字符数（超出保留末尾并标截断；完整输出以 pi 的 fullOutputPath 为准）。 */
        const val MAX_BASH_OUTPUT = 256 * 1024

        fun bashKey(bashId: String) = "bash-$bashId"

        private fun sameImages(a: List<ImageInput>, b: List<ImageInput>): Boolean =
            a.size == b.size && a.indices.all { a[it].mimeType == b[it].mimeType && a[it].base64Data == b[it].base64Data }

        fun toolKey(toolCallId: String) = "tool-$toolCallId"

        const val NOT_EXECUTED = "未执行（本轮已中止）"

        fun withdrawnText(n: Int) = "$n 条消息未发出，已标为发送失败，可点「重试」重新发送"

        /** 不会再执行的工具卡：标失败，结果写明「未执行」（已有结果的保留）。 */
        internal fun ChatItem.Tool.notExecuted(): ChatItem.Tool = copy(
            status = ToolStatus.ERROR,
            result = result ?: buildJsonObject {
                put("content", JsonArray(listOf(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive(NOT_EXECUTED)) })))
            },
        )
    }
}

/** 消息体（`message-types.md`）→ 界面块。 */
internal object Messages {
    private val AUX_ROLES = setOf("bashExecution", "custom", "branchSummary", "compactionSummary")

    fun isKnownAuxRole(role: String?) = role in AUX_ROLES

    /** UserMessage.content 为字符串或 (Text|Image)[]。 */
    fun text(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> if (content.isString) content.content else ""
        is JsonArray -> content.mapNotNull { b ->
            val o = b as? JsonObject ?: return@mapNotNull null
            when (o.str("type")) {
                "text" -> o.str("text")
                "image" -> "[图片]"
                else -> null
            }
        }.joinToString("")
        else -> ""
    }

    fun blocks(message: JsonObject, previous: List<ContentBlock>): List<ContentBlock> {
        val content = message.arr("content") ?: return emptyList()
        return content.mapIndexedNotNull { i, b ->
            val o = b as? JsonObject ?: return@mapIndexedNotNull null
            when (o.str("type")) {
                "text" -> ContentBlock.Text(i, o.str("text") ?: "")
                "thinking" -> ContentBlock.Thinking(
                    i,
                    o.str("thinking") ?: "",
                    (previous.firstOrNull { it.index == i } as? ContentBlock.Thinking)?.durationMs,
                )
                "toolCall" -> toolCallBlock(i, o)
                else -> null
            }
        }
    }

    fun toolCallBlock(index: Int, tc: JsonObject) = ContentBlock.ToolCall(
        index = index,
        id = tc.str("id") ?: "",
        name = tc.str("name") ?: "",
        argsJson = tc["arguments"]?.toString() ?: "{}",
    )

    fun assistant(key: String, message: JsonObject, previous: List<ContentBlock>) = ChatItem.Assistant(
        key = key,
        blocks = blocks(message, previous),
        streaming = false,
        stopReason = message.str("stopReason")?.takeUnless { it == "pending" },
        errorMessage = message.str("errorMessage"),
    )

    fun parseArgs(json: String): JsonObject? = try {
        RecordCodec.json.parseToJsonElement(json) as? JsonObject
    } catch (e: Exception) {
        null
    }

    /** toolResult 消息 → 与 `tool_execution_end.result` 同形的 `{content, details}`。 */
    fun toolResultPayload(message: JsonObject): JsonObject = buildJsonObject {
        message["content"]?.let { put("content", it) }
        message["details"]?.let { put("details", it) }
    }

    fun auxItem(role: String?, o: JsonObject, key: String): ChatItem? = when (role) {
        "bashExecution" -> ChatItem.Notice(
            key,
            NoticeKind.INFO,
            "执行了命令：" + (o.str("command") ?: "") + (o.long("exitCode")?.let { "（退出码 $it）" } ?: ""),
        )
        "custom" -> if (o.bool("display") == false) null else ChatItem.Notice(key, NoticeKind.INFO, text(o["content"]))
        "branchSummary" -> ChatItem.Notice(key, NoticeKind.INFO, "分支摘要：" + (o.str("summary") ?: ""))
        "compactionSummary" -> ChatItem.Notice(key, NoticeKind.COMPACTION, "已压缩上下文")
        else -> ChatItem.Raw(key, role, o)
    }
}

// ---- ChatState 的小工具 ----

internal inline fun ChatState.updateItem(key: String, f: (ChatItem) -> ChatItem): ChatState {
    val idx = items.indexOfFirst { it.key == key }
    if (idx < 0) return this
    val list = items.toMutableList()
    list[idx] = f(list[idx])
    return copy(items = list)
}

internal inline fun ChatState.updateTool(toolCallId: String, f: (ChatItem.Tool) -> ChatItem.Tool): ChatState =
    updateItem(ChatReducer.toolKey(toolCallId)) { if (it is ChatItem.Tool) f(it) else it }

private fun ChatItem.Assistant.put(block: ContentBlock): ChatItem.Assistant =
    copy(blocks = (blocks.filter { it.index != block.index } + block).sortedBy { it.index })

private inline fun ChatItem.Assistant.ensure(index: Int, create: (Int) -> ContentBlock): ChatItem.Assistant =
    if (blocks.any { it.index == index }) this else put(create(index))

private inline fun ChatItem.Assistant.edit(index: Int, create: (Int) -> ContentBlock, f: (ContentBlock) -> ContentBlock): ChatItem.Assistant {
    val b = blocks.firstOrNull { it.index == index } ?: create(index)
    return put(f(b))
}
