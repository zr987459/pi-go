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

    /** 对象 / 数组类型的键：flatten 不往里展开，整体按 JSON 结构读写（审查第 1 轮 I5）。 */
    private val structuralKeys: Set<String> = schema.specs.filter { PiSettingsCodec.isStructural(it) }.map { it.key }.toSet()

    override suspend fun refresh(): Outcome<Unit> = withContext(Dispatchers.IO) {
        runCatching { _values.value = SettingsJson.flatten(SettingsJson.read(file), stopAt = structuralKeys) }
            .fold({ Outcome.Ok(Unit) }, { Outcome.Err(ErrCode.UNKNOWN, "读取 settings.json 失败：${it.message}") })
    }

    override suspend fun set(key: String, value: JsonElement?): Outcome<Unit> {
        if (isSideKey(key)) return sideKeys(key, value)
        val spec = schema.specs.firstOrNull { it.key == key }
            ?: return Outcome.Err(ErrCode.RPC_REJECTED, "未知的 pi 设置键：$key")
        var changed = false
        val r = withContext(Dispatchers.IO) {
            runCatching {
                SettingsJson.locked(file) {
                    val root = SettingsJson.read(file)
                    val current = SettingsJson.get(root, key.split('.'))
                    when (val enc = PiSettingsCodec.encode(spec, value, current)) {
                        is PiSettingsCodec.Encoded.Invalid -> throw IllegalArgumentException(enc.reason)
                        PiSettingsCodec.Encoded.NoChange -> Unit
                        is PiSettingsCodec.Encoded.Value -> if (enc.value != current) {
                            val next = SettingsJson.put(root, key.split('.'), enc.value)
                            SettingsJson.write(file, next)
                            changed = true
                        }
                    }
                    _values.value = SettingsJson.flatten(SettingsJson.read(file), stopAt = structuralKeys)
                }
            }
        }
        return r.fold({
            if (changed) runCatching { reload() }
            Outcome.Ok(Unit)
        }, {
            if (it is IllegalArgumentException) Outcome.Err(ErrCode.RPC_REJECTED, "${spec.label}：${it.message}")
            else Outcome.Err(ErrCode.UNKNOWN, "写入 settings.json 失败：${it.message}")
        })
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

    /** 读 [path] 指向的值；不存在为 null。 */
    fun get(root: JsonObject, path: List<String>): JsonElement? {
        var cur: JsonElement = root
        for (seg in path) cur = (cur as? JsonObject)?.get(seg) ?: return null
        return cur
    }

    /**
     * 嵌套对象展开成点分路径（叶子为非对象值）；`retry.provider.*` 这类分组对象也展开。
     * [stopAt] 里的键（`modelThinkingLevels` 等对象型设置）整体保留为一个值，不往里展开。
     */
    fun flatten(obj: JsonObject, prefix: String = "", stopAt: Set<String> = emptySet()): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in obj) {
            val key = if (prefix.isEmpty()) k else "$prefix.$k"
            if (v is JsonObject && v.isNotEmpty() && key !in stopAt) out.putAll(flatten(v, key, stopAt)) else out[key] = v
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

/**
 * 表单文本 ↔ settings.json 值（审查第 1 轮 I5）。两种形态的表单都提交字符串，这里按 schema 转成 pi 1.5.0 要的类型：
 * 布尔、数字、枚举（`"true"`/`"false"` 转布尔、`"0"`/`"1"` 转数字）、「数字或关键字」、字符串列表、JSON 对象 / 数组。
 * 对象 / 数组键留空 = 不改，非法 JSON 或类型不对直接拒绝，**绝不用字符串覆盖用户原有的对象**。
 */
object PiSettingsCodec {
    sealed interface Encoded {
        data object NoChange : Encoded
        data class Value(val value: JsonElement?) : Encoded
        data class Invalid(val reason: String) : Encoded
    }

    fun isStructural(spec: PiSettingSpec) = spec.help == PiSettingsSchemaV150.OBJECT_HELP || spec.help == PiSettingsSchemaV150.ARRAY_HELP
    private fun isArray(spec: PiSettingSpec) = spec.help == PiSettingsSchemaV150.ARRAY_HELP
    private fun isMixed(spec: PiSettingSpec) = spec.help == PiSettingsSchemaV150.MIXED_HELP

    fun encode(spec: PiSettingSpec, value: JsonElement?, current: JsonElement?): Encoded {
        if (value == null) return if (isStructural(spec) && current != null) Encoded.Invalid("对象型设置不能整体清空，请编辑 JSON") else Encoded.Value(null)
        if (isStructural(spec)) {
            val parsed = when {
                value is JsonPrimitive && value.isString -> {
                    val t = value.content.trim()
                    if (t.isEmpty()) return Encoded.NoChange
                    runCatching { Json.parseToJsonElement(t) }.getOrElse { return Encoded.Invalid("不是合法的 JSON") }
                }
                else -> value
            }
            val ok = if (isArray(spec)) parsed is kotlinx.serialization.json.JsonArray else parsed is JsonObject
            return if (ok) Encoded.Value(parsed) else Encoded.Invalid(if (isArray(spec)) "需要 JSON 数组" else "需要 JSON 对象")
        }
        if (value !is JsonPrimitive || !value.isString) return Encoded.Value(value)
        val t = value.content
        val type = spec.type
        // 表单把未设置的键显示成默认值；原样提交回来时不写文件（只改用户真正改过的键）。
        if (current == null && spec.default != null && t == display(spec, spec.default)) return Encoded.NoChange
        if (t.isBlank() && type !is PiSettingType.Str) return if (current == null) Encoded.NoChange else Encoded.Value(null)
        return when (type) {
            PiSettingType.Bool -> t.trim().toBooleanStrictOrNull()?.let { Encoded.Value(JsonPrimitive(it)) } ?: Encoded.Invalid("需要 true 或 false")
            is PiSettingType.Number -> t.trim().let { n -> n.toLongOrNull()?.let { Encoded.Value(JsonPrimitive(it)) } ?: n.toDoubleOrNull()?.let { Encoded.Value(JsonPrimitive(it)) } } ?: Encoded.Invalid("需要数字")
            is PiSettingType.Enum -> if (t !in type.values) Encoded.Invalid("可选值：${type.values.joinToString("、")}") else Encoded.Value(scalar(t))
            PiSettingType.StrList -> Encoded.Value(kotlinx.serialization.json.JsonArray(t.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.map { JsonPrimitive(it) }))
            PiSettingType.Str -> when {
                t.isEmpty() -> if (current == null) Encoded.NoChange else Encoded.Value(null)
                isMixed(spec) -> Encoded.Value(scalar(t.trim()))
                else -> Encoded.Value(JsonPrimitive(t))
            }
        }
    }

    /** 枚举 / 混合类型的文字转回 JSON：布尔、整数、其余为字符串。 */
    private fun scalar(t: String): JsonElement = t.toBooleanStrictOrNull()?.let { JsonPrimitive(it) } ?: t.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(t)

    /** 表单显示用：字符串原样，其余（布尔、数字、对象、数组）用 JSON 文本；字符串列表每行一个。 */
    fun display(spec: PiSettingSpec, v: JsonElement?): String = when {
        v == null -> ""
        spec.type == PiSettingType.StrList && v is kotlinx.serialization.json.JsonArray -> v.joinToString("\n") { (it as? JsonPrimitive)?.content ?: it.toString() }
        v is JsonPrimitive -> v.content
        else -> v.toString()
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
            spec.key to PiSettingsCodec.display(spec, v)
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
    private fun e(group: String, key: String, label: String, values: List<String>, def: String? = null, help: String? = null) =
        PiSettingSpec(key, group, label, PiSettingType.Enum(values), def?.let { JsonPrimitive(it) }, help, featureRef = "§8 $group")
    /** 「数字或关键字」混合类型（如 `"auto"` | number、number | `"disabled"`）：文本输入，保存时按值转成数字或字符串。 */
    private fun x(group: String, key: String, label: String, def: String? = null, help: String? = null) =
        PiSettingSpec(key, group, label, PiSettingType.Str, def?.let { JsonPrimitive(it) }, help ?: MIXED_HELP, featureRef = "§8 $group")
    /** 对象 / 数组类型的键：按 JSON 结构编辑，保存前校验，空白 = 不改（绝不用字符串覆盖原对象）。 */
    private fun o(group: String, key: String, label: String, array: Boolean = false) =
        PiSettingSpec(key, group, label, PiSettingType.Str, null, if (array) ARRAY_HELP else OBJECT_HELP, featureRef = "§8 $group")
    private fun l(group: String, key: String, label: String) =
        PiSettingSpec(key, group, label, PiSettingType.StrList, featureRef = "§8 $group")

    private const val NOT_MOBILE = "手机上不适用（只读）"
    private const val TERM_ONLY = "仅终端形态"
    const val OBJECT_HELP = "JSON 对象；保存前校验，留空不改"
    const val ARRAY_HELP = "JSON 数组；保存前校验，留空不改"
    const val MIXED_HELP = "数字或关键字"

    override val specs: List<PiSettingSpec> = listOf(
        s("模型", "defaultProvider", "默认服务商"),
        s("模型", "defaultModel", "默认模型"),
        e("模型", "defaultThinkingLevel", "默认思考强度", listOf("off", "minimal", "low", "medium", "high", "xhigh", "max"), "medium"),
        o("模型", "modelThinkingLevels", "每模型思考档（provider/modelId → 档位）"),
        o("模型", "thinkingBudgets", "思考预算（minimal/low/medium/high → token）"),
        l("模型", "enabledModels", "常用模型"),
        b("显示", "hideThinkingBlock", "隐藏思考块", false),
        b("显示", "showCacheMissNotices", "显示缓存未命中提示", false),
        e("显示", "quietStartup", "安静启动", listOf("false", "true", "header"), "false"),
        s("显示", "theme", "主题（终端形态）", "system"),
        e("缓存", "cacheWarming", "缓存预热", listOf("off", "streaming", "idle"), "streaming"),
        e("队列", "steeringMode", "引导消息投递", listOf("all", "one-at-a-time"), "one-at-a-time"),
        e("队列", "followUpMode", "排队消息投递", listOf("all", "one-at-a-time"), "one-at-a-time"),
        s("编辑器", "externalEditor", "外部编辑器", help = NOT_MOBILE),
        e("编辑器", "doubleEscapeAction", "双击 Esc", listOf("tree", "fork", "none"), "tree"),
        n("编辑器", "autocompleteMaxVisible", "补全最多显示", 5),
        n("编辑器", "editorPaddingX", "编辑器左右留白", 0),
        b("编辑器", "showHardwareCursor", "硬件光标", false, help = NOT_MOBILE),
        e("树", "treeFilterMode", "分支树筛选", listOf("default", "no-tools", "user-only", "labeled-only", "all"), "default"),
        n("树", "branchSummary.reserveTokens", "分支摘要预留 token", 16384),
        b("树", "branchSummary.skipPrompt", "分支摘要不再询问", false),
        e("信任", "defaultProjectTrust", "项目信任默认", listOf("ask", "always", "never"), "ask"),
        l("工具", "defaultTools", "默认工具"),
        e("工具", "codemode.mode", "codemode 模式", listOf("on", "only"), "on"),
        n("工具", "codemode.inlineBudget", "codemode 内联预算", 3000),
        s("会话", "sessionDir", "会话目录"),
        e("会话", "sessionShareDestination", "分享目标", listOf("auto", "gist", "radius"), "auto"),
        b("压缩", "compaction.enabled", "自动压缩", true),
        n("压缩", "compaction.reserveTokens", "压缩预留 token", 16384),
        n("压缩", "compaction.keepRecentTokens", "保留最近 token", 20000),
        o("压缩", "compaction.modelOverrides", "按模型的压缩 token 设置（provider/modelId → 对象）"),
        e("全屏 TUI", "tuiMode", "TUI 模式", listOf("regular", "fullscreen"), "fullscreen", help = TERM_ONLY),
        e("全屏 TUI", "fullscreenExitOutput", "退出时输出", listOf("transcript", "resume-hint"), help = TERM_ONLY),
        e("全屏 TUI", "fullscreenScrollbar", "滚动条", listOf("auto", "always", "hidden"), "auto", help = TERM_ONLY),
        b("全屏 TUI", "fullscreenCopyOnSelect", "选中即复制", true, help = TERM_ONLY),
        x("全屏 TUI", "fullscreenWheelScrollLines", "滚轮行数（auto 或数字，仅终端形态）", "auto"),
        e("全屏 TUI", "outputPad", "输出留白", listOf("0", "1"), help = TERM_ONLY),
        b("终端", "terminal.showImages", "内联图片", true, help = TERM_ONLY),
        n("终端", "terminal.imageWidthCells", "图片宽度（列）", 60),
        b("终端", "terminal.clearOnShrink", "缩小时清屏", false, help = TERM_ONLY),
        b("终端", "terminal.showTerminalProgress", "终端进度", false, help = TERM_ONLY),
        e("终端", "terminal.hyperlinks", "超链接", listOf("true", "false", "auto"), help = TERM_ONLY),
        e("终端", "terminal.images", "图片协议", listOf("auto", "kitty", "iterm2", "false"), "auto", help = TERM_ONLY),
        e("终端", "terminal.trueColor", "真彩色", listOf("true", "false", "auto"), help = TERM_ONLY),
        b("图片", "images.autoResize", "自动缩放（最大 2000×2000）", true),
        b("图片", "images.blockImages", "不发送图片", false),
        s("Markdown", "markdown.codeBlockIndent", "代码块缩进前缀", "  "),
        e("Markdown", "markdown.mermaid", "Mermaid", listOf("off", "streaming", "final"), "streaming"),
        e("网络", "transport", "传输", listOf("auto", "sse", "websocket", "websocket-cached"), "auto"),
        s("网络", "httpProxy", "HTTP 代理"),
        x("网络", "httpIdleTimeoutMs", "HTTP 空闲超时（毫秒或 disabled）", "300000"),
        x("网络", "websocketConnectTimeoutMs", "WebSocket 连接超时（毫秒或 disabled）", "15000"),
        b("重试", "retry.enabled", "自动重试", true),
        n("重试", "retry.maxRetries", "最多重试次数", 3),
        n("重试", "retry.baseDelayMs", "首次重试间隔（毫秒）", 2000),
        n("重试", "retry.maxAgentDelayMs", "最长等待（毫秒）", 60000),
        n("重试", "retry.provider.timeoutMs", "服务商请求超时（毫秒）"),
        n("重试", "retry.provider.maxRetries", "服务商层重试次数", 0),
        n("重试", "retry.provider.maxRetryDelayMs", "服务商建议等待上限（毫秒，0 = 不限）", 60000),
        s("Shell", "shellPath", "Shell 路径"),
        s("Shell", "shellCommandPrefix", "命令前缀"),
        l("包与资源", "npmCommand", "npm 命令（每行一个参数）"),
        l("包与资源", "selfUpdateCommand", "自更新命令（每行一个参数）"),
        o("包与资源", "packages", "包（JSON 数组，可含对象）", array = true),
        l("包与资源", "extensions", "扩展"),
        l("包与资源", "skills", "技能"),
        l("包与资源", "prompts", "提示模板"),
        l("包与资源", "themes", "主题"),
        b("包与资源", "enableSkillCommands", "技能斜杠命令", true),
        b("其他", "collapseChangelog", "折叠更新日志", false),
        b("其他", "enableInstallTelemetry", "安装遥测", true),
        b("其他", "enableAnalytics", "使用统计", false),
        b("其他", "warnings.anthropicExtraUsage", "Anthropic 额外用量提醒", true),
        // 进程环境变量类：改了要重启引擎（§5.5，唯一允许重启的路径）。
        PiSettingSpec("env.PI_OFFLINE", "网络", "离线启动（PI_OFFLINE）", PiSettingType.Bool, restartsEngine = true, featureRef = "§8.1 进程"),
        PiSettingSpec("env.PI_TELEMETRY", "其他", "遥测（PI_TELEMETRY）", PiSettingType.Bool, restartsEngine = true, featureRef = "§8.1 进程"),
        // 上下文文件与系统提示：文件型（SYSTEM.md / APPEND_SYSTEM.md / AGENTS.md），由 Kotlin 直接读写。
        PiSettingSpec("file.SYSTEM.md", "上下文", "系统提示（SYSTEM.md）", PiSettingType.Str, featureRef = "§1.2 --system-prompt"),
        PiSettingSpec("file.APPEND_SYSTEM.md", "上下文", "追加系统提示（APPEND_SYSTEM.md）", PiSettingType.Str, featureRef = "§1.2 --append-system-prompt"),
        PiSettingSpec("flag.noContextFiles", "上下文", "不加载 AGENTS.md / CLAUDE.md（-nc）", PiSettingType.Bool, restartsEngine = true, featureRef = "§1.2 -nc"),
    )
}
