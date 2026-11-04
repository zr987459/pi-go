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
import io.github.zr987459.pigo.contract.session.TreeFilter
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

    /**
     * `/tree` 选中一条记录：同一会话文件内把叶子切过去（r3/core `PiBridge.treeNavigate`，桥接 `/pigo:tree-nav`；
     * 桥接不可用时由实现退化为 fork，见 [TreeNav.viaFork]）。
     */
    suspend fun treeNavigate(entryId: String, summarize: Boolean): Outcome<TreeNav>

    /**
     * 在运行环境（PRoot）里直接执行一条命令，**不经 RPC `bash`**，所以不会写进当前会话文件（审查 N4）。
     * 用于 `pi list`、`pi update --models`、`pi -p`、`gh gist create`、读包内 CHANGELOG.md 等。
     */
    suspend fun runGuest(command: String, timeoutMs: Long = 120_000): Outcome<GuestOutput>

    /** 把文字写进 App 缓存目录下的文件（问题报告），返回宿主路径，供分享。 */
    suspend fun writeReport(fileName: String, text: String): Outcome<String>

    /** 在另一个工作目录新建会话（pi 的 cwd 是进程目录，必须重启引擎；调用方已先确认，§5.5）。 */
    suspend fun newSessionInDir(dir: String): Outcome<Unit>
}

/** [SessionOps.treeNavigate] 的结果（字段与 r3/core 的 `TreeNavResult` 一致）。 */
data class TreeNav(
    val leafId: String?,
    val cancelled: Boolean = false,
    val viaFork: Boolean = false,
    val editorText: String? = null,
    val unconfirmed: Boolean = false,
)

