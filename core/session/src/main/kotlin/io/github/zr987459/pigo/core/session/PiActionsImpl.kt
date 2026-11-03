package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.BashEvent
import io.github.zr987459.pigo.contract.CommandSource
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.actions.PiActionCatalog
import io.github.zr987459.pigo.contract.actions.PiActionSpec
import io.github.zr987459.pigo.contract.actions.PiActions
import io.github.zr987459.pigo.contract.actions.TermEntry
import io.github.zr987459.pigo.contract.actions.UiEntry
import io.github.zr987459.pigo.contract.models.ModelPanels
import io.github.zr987459.pigo.contract.models.ModelPickerTab
import io.github.zr987459.pigo.contract.session.BashMode
import io.github.zr987459.pigo.contract.session.FormField
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.SelectGroup
import io.github.zr987459.pigo.contract.session.SelectItem
import io.github.zr987459.pigo.contract.session.TreeNode
import io.github.zr987459.pigo.contract.session.UiForm
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonPrimitive

/**
 * pi 能力在 `core/contract` 里还没有方法的几处（DESIGN-r3 §5.6 / §2.3）：由 AppGraph 用真实引擎实现，
 * 测试里用记录命令的替身。确需补进契约时由 r3/core 加（见 PR 描述「契约缺口」）。
 */
interface SessionOps {
    /** RPC `abort_retry`（只发它，不发 abort / clear_queue，§7.3 AbortRetryTest）。 */
    suspend fun abortRetry(): Outcome<Unit>

    /** 把 pi 排队中的消息取回（Alt+↑ / 队列条「取回」）：返回被取回的快照。 */
    suspend fun dequeueAll(): Outcome<QueueSnapshot>

    /** 引擎停止（`/quit` 的手机语义：结束 pi 进程，下次发送时再启动）。 */
    suspend fun stopEngine(): Outcome<Unit>

    /** 设置标签（桥接 `/pigo:label`）；不可用时返回 Err 说明原因。 */
    suspend fun setLabel(entryId: String, label: String?): Outcome<Unit>

    /** 重新加载资源（桥接 `/pigo:reload`）。 */
    suspend fun reload(): Outcome<Unit>
}

/**
 * [PiActions] 的唯一实现（DESIGN-r3 §5.4）：两种形态共用。**只返回结果**，压栈与本地块由
 * [SessionHostImpl.runAction] 落地一次。模型配置相关的面板经 [ModelPanels]（由 feature/models 实现、AppGraph 注入）。
 */
