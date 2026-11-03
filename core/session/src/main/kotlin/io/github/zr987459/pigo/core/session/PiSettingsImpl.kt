package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.session.FormField
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.SelectGroup
import io.github.zr987459.pigo.contract.session.SelectItem
import io.github.zr987459.pigo.contract.settings.PiSettingSpec
import io.github.zr987459.pigo.contract.settings.PiSettingType
import io.github.zr987459.pigo.contract.settings.PiSettings
import io.github.zr987459.pigo.contract.settings.PiSettingsSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.RandomAccessFile

/**
 * pi 的 `settings.json`（DESIGN-r3 §5.8，清单 §8）。
 *
 * - 读-改-写在文件锁里（与 `AuthJsonLock` 同款：同目录 `settings.json.lock` 上的 `FileChannel.lock`）；
 * - **只改目标键**：点分路径逐层改，未知键与其他键原样保留、顺序不变；
 * - 写完调 [reload]（`/pigo:reload`），让运行中的 pi 读到新值；
 * - [set] 的值为 null = 删除该键（回到 pi 默认值）。
 */
class PiSettingsImpl(
    private val file: File,
    private val reload: suspend () -> Unit = {},
    override val schema: PiSettingsSchema = PiSettingsSchemaV150,
    /** `env.*`（进程环境变量，要重启引擎）、`file.*`（SYSTEM.md 等）、`flag.*`（启动参数）不写进 settings.json，交给宿主。 */
    private val sideKeys: suspend (key: String, value: JsonElement?) -> Outcome<Unit> = { k, _ -> Outcome.Err(ErrCode.UNKNOWN, "$k 由 App 处理，尚未接入") },
) : PiSettings {

    private val _values = MutableStateFlow<Map<String, JsonElement>>(emptyMap())
    override val values: StateFlow<Map<String, JsonElement>> = _values.asStateFlow()

    override suspend fun refresh(): Outcome<Unit> = withContext(Dispatchers.IO) {
        runCatching { _values.value = SettingsJson.flatten(SettingsJson.read(file)) }
            .fold({ Outcome.Ok(Unit) }, { Outcome.Err(ErrCode.UNKNOWN, "读取 settings.json 失败：${it.message}") })
    }

    override suspend fun set(key: String, value: JsonElement?): Outcome<Unit> {
        if (isSideKey(key)) return sideKeys(key, value)
        val r = withContext(Dispatchers.IO) {
            runCatching {
                SettingsJson.locked(file) {
                    val root = SettingsJson.read(file)
                    val next = SettingsJson.put(root, key.split('.'), value)
                    SettingsJson.write(file, next)
                    _values.value = SettingsJson.flatten(next)
                }
            }
        }
        return r.fold({
            runCatching { reload() }
            Outcome.Ok(Unit)
        }, { Outcome.Err(ErrCode.UNKNOWN, "写入 settings.json 失败：${it.message}") })
    }
}

internal fun isSideKey(key: String) = key.startsWith("env.") || key.startsWith("file.") || key.startsWith("flag.")

/** settings.json 的纯函数读写（便于快照单测）。 */
object SettingsJson {
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun read(file: File): JsonObject =
        if (!file.exists() || file.length() == 0L) JsonObject(emptyMap()) else Json.parseToJsonElement(file.readText()).jsonObject

    fun write(file: File, obj: JsonObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(pretty.encodeToString(JsonObject.serializer(), obj) + "\n")
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    /** 只改 [path] 指向的键；中间层不存在时创建；[value] 为 null 时删除该键（空的中间层一并去掉）。 */
    fun put(root: JsonObject, path: List<String>, value: JsonElement?): JsonObject {
        val head = path.first()
        val m = LinkedHashMap(root)
        if (path.size == 1) {
            if (value == null) m.remove(head) else m[head] = value
        } else {
            val child = (root[head] as? JsonObject) ?: JsonObject(emptyMap())
            val updated = put(child, path.drop(1), value)
            if (updated.isEmpty() && value == null) m.remove(head) else m[head] = updated
        }
        return JsonObject(m)
    }

    /** 嵌套对象展开成点分路径（叶子为非对象值）；`retry.provider.*` 这类对象值也展开。 */
    fun flatten(obj: JsonObject, prefix: String = ""): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in obj) {
            val key = if (prefix.isEmpty()) k else "$prefix.$k"
            if (v is JsonObject && v.isNotEmpty()) out.putAll(flatten(v, key)) else out[key] = v
        }
        return out
    }

    private val inProcess = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** 进程内互斥（同一 JVM 里 FileChannel.lock 不能重入）+ 跨进程文件锁（pi 也会写 settings.json）。 */
    fun <T> locked(file: File, block: () -> T): T {
        file.parentFile?.mkdirs()
        val mutex = inProcess.computeIfAbsent(file.absolutePath) { Any() }
        synchronized(mutex) {
            RandomAccessFile(File(file.parentFile, file.name + ".lock"), "rw").use { raf ->
                raf.channel.lock().use { return block() }
            }
        }
    }
}