/** [SessionOps.runGuest] 的结果。 */
data class GuestOutput(val exitCode: Int, val output: String)

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
                // 经聊天端口停止：撤回的排队消息进共享草稿（含「已撤回」芯片），两种形态一致（审查第 2 轮 I2）。
                else -> host.chatPort.stop().toResult()
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
            } else {
                // args：问题文本；以 "--json " 开头时输出 JSON 事件流（pi --mode json -p）。
                val json = args.startsWith(OneShot.JSON_PREFIX)
                guest(OneShot.command(args.removePrefix(OneShot.JSON_PREFIX), json), "一次性提问", timeoutMs = 600_000)
            }

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
            "session.tree" -> if (args.isNullOrBlank()) treeOverlay(TreeView()) else treeNav(args)
            "tree.filter" -> treeOverlay(treeView.copy(filter = TreeFilter.entries.firstOrNull { it.piName == args?.trim() } ?: nextFilter(treeView.filter)))
            "tree.fold" -> treeOverlay(treeView.copy(collapsed = when {
                args.isNullOrBlank() -> if (treeView.collapsed.isEmpty()) ALL_COLLAPSED else emptySet()
                args.trim() in treeView.collapsed -> treeView.collapsed - args.trim()
                else -> treeView.collapsed + args.trim()
            }))
            "tree.time" -> treeOverlay(treeView.copy(showTime = !treeView.showTime))
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
            "session.cwd" -> when {
                args.isNullOrBlank() -> ActionResult.Overlay(OverlayModel.Form("overlay:session.cwd", spec.title,
                    listOf(FormField("dir", "工作目录（运行环境里的路径，如 /root/proj）", io.github.zr987459.pigo.contract.settings.PiSettingType.Str,
                        "pi 的工作目录是进程目录：在其他目录新建会话会重启引擎（当前生成会中止）")),
                    values = mapOf("dir" to ""), submitLabel = "下一步"))
                args.startsWith(CONFIRM_PREFIX) -> ops.newSessionInDir(args.removePrefix(CONFIRM_PREFIX).trim()).toResult()
                else -> ActionResult.Overlay(OverlayModel.Confirm("overlay:session.cwd.confirm:${args.trim()}", "在其他目录新建会话",
                    "将在 ${args.trim()} 重启引擎并新建会话；正在生成的回复会中止。", confirmLabel = "重启并新建", dangerous = true))
            }
            "session.info" -> sessionInfo()
            "session.share" -> share()
            "copy.last" -> when (val r = x.lastAssistantText()) {
                is Outcome.Ok -> when {
                    r.value.isNullOrEmpty() -> ActionResult.Toast("还没有助手回复")
                    // 文字交给形态写进系统剪贴板（SessionHost.clipboardRequests）；没有形态在收集就不说「已复制」。
                    host.requestClipboard(r.value!!) -> ActionResult.Toast("已复制最后一条回复")
                    else -> ActionResult.Toast("复制失败：界面未就绪")
                }
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
            "model.setDefault" -> setDefaultModel(args)
            "model.thinkingAdvanced" -> ActionResult.Overlay(OverlayModel.Form("overlay:model.thinkingAdvanced", spec.title,
                PiSettingsForms.fieldsFor(host.settings, "模型").filter { it.key in THINKING_ADVANCED_KEYS },
                PiSettingsForms.valuesFor(host.settings, "模型").filterKeys { it in THINKING_ADVANCED_KEYS }, submitLabel = "保存"))
            "model.refreshCatalog" -> when (val r = ops.runGuest("pi update --models", timeoutMs = 300_000)) {
                is Outcome.Ok -> if (r.value.exitCode == 0) { runCatching { e.models() }; ActionResult.Toast("模型目录已刷新") }
                    else ActionResult.LocalBlock("刷新模型目录失败", r.value.output)
                is Outcome.Err -> ActionResult.Toast("刷新模型目录失败：${r.message}")
            }
            // /llama、/mcp 是 pi 内置扩展注册的命令：经 RPC prompt 发送会立即执行，界面走 extension_ui_request（清单 §3）。
            "ext.llama" -> extCommand("llama", args)

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
            "packages" -> packages(args, spec.title)
            "mcp" -> extCommand("mcp", args)

            // ---- 编辑器（只动共享草稿，不发 RPC）----
            "editor.clear" -> { host.draft.value = io.github.zr987459.pigo.contract.session.DraftState(recalled = host.draft.value.recalled); ActionResult.Done }
            "editor.expand", "tools.expandAll", "attach.image" -> ActionResult.Done
            "editor.history" -> ActionResult.Overlay(OverlayModel.SelectList("overlay:editor.history", spec.title,
                listOf(SelectGroup(null, host.promptHistory.value.asReversed().mapIndexed { i, t -> SelectItem("h$i", t.lineSequence().first()) }))))
            "form.toggle" -> { host.form.value = when (args) { "ui" -> UiForm.CALM; "term" -> UiForm.TERM; else -> if (host.form.value == UiForm.CALM) UiForm.TERM else UiForm.CALM }; ActionResult.Done }

            // ---- 帮助与诊断（本地块，不写会话）----
            "help" -> ActionResult.LocalBlock("帮助", helpText())
            "hotkeys" -> ActionResult.LocalBlock("快捷键", Hotkeys.TEXT)
            "changelog" -> when (val r = ops.runGuest(CHANGELOG_CMD)) {
                is Outcome.Ok -> if (r.value.exitCode == 0 && r.value.output.isNotBlank()) ActionResult.LocalBlock("更新日志", r.value.output)
                    else ActionResult.Toast("读取包内 CHANGELOG.md 失败：${r.value.output.lineSequence().firstOrNull().orEmpty()}")
                is Outcome.Err -> ActionResult.Toast("读取更新日志失败：${r.message}")
            }
            "about" -> ActionResult.LocalBlock("关于", "pi-go：pi 1.5.0 的安卓客户端。引擎：pi --mode rpc。")
            "debug" -> ActionResult.LocalBlock("诊断", "引擎状态：${e.status.value::class.simpleName}；PID：${host.debugEnginePid() ?: "—"}；会话：${host.chat.value.sessionFile ?: "—"}")
            "bug" -> bugReport(args)
            "engine.stop" -> ops.stopEngine().toResult()
            else -> ActionResult.Toast("「${spec.title}」暂未实现")
        }
    }

    private fun explainOf(spec: PiActionSpec): String? =
        (spec.ui as? UiEntry.Explain)?.text ?: (spec.term as? TermEntry.Explain)?.text

    /** `!` 命令模式开关（真正执行在发送时经 RPC `bash`，这是 pi 的 `!cmd` 语义，输出进会话）。 */
    private suspend fun runBash(command: String, exclude: Boolean): ActionResult {
        val events = host.extras.bash(command, excludeFromContext = exclude).toList()
        val err = events.filterIsInstance<BashEvent.Failed>().firstOrNull()?.error?.message
        return err?.let { ActionResult.Toast("命令失败：$it") } ?: ActionResult.Done
    }

    /** 在运行环境里直接执行（不写会话文件），输出作为本地块。 */
    private suspend fun guest(command: String, title: String, timeoutMs: Long = 120_000): ActionResult =
        when (val r = ops.runGuest(command, timeoutMs)) {
            is Outcome.Ok -> ActionResult.LocalBlock(if (r.value.exitCode == 0) title else "$title（退出码 ${r.value.exitCode}）", r.value.output)
            is Outcome.Err -> ActionResult.Toast("$title 失败：${r.message}")
        }

    /** 扩展命令（`/mcp`、`/llama` 等）：经 RPC prompt 发送，pi 立即执行，界面经扩展 UI 协议回来。 */
    private suspend fun extCommand(name: String, args: String?): ActionResult {
        val text = "/" + name + (args?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")
        return host.engine.send(text, SendMode.STEER).toResult()
    }

    private suspend fun packages(args: String?, title: String): ActionResult {
        val a = args?.trim().orEmpty()
        return when {
            a.isEmpty() -> guest("pi list", title)
            // install <源> / remove <源> / update [源] / config：pi 的包管理 CLI；完成后重新加载资源。
            a.split(' ').first() in PACKAGE_VERBS -> guest("pi $a", title, timeoutMs = 600_000).also { if (it is ActionResult.LocalBlock) runCatching { ops.reload() } }
            else -> ActionResult.Toast("包管理只支持：${PACKAGE_VERBS.joinToString("、")}")
        }
    }

    private suspend fun setDefaultModel(args: String?): ActionResult {
        val c = host.chat.value
        val (provider, model) = args?.takeIf { it.contains('/') }?.split('/', limit = 2)?.let { it[0] to it[1] }
            ?: (c.model?.let { it.provider to it.id } ?: return ActionResult.Toast("当前没有模型可设为默认"))
        val s = host.settings
        val r1 = s.set("defaultProvider", JsonPrimitive(provider))
        val r2 = s.set("defaultModel", JsonPrimitive(model))
        val r3 = c.thinkingLevel?.takeIf { args == null }?.let { s.set("defaultThinkingLevel", JsonPrimitive(it)) } ?: Outcome.Ok(Unit)
        val err = listOf(r1, r2, r3).filterIsInstance<Outcome.Err>().firstOrNull()
        return err?.let { ActionResult.Toast("设为默认失败：${it.message}") } ?: ActionResult.Toast("已设为默认：$provider/$model")
    }

    /**
     * `/share`（pi 1.5.0 `session-share.js`）：`sessionShareDestination` 为 auto / gist 时，导出 HTML 后用
     * `gh gist create --public=false` 上传，链接按 pi 的查看器格式（`PI_SHARE_VIEWER_URL` 默认 https://pi.dev/session/#gistId）。
     * Radius 上传需要 pi 交互模式里的 Radius 登录凭据，RPC 下拿不到：destination = radius 时明确提示，auto 时退到 gist。
     */
    private suspend fun share(): ActionResult {
        val dest = (host.settings.values.value["sessionShareDestination"] as? JsonPrimitive)?.content ?: "auto"
        if (dest == "radius") return ActionResult.Toast("分享到 Radius 需要 pi 交互模式的 Radius 登录，pi-go 暂不支持；可在 pi 设置 › 会话 把分享目标改成 gist 或 auto")
        when (val auth = ops.runGuest("gh auth status")) {
            is Outcome.Err -> return ActionResult.Toast("未安装 GitHub CLI（gh）：在终端形态运行 !apt install gh 后再试")
            is Outcome.Ok -> if (auth.value.exitCode != 0) return ActionResult.Toast("GitHub CLI 未登录：在终端形态运行 !gh auth login 后再试")
        }
        val html = when (val r = host.extras.exportHtml(SHARE_TMP)) { is Outcome.Ok -> r.value; is Outcome.Err -> return ActionResult.Toast("导出会话失败：${r.message}") }
        return when (val r = ops.runGuest("gh gist create --public=false ${shellQuote(html)}; rm -f ${shellQuote(html)}")) {
            is Outcome.Err -> ActionResult.Toast("创建 gist 失败：${r.message}")
            is Outcome.Ok -> {
                val url = r.value.output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("https://gist.github.com/") }
                if (r.value.exitCode != 0 || url == null) ActionResult.LocalBlock("分享失败", r.value.output)
                else {
                    val gistId = url.substringAfterLast('/')
                    val viewer = "https://pi.dev/session/#$gistId"
                    host.requestClipboard(viewer)
                    ActionResult.LocalBlock("分享链接", "$viewer\n（gist：$url，链接已复制）")
                }
            }
        }
    }

    /**
     * `/bug`：pi 1.5.0 的报告上传走 Radius 网关（需交互模式登录），RPC 下不可用；按 pi「Export as Zip」的退路，
     * 在本机生成报告（pi 版本、系统、模型、已加载扩展命令、settings.json、可选本会话 JSONL 路径），作为文件分享。
     * args：问题描述；为空时先弹表单。
     */
    private suspend fun bugReport(args: String?): ActionResult {
        if (args == null) return ActionResult.Overlay(OverlayModel.Form("overlay:bug", "报告问题",
            listOf(FormField("hint", "出了什么问题？", io.github.zr987459.pigo.contract.settings.PiSettingType.Str,
                "报告只在本机生成，不会自动上传；包含 pi 版本、模型与服务商（不含 Key）、扩展、设置和本会话文件路径")),
            submitLabel = "生成报告"))
        val c = host.chat.value
        val version = (ops.runGuest("pi --version") as? Outcome.Ok)?.value?.output?.trim().orEmpty()
        val exts = (host.extras.commands(refresh = false) as? Outcome.Ok)?.value?.filter { it.source == CommandSource.EXTENSION }?.joinToString(", ") { it.name }.orEmpty()
        val text = buildString {
            appendLine("# pi 问题报告（pi-go 本地生成）")
            appendLine(); appendLine("描述：${args.ifBlank { "（未填写）" }}")
            appendLine("pi：$version")
            appendLine("系统：Android（pi-go）")
            appendLine("模型：${c.model?.let { "${it.provider}/${it.id}" } ?: "—"}  思考：${c.thinkingLevel ?: "—"}")
            appendLine("扩展命令：${exts.ifBlank { "—" }}")
            appendLine("会话文件：${c.sessionFile ?: "—"}")
            appendLine(); appendLine("## settings.json")
            host.settings.values.value.forEach { (k, v) -> appendLine("$k = $v") }
        }
        return when (val r = ops.writeReport("pi-bug-${System.currentTimeMillis()}.md", text)) {
            is Outcome.Ok -> ActionResult.LocalBlock("问题报告已生成", "已保存到 ${r.value}\npi 的上传通道需要 Radius 登录，pi-go 只生成本地报告；可在会话菜单 › 导出 里一起分享会话文件。")
            is Outcome.Err -> ActionResult.Toast("生成报告失败：${r.message}")
        }
    }

    private suspend fun commandsOverlay(id: String, title: String, source: CommandSource): ActionResult =
        when (val r = host.extras.commands(refresh = false)) {
            is Outcome.Ok -> ActionResult.Overlay(OverlayModel.SelectList("overlay:$id", title, listOf(SelectGroup(null,
                r.value.filter { it.source == source && !it.name.startsWith("pigo:") }.map { SelectItem(it.name, "/" + it.name, it.description) }))))
            is Outcome.Err -> ActionResult.Toast(r.message)
        }

    /** 分支树当前的视图状态（筛选、折叠、显示时间）；`/tree` 重新打开时筛选回到 `treeFilterMode`。 */
    private data class TreeView(val filter: TreeFilter? = null, val collapsed: Set<String> = emptySet(), val showTime: Boolean = false)

    private var treeView = TreeView()

    private fun nextFilter(f: TreeFilter?): TreeFilter = TreeFilter.entries[((f ?: TreeFilter.DEFAULT).ordinal + 1) % TreeFilter.entries.size]

    private suspend fun treeOverlay(view: TreeView): ActionResult = when (val r = host.extras.tree()) {
        is Outcome.Ok -> {
            val filter = view.filter ?: settingFilter()
            treeView = view.copy(filter = filter)
            ActionResult.Overlay(TreeShape.overlay(r.value, filter, view.collapsed.let { if (TreeShape.ALL in it) TreeShape.allParents(r.value) else it }, view.showTime))
        }
        is Outcome.Err -> ActionResult.Toast("读取分支树失败：${r.message}")
    }

    private fun settingFilter(): TreeFilter {
        val v = (host.settings.values.value["treeFilterMode"] as? JsonPrimitive)?.content
        return TreeFilter.entries.firstOrNull { it.piName == v } ?: TreeFilter.DEFAULT
    }

    /**
     * 选中树节点：args = "<entryId>"（按 `branchSummary.skipPrompt` 决定是否先问要不要摘要）或
     * "<entryId> summarize" / "<entryId> nosummary"。原地切换经 [SessionOps.treeNavigate]；
     * 选中用户消息时 pi 把原文放回编辑器 → 写进共享草稿（草稿非空时进「已撤回」芯片，不覆盖）。
     */
    private suspend fun treeNav(args: String): ActionResult {
        val parts = args.trim().split(' ')
        val entryId = parts[0]
        val summarize = when (parts.getOrNull(1)) {
            "summarize" -> true
            "nosummary" -> false
            else -> {
                val skip = (host.settings.values.value["branchSummary.skipPrompt"] as? JsonPrimitive)?.content == "true"
                if (skip) false else return ActionResult.Overlay(OverlayModel.Confirm("overlay:tree.summarize:$entryId", "摘要离开的分支？",
                    "切换前可以让模型为离开的分支写一段摘要，放进新位置的上下文里。", confirmLabel = "摘要后切换"))
            }
        }
        return when (val r = ops.treeNavigate(entryId, summarize)) {
            is Outcome.Ok -> {
                val nav = r.value
                if (nav.cancelled) return ActionResult.Toast("已取消切换")
                nav.editorText?.takeIf { it.isNotBlank() }?.let { t -> host.draft.value = QueueRecall.merge(host.draft.value, listOf(t)) }
                ActionResult.Toast(when {
                    nav.viaFork -> "桥接扩展不可用：已从该记录分叉出新会话（原会话不变）"
                    nav.unconfirmed -> "切换未确认，已按引擎当前状态刷新"
                    else -> "已切换到所选位置"
                })
            }
            is Outcome.Err -> ActionResult.Toast("切换失败：${r.message}")
        }
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

    companion object {
        const val CONFIRM_PREFIX = "confirm "
        private const val SHARE_TMP = "/tmp/pigo-share.html"
        private val PACKAGE_VERBS = setOf("install", "remove", "update", "list", "config")
        private val THINKING_ADVANCED_KEYS = setOf("thinkingBudgets", "modelThinkingLevels")
        /** 「全部折叠」的标记集合（打开时换成全部有子节点的记录）。 */
        private val ALL_COLLAPSED: Set<String> = setOf(TreeShape.ALL)
        /** pi 包内的 CHANGELOG.md（全局安装位置由 npm 决定）。 */
        const val CHANGELOG_CMD = "cat \"$(npm root -g)/@earendil-works/pi-coding-agent/CHANGELOG.md\""
    }

    private fun modelFallback(): ActionResult = ActionResult.Toast("模型面板尚未接入（设置 › 模型）")

    private fun helpText(): String = all.groupBy { it.group }.entries.joinToString("\n\n") { (g, specs) ->
        g.name + "\n" + specs.filter { it.ui !is UiEntry.Explain }.joinToString("\n") { s -> "  " + s.title + (s.slash.firstOrNull()?.let { "  /$it" } ?: "") }
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}

/** 一次性提问的命令（`bash` + `excludeFromContext` 跑 `pi -p`，不进当前会话，DESIGN-r3 §2.1）。 */
object OneShot {
    const val JSON_PREFIX = "--json "
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