class PiActionsImpl(
    private val host: SessionHostImpl,
    private val ops: SessionOps,
    private val modelPanels: ModelPanels? = null,
    override val all: List<PiActionSpec> = PiActionCatalog.all,
) : PiActions {

    private val byId = all.associateBy { it.id }

    override suspend fun run(id: String, args: String?): ActionResult {
        val spec = byId[id] ?: return ActionResult.Toast("未知动作：$id")
        explainOf(spec)?.let { return ActionResult.Overlay(OverlayModel.Text("explain:$id", spec.title, it)) }
        val e = host.engine
        val x = host.extras
        return when (id) {
            // ---- 发送与生成 ----
            "send" -> host.sendDraft(SendMode.STEER).toResult()
            "send.followUp" -> host.sendDraft(SendMode.FOLLOW_UP).toResult()
            "abort" -> when {
                host.chat.value.retry != null -> ops.abortRetry().toResult()
                else -> e.stop().let { r ->
                    if (r is Outcome.Ok) host.draft.value = QueueRecall.merge(host.draft.value, r.value.steering + r.value.followUp)
                    r.toResult()
                }
            }
            "retry.abort" -> ops.abortRetry().toResult()
            "queue.dequeue" -> ops.dequeueAll().let { r ->
                if (r is Outcome.Ok) host.draft.value = QueueRecall.merge(host.draft.value, r.value.steering + r.value.followUp)
                r.toResult()
            }
            "bash" -> {
                val cmd = args ?: host.draft.value.text
                if (args == null) {
                    host.draft.value = host.draft.value.copy(bashMode = if (host.draft.value.bashMode == BashMode.NONE) BashMode.INCLUDE else BashMode.NONE)
                    ActionResult.Done
                } else runBash(cmd, exclude = false)
            }
            "bash.abort" -> x.abortBash().toResult()
            "oneshot" -> if (args.isNullOrBlank()) {
                ActionResult.Overlay(OverlayModel.Form("overlay:oneshot", spec.title, listOf(
                    FormField("prompt", "问题", io.github.zr987459.pigo.contract.settings.PiSettingType.Str),
                    FormField("json", "输出 JSON 事件流", io.github.zr987459.pigo.contract.settings.PiSettingType.Bool),
                ), submitLabel = "提问"))
            } else runBash(OneShot.command(args, json = false), exclude = true, title = "一次性提问")

            // ---- 会话 ----
            "session.new" -> host.sessions.newSession(false).toResult()
            "session.newEphemeral" -> host.sessions.newSession(true).toResult()
            "session.resume" -> { host.sessions.refresh(); ActionResult.Overlay(sessionPicker()) }
            "session.name" -> if (args.isNullOrBlank()) {
                ActionResult.Overlay(OverlayModel.Form("overlay:session.name", spec.title, listOf(FormField("name", "会话名", io.github.zr987459.pigo.contract.settings.PiSettingType.Str)),
                    values = mapOf("name" to (host.chat.value.sessionName ?: "")), submitLabel = "保存"))
            } else e.rename(args.trim()).toResult()
            "session.fork" -> if (args.isNullOrBlank()) {
                when (val r = x.forkMessages()) {
                    is Outcome.Ok -> ActionResult.Overlay(OverlayModel.SelectList("overlay:session.fork", spec.title,
                        listOf(SelectGroup(null, r.value.map { SelectItem(it.entryId, it.text.lineSequence().firstOrNull().orEmpty()) }))))
                    is Outcome.Err -> ActionResult.Toast("分叉失败：${r.message}")
                }
            } else x.fork(args).toResult()
            "session.clone" -> x.clone().toResult()
            "session.tree", "tree.filter", "tree.fold", "tree.time" -> treeOverlay(id)
            "tree.label" -> if (args == null) ActionResult.Toast("在分支树里长按一条记录设置标签") else {
                val (entry, label) = args.split(' ', limit = 2).let { it[0] to it.getOrNull(1) }
                ops.setLabel(entry, label).toResult()
            }
            "session.compact" -> x.compact(args?.takeIf { it.isNotBlank() }).toResult()
            "session.export" -> x.exportHtml(args?.takeIf { it.isNotBlank() }).let { r ->
                if (r is Outcome.Ok) ActionResult.Toast("已导出：${r.value}") else r.toResult()
            }
            "session.import" -> if (args.isNullOrBlank()) ActionResult.Toast("选择要导入的 .jsonl 会话文件") else host.sessions.import(args).toResult()
            "session.delete" -> {
                val f = args ?: host.chat.value.sessionFile ?: return ActionResult.Toast("当前没有会话文件")
                when (val r = host.sessions.delete(f)) {
                    is Outcome.Ok -> ActionResult.Toast("已删除，5 秒内可撤销")
                    is Outcome.Err -> ActionResult.Toast("删除失败：${r.message}")
                }
            }
            "session.cwd" -> ActionResult.Toast("选择新会话的工作目录")
            "session.info" -> sessionInfo()
            "session.share" -> runBash("pi --share ${shellQuote(host.chat.value.sessionFile ?: "")}", exclude = true, title = "分享")
            "copy.last" -> when (val r = x.lastAssistantText()) {
                is Outcome.Ok -> if (r.value.isNullOrEmpty()) ActionResult.Toast("还没有助手回复") else { lastCopied = r.value; ActionResult.Toast("已复制最后一条回复") }
                is Outcome.Err -> ActionResult.Toast(r.message)
            }
            "transcript.search" -> ActionResult.Overlay(OverlayModel.SelectList("overlay:transcript.search", spec.title, query = args.orEmpty()))
            "transcript.jump" -> ActionResult.Done

            // ---- 模型与思考 ----
            "model.select" -> modelPanels?.let { ActionResult.Overlay(it.modelPicker(ModelPickerTab.ALL, args.orEmpty())) } ?: modelFallback()
            "model.scoped" -> modelPanels?.let { ActionResult.Overlay(it.modelPicker(ModelPickerTab.SCOPED, args.orEmpty())) } ?: modelFallback()
            "model.cycle" -> x.cycleModel().toResult()
            "thinking.cycle" -> x.cycleThinking().toResult()
            "thinking.select" -> when (val r = e.thinkingLevels()) {
                is Outcome.Ok -> if (args != null) e.setThinking(args).toResult() else ActionResult.Overlay(OverlayModel.SelectList("overlay:thinking.select", spec.title,
                    listOf(SelectGroup(null, r.value.map { SelectItem(it, it, toggled = it == host.chat.value.thinkingLevel) }))))
                is Outcome.Err -> ActionResult.Toast(r.message)
            }
            "thinking.toggle" -> toggleSetting("hideThinkingBlock")
            "auth.login" -> modelPanels?.let { ActionResult.Overlay(if (args.isNullOrBlank()) it.providerList() else it.oauthFlow(args)) } ?: modelFallback()
            "auth.logout" -> modelPanels?.let { ActionResult.Overlay(it.logoutPicker()) } ?: modelFallback()
            "provider.custom" -> modelPanels?.let { ActionResult.Overlay(it.customProviderForm(args)) } ?: modelFallback()
            "model.setDefault", "model.thinkingAdvanced", "model.refreshCatalog" -> modelPanels?.let { ActionResult.Overlay(it.providerList()) } ?: modelFallback()
            "ext.llama" -> modelPanels?.let { ActionResult.Overlay(it.providerForm("llama")) } ?: modelFallback()

            // ---- pi 设置（schema 生成的表单）----
            "settings" -> ActionResult.Overlay(PiSettingsForms.groups(host.settings))
            "settings.session", "settings.tools", "settings.network", "settings.queue", "settings.compaction", "settings.retry", "appearance" ->
                ActionResult.Overlay(PiSettingsForms.group(host.settings, PiSettingsForms.groupOf(id)))
            "settings.contextFiles", "context.system", "trust" -> ActionResult.Overlay(OverlayModel.Form("overlay:$id", spec.title,
                PiSettingsForms.fieldsFor(host.settings, PiSettingsForms.groupOf(id)), PiSettingsForms.valuesFor(host.settings, PiSettingsForms.groupOf(id)), submitLabel = "保存"))
            "reload" -> ops.reload().let { if (it is Outcome.Ok) ActionResult.Toast("已重新加载") else it.toResult() }

            // ---- 资源 ----
            "skills" -> commandsOverlay(id, spec.title, CommandSource.SKILL)
            "templates" -> commandsOverlay(id, spec.title, CommandSource.PROMPT)
            "ext.commands" -> commandsOverlay(id, spec.title, CommandSource.EXTENSION)
            "packages" -> runBash("pi list", exclude = true, title = spec.title)
            "mcp" -> runBash("pi mcp list", exclude = true, title = spec.title)

            // ---- 编辑器（只动共享草稿，不发 RPC）----
            "editor.clear" -> { host.draft.value = io.github.zr987459.pigo.contract.session.DraftState(recalled = host.draft.value.recalled); ActionResult.Done }
            "editor.expand", "tools.expandAll", "attach.image" -> ActionResult.Done
            "editor.history" -> ActionResult.Overlay(OverlayModel.SelectList("overlay:editor.history", spec.title,
                listOf(SelectGroup(null, host.promptHistory.value.asReversed().mapIndexed { i, t -> SelectItem("h$i", t.lineSequence().first()) }))))
            "form.toggle" -> { host.form.value = when (args) { "ui" -> UiForm.CALM; "term" -> UiForm.TERM; else -> if (host.form.value == UiForm.CALM) UiForm.TERM else UiForm.CALM }; ActionResult.Done }

            // ---- 帮助与诊断（本地块，不写会话）----
            "help" -> ActionResult.LocalBlock("帮助", helpText())
            "hotkeys" -> ActionResult.LocalBlock("快捷键", Hotkeys.TEXT)
            "changelog" -> ActionResult.LocalBlock("更新日志", "pi 1.5.0（@earendil-works/pi-coding-agent）。完整更新日志：在终端形态运行 !pi --version 后查看 CHANGELOG。")
            "about" -> ActionResult.LocalBlock("关于", "pi-go：pi 1.5.0 的安卓客户端。引擎：pi --mode rpc。")
            "debug" -> ActionResult.LocalBlock("诊断", "引擎状态：${e.status.value::class.simpleName}；PID：${host.debugEnginePid() ?: "—"}；会话：${host.chat.value.sessionFile ?: "—"}")
            "bug" -> runBash("pi --version", exclude = true, title = "问题反馈")
            "engine.stop" -> ops.stopEngine().toResult()
            else -> ActionResult.Toast("「${spec.title}」暂未实现")
        }
    }

    /** 最近一次「复制最后一条回复」得到的文字（形态负责写剪贴板）。 */
    var lastCopied: String? = null
        private set

    private fun explainOf(spec: PiActionSpec): String? =
        (spec.ui as? UiEntry.Explain)?.text ?: (spec.term as? TermEntry.Explain)?.text

    private suspend fun runBash(command: String, exclude: Boolean, title: String = "命令"): ActionResult {
        val events = host.extras.bash(command, excludeFromContext = exclude).toList()
        val out = StringBuilder()
        var err: String? = null
        for (ev in events) when (ev) {
            is BashEvent.Output -> Unit
            is BashEvent.Done -> out.append(ev.result.output)
            is BashEvent.Failed -> err = ev.error.message
        }
        return err?.let { ActionResult.Toast("$title 失败：$it") } ?: ActionResult.LocalBlock(title, out.toString())
    }

    private suspend fun commandsOverlay(id: String, title: String, source: CommandSource): ActionResult =
        when (val r = host.extras.commands(refresh = false)) {
            is Outcome.Ok -> ActionResult.Overlay(OverlayModel.SelectList("overlay:$id", title, listOf(SelectGroup(null,
                r.value.filter { it.source == source && !it.name.startsWith("pigo:") }.map { SelectItem(it.name, "/" + it.name, it.description) }))))
            is Outcome.Err -> ActionResult.Toast(r.message)
        }

    private suspend fun treeOverlay(id: String): ActionResult = when (val r = host.extras.tree()) {
        is Outcome.Ok -> {
            val nodes = ArrayList<TreeNode>()
            fun walk(n: io.github.zr987459.pigo.contract.TreeNode) {
                nodes += TreeNode(n.entry.id, n.entry.parentId, n.entry.role ?: n.entry.type, n.entry.preview, n.label, isLeaf = n.entry.id == r.value.leafId)
                n.children.forEach(::walk)
            }
            r.value.roots.forEach(::walk)
            ActionResult.Overlay(OverlayModel.Tree("overlay:session.tree", "分支树", nodes, selectedId = r.value.leafId,
                showTime = id == "tree.time"))
        }
        is Outcome.Err -> ActionResult.Toast("读取分支树失败：${r.message}")
    }

    private fun sessionPicker(): OverlayModel.SelectList {
        val list = host.sessions.sessions.value
        val opts = host.sessions.options.value
        return OverlayModel.SelectList("overlay:session.resume", "会话", listOf(SelectGroup(null, list.map {
            SelectItem(it.guestPath, it.name ?: it.preview.ifBlank { it.id }, if (opts.showPath) it.cwd else null)
        })), query = opts.query)
    }

    private suspend fun sessionInfo(): ActionResult {
        val c = host.chat.value
        val stats = (host.extras.usage() as? Outcome.Ok)?.value
        val text = buildString {
            appendLine("会话文件：${c.sessionFile ?: "—"}")
            appendLine("会话名：${c.sessionName ?: "—"}")
            appendLine("模型：${c.model?.let { "${it.provider}/${it.id}" } ?: "—"}  思考：${c.thinkingLevel ?: "—"}")
            if (stats != null) {
                appendLine("消息：用户 ${stats.userMessages}、助手 ${stats.assistantMessages}、工具 ${stats.toolCalls}")
                appendLine("Token：入 ${stats.tokens.input}、出 ${stats.tokens.output}、缓存读 ${stats.tokens.cacheRead}、写 ${stats.tokens.cacheWrite}")
                stats.costUsd?.let { appendLine("费用：$${"%.4f".format(it)}") }
            }
            if (host.ephemeral.value) appendLine("临时会话：离开后不保留")
        }.trimEnd()
        return ActionResult.LocalBlock("会话信息", text)
    }

    private suspend fun toggleSetting(key: String): ActionResult {
        val cur = (host.settings.values.value[key] as? JsonPrimitive)?.content == "true"
        return host.settings.set(key, JsonPrimitive(!cur)).toResult()
    }

    private fun modelFallback(): ActionResult = ActionResult.Toast("模型面板尚未接入（设置 › 模型）")

    private fun helpText(): String = all.groupBy { it.group }.entries.joinToString("\n\n") { (g, specs) ->
        g.name + "\n" + specs.filter { it.ui !is UiEntry.Explain }.joinToString("\n") { s -> "  " + s.title + (s.slash.firstOrNull()?.let { "  /$it" } ?: "") }
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}

/** 一次性提问的命令（`bash` + `excludeFromContext` 跑 `pi -p`，不进当前会话，DESIGN-r3 §2.1）。 */
object OneShot {
    fun command(prompt: String, json: Boolean): String {
        val q = "'" + prompt.replace("'", "'\\''") + "'"
        return if (json) "pi --mode json -p $q" else "pi -p $q"
    }
}

/** `/hotkeys` 的本地块文字（pi 默认键位与触屏映射，清单 §9）。 */
object Hotkeys {
    const val TEXT = """Enter 发送 / 生成中引导    Alt+Enter 排队
Esc 中止（排队文字回编辑器）    Esc Esc 分支树
Ctrl+L 选模型    Ctrl+P / Shift+Ctrl+P 循环模型
Shift+Tab 循环思考强度    Ctrl+T 显示 / 隐藏思考块
Ctrl+O 展开工具输出    Alt+↑ 取回排队消息
Ctrl+C 清空编辑器    ↑ / ↓ 历史提示
Ctrl+Alt+T 切换 UI / 终端形态（pi-go）"""
}

private fun <T> Outcome<T>.toResult(): ActionResult = when (this) {
    is Outcome.Ok -> ActionResult.Done
    is Outcome.Err -> ActionResult.Toast(message)
}