/** 由 schema 生成两种形态共用的设置面板（DESIGN-r3 §2.10）。 */
object PiSettingsForms {
    fun groupOf(actionId: String): String = when (actionId) {
        "settings.session" -> "会话"
        "settings.tools" -> "工具"
        "settings.network" -> "网络"
        "settings.queue" -> "队列"
        "settings.compaction" -> "压缩"
        "settings.retry" -> "重试"
        "appearance" -> "显示"
        "trust" -> "信任"
        "context.system", "settings.contextFiles" -> "上下文"
        else -> actionId
    }

    fun groups(s: PiSettings): OverlayModel.SelectList = OverlayModel.SelectList(
        "overlay:settings", "pi 设置",
        listOf(SelectGroup(null, s.schema.specs.map { it.group }.distinct().map { SelectItem("group:$it", it) })),
    )

    fun group(s: PiSettings, group: String): OverlayModel.Form =
        OverlayModel.Form("overlay:settings:$group", "pi 设置 · $group", fieldsFor(s, group), valuesFor(s, group), submitLabel = "保存")

    fun fieldsFor(s: PiSettings, group: String): List<FormField> =
        s.schema.specs.filter { it.group == group }.map { FormField(it.key, it.label, it.type, it.help) }

    fun valuesFor(s: PiSettings, group: String): Map<String, String> =
        s.schema.specs.filter { it.group == group }.associate { spec ->
            val v = s.values.value[spec.key] ?: spec.default
            spec.key to when (v) { null -> ""; is JsonPrimitive -> v.content; else -> v.toString() }
        }
}

/**
 * pi 1.5.0 `settings.json` 全部键（清单 §8；PiSettingsSchemaCoverageTest 逐键核对）。
 * 「模型」组的键在设置页 › 模型（r3/models）里编辑，这里也列出以便 `/settings` 完整。
 */
object PiSettingsSchemaV150 : PiSettingsSchema {
    private fun b(group: String, key: String, label: String, def: Boolean? = null, help: String? = null) =
        PiSettingSpec(key, group, label, PiSettingType.Bool, def?.let { JsonPrimitive(it) }, help, featureRef = "§8 $group")
    private fun n(group: String, key: String, label: String, def: Number? = null, integer: Boolean = true) =
        PiSettingSpec(key, group, label, PiSettingType.Number(min = 0.0, integer = integer), def?.let { JsonPrimitive(it) }, featureRef = "§8 $group")
    private fun s(group: String, key: String, label: String, def: String? = null, help: String? = null) =
        PiSettingSpec(key, group, label, PiSettingType.Str, def?.let { JsonPrimitive(it) }, help, featureRef = "§8 $group")
    private fun e(group: String, key: String, label: String, values: List<String>, def: String? = null) =
        PiSettingSpec(key, group, label, PiSettingType.Enum(values), def?.let { JsonPrimitive(it) }, featureRef = "§8 $group")
    private fun l(group: String, key: String, label: String) =
        PiSettingSpec(key, group, label, PiSettingType.StrList, featureRef = "§8 $group")

    private const val NOT_MOBILE = "手机上不适用（只读）"
    private const val TERM_ONLY = "仅终端形态"

    override val specs: List<PiSettingSpec> = listOf(
        s("模型", "defaultProvider", "默认服务商"),
        s("模型", "defaultModel", "默认模型"),
        e("模型", "defaultThinkingLevel", "默认思考强度", listOf("off", "minimal", "low", "medium", "high", "xhigh"), "medium"),
        s("模型", "modelThinkingLevels", "每模型思考档"),
        s("模型", "thinkingBudgets", "思考预算"),
        l("模型", "enabledModels", "常用模型"),
        b("显示", "hideThinkingBlock", "隐藏思考块", false),
        b("显示", "showCacheMissNotices", "显示缓存未命中提示", false),
        b("显示", "quietStartup", "安静启动"),
        s("显示", "theme", "主题（终端形态）", "system"),
        e("缓存", "cacheWarming", "缓存预热", listOf("off", "streaming", "always"), "streaming"),
        e("队列", "steeringMode", "引导消息投递", listOf("all", "one-at-a-time"), "one-at-a-time"),
        e("队列", "followUpMode", "排队消息投递", listOf("all", "one-at-a-time"), "one-at-a-time"),
        s("编辑器", "externalEditor", "外部编辑器", help = NOT_MOBILE),
        e("编辑器", "doubleEscapeAction", "双击 Esc", listOf("tree", "fork", "none"), "tree"),
        n("编辑器", "autocompleteMaxVisible", "补全最多显示", 5),
        n("编辑器", "editorPaddingX", "编辑器左右留白", 0),
        b("编辑器", "showHardwareCursor", "硬件光标", help = NOT_MOBILE),
        e("树", "treeFilterMode", "分支树筛选", listOf("default", "no-tools", "user-only", "labeled-only", "all"), "default"),
        n("树", "branchSummary.reserveTokens", "分支摘要预留 token"),
        b("树", "branchSummary.skipPrompt", "分支摘要不再询问"),
        e("信任", "defaultProjectTrust", "项目信任默认", listOf("ask", "trust", "distrust"), "ask"),
        l("工具", "defaultTools", "默认工具"),
        e("工具", "codemode.mode", "codemode 模式", listOf("off", "inline", "tool")),
        n("工具", "codemode.inlineBudget", "codemode 内联预算"),
        s("会话", "sessionDir", "会话目录"),
        e("会话", "sessionShareDestination", "分享目标", listOf("auto", "gist", "viewer"), "auto"),
        b("压缩", "compaction.enabled", "自动压缩", true),
        n("压缩", "compaction.reserveTokens", "压缩预留 token", 16384),
        n("压缩", "compaction.keepRecentTokens", "保留最近 token", 20000),
        s("压缩", "compaction.modelOverrides", "压缩用模型"),
        s("全屏 TUI", "tuiMode", "TUI 模式", help = TERM_ONLY),
        b("全屏 TUI", "fullscreenExitOutput", "退出时输出", help = TERM_ONLY),
        b("全屏 TUI", "fullscreenScrollbar", "滚动条", help = TERM_ONLY),
        b("全屏 TUI", "fullscreenCopyOnSelect", "选中即复制", help = TERM_ONLY),
        n("全屏 TUI", "fullscreenWheelScrollLines", "滚轮行数"),
        n("全屏 TUI", "outputPad", "输出留白"),
        b("终端", "terminal.showImages", "内联图片", help = TERM_ONLY),
        n("终端", "terminal.imageWidthCells", "图片宽度（列）"),
        b("终端", "terminal.clearOnShrink", "缩小时清屏", help = TERM_ONLY),
        b("终端", "terminal.showTerminalProgress", "终端进度", help = TERM_ONLY),
        b("终端", "terminal.hyperlinks", "超链接", help = TERM_ONLY),
        s("终端", "terminal.images", "图片协议", help = TERM_ONLY),
        b("终端", "terminal.trueColor", "真彩色", help = TERM_ONLY),
        b("图片", "images.autoResize", "自动缩放（最大 2000×2000）", true),
        b("图片", "images.blockImages", "不发送图片", false),
        n("Markdown", "markdown.codeBlockIndent", "代码块缩进"),
        e("Markdown", "markdown.mermaid", "Mermaid", listOf("off", "streaming", "final"), "streaming"),
        e("网络", "transport", "传输", listOf("auto", "sse", "websocket"), "auto"),
        s("网络", "httpProxy", "HTTP 代理"),
        n("网络", "httpIdleTimeoutMs", "HTTP 空闲超时（毫秒）", 300000),
        n("网络", "websocketConnectTimeoutMs", "WebSocket 连接超时（毫秒）", 15000),
        b("重试", "retry.enabled", "自动重试", true),
        n("重试", "retry.maxRetries", "最多重试次数", 3),
        n("重试", "retry.baseDelayMs", "首次重试间隔（毫秒）", 2000),
        n("重试", "retry.maxAgentDelayMs", "最长等待（毫秒）"),
        s("重试", "retry.provider", "按服务商重试设置"),
        s("Shell", "shellPath", "Shell 路径"),
        s("Shell", "shellCommandPrefix", "命令前缀"),
        s("包与资源", "npmCommand", "npm 命令"),
        s("包与资源", "selfUpdateCommand", "自更新命令"),
        l("包与资源", "packages", "包"),
        l("包与资源", "extensions", "扩展"),
        l("包与资源", "skills", "技能"),
        l("包与资源", "prompts", "提示模板"),
        l("包与资源", "themes", "主题"),
        b("包与资源", "enableSkillCommands", "技能斜杠命令", true),
        b("其他", "collapseChangelog", "折叠更新日志"),
        b("其他", "enableInstallTelemetry", "安装遥测", true),
        b("其他", "enableAnalytics", "使用统计", false),
        b("其他", "warnings.anthropicExtraUsage", "Anthropic 额外用量提醒"),
        // 进程环境变量类：改了要重启引擎（§5.5，唯一允许重启的路径）。
        PiSettingSpec("env.PI_OFFLINE", "网络", "离线启动（PI_OFFLINE）", PiSettingType.Bool, restartsEngine = true, featureRef = "§8.1 进程"),
        PiSettingSpec("env.PI_TELEMETRY", "其他", "遥测（PI_TELEMETRY）", PiSettingType.Bool, restartsEngine = true, featureRef = "§8.1 进程"),
        // 上下文文件与系统提示：文件型（SYSTEM.md / APPEND_SYSTEM.md / AGENTS.md），由 Kotlin 直接读写。
        PiSettingSpec("file.SYSTEM.md", "上下文", "系统提示（SYSTEM.md）", PiSettingType.Str, featureRef = "§1.2 --system-prompt"),
        PiSettingSpec("file.APPEND_SYSTEM.md", "上下文", "追加系统提示（APPEND_SYSTEM.md）", PiSettingType.Str, featureRef = "§1.2 --append-system-prompt"),
        PiSettingSpec("flag.noContextFiles", "上下文", "不加载 AGENTS.md / CLAUDE.md（-nc）", PiSettingType.Bool, restartsEngine = true, featureRef = "§1.2 -nc"),
    )
}
