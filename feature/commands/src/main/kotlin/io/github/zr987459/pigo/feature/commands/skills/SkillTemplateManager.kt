package io.github.zr987459.pigo.feature.commands.skills

import io.github.zr987459.pigo.contract.CommandSource
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SlashCommand
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** 技能 / 提示模板管理页的一项（C4）。 */
data class ResourceItem(
    /** 命令名（技能为 `skill:<名>`）；已停用的项从路径推出。 */
    val name: String,
    val kind: CommandSource,
    val description: String?,
    /** 容器内路径；扩展自带的技能可能没有。 */
    val path: String?,
    val scope: String?,
    val enabled: Boolean,
    /** 只有用户级（`~/.pi/agent/...`）的才能在这里停用/编辑；项目级和包里的只读。 */
    val editable: Boolean,
)

/**
 * 技能与提示模板管理（C4）：
 * - 列表 = `get_commands` 里的技能与模板 ∪ 用户 `settings.json` 里 `-路径` 排除掉的（显示为已停用）；
 * - 查看：宿主直接读容器文件（最多 [MAX_READ] 字节）；
 * - 启用/停用：改用户 `settings.json` 的 `skills` / `prompts` 数组里的 `-<绝对路径>` 项（pi 1.5.0 docs/settings.md「Resources」），
 *   只动这两个键，其他键原样保留；
 * - 新建模板：写 `~/.pi/agent/prompts/<名>.md`（docs/prompt-templates.md 的格式，带 description 头）。
 * 改动在**新会话或重启引擎后**生效（RPC 没有 /reload），界面要提示。
 */
class SkillTemplateManager(
    /** 容器路径 → 宿主文件（`runtime.hostFile`）。 */
    private val hostFile: (String) -> File,
    private val agentDir: String = "/root/.pi/agent",
) {
    private val json = Json { prettyPrint = true }

    fun list(commands: List<SlashCommand>): List<ResourceItem> {
        val settings = readSettings()
        val disabledSkills = excluded(settings, "skills")
        val disabledPrompts = excluded(settings, "prompts")
        val live = commands.filter { it.source == CommandSource.SKILL || it.source == CommandSource.PROMPT }.map { c ->
            ResourceItem(
                name = c.name, kind = c.source, description = c.description, path = c.path, scope = c.scope,
                enabled = true, editable = isUserPath(c.path),
            )
        }
        val livePaths = live.mapNotNull { it.path }.toSet()
        val off = disabledSkills.filter { it !in livePaths }.map { p -> disabledItem(p, CommandSource.SKILL) } +
            disabledPrompts.filter { it !in livePaths }.map { p -> disabledItem(p, CommandSource.PROMPT) }
        return (live + off).sortedWith(compareBy<ResourceItem> { it.kind.ordinal }.thenBy { it.name })
    }

    private fun disabledItem(path: String, kind: CommandSource): ResourceItem {
        val file = path.trimEnd('/')
        val name = when (kind) {
            CommandSource.SKILL -> "skill:" + (if (file.endsWith("/SKILL.md")) file.removeSuffix("/SKILL.md").substringAfterLast('/') else file.substringAfterLast('/').removeSuffix(".md"))
            else -> file.substringAfterLast('/').removeSuffix(".md")
        }
        return ResourceItem(name, kind, null, path, "user", enabled = false, editable = true)
    }

    fun read(guestPath: String): Outcome<String> {
        val f = hostFile(guestPath)
        if (!f.isFile) return Outcome.Err(ErrCode.UNKNOWN, "文件不存在：$guestPath")
        return try {
            val bytes = f.inputStream().use { it.readNBytes(MAX_READ + 1) }
            val text = String(bytes, 0, minOf(bytes.size, MAX_READ), Charsets.UTF_8)
            Outcome.Ok(if (bytes.size > MAX_READ) "$text\n\n…（文件太大，只显示前 ${MAX_READ / 1024} KB）" else text)
        } catch (e: Exception) {
            Outcome.Err(ErrCode.UNKNOWN, "读取失败：${e.message}", e)
        }
    }

    fun setEnabled(item: ResourceItem, enabled: Boolean): Outcome<Unit> {
        val path = item.path ?: return Outcome.Err(ErrCode.UNKNOWN, "这一项没有文件路径，不能停用")
        if (!item.editable) return Outcome.Err(ErrCode.UNKNOWN, "只能停用用户目录（$agentDir）里的技能和模板")
        val key = when (item.kind) {
            CommandSource.SKILL -> "skills"
            CommandSource.PROMPT -> "prompts"
            else -> return Outcome.Err(ErrCode.UNKNOWN, "只能停用技能和模板")
        }
        return try {
            val s = readSettings()
            val arr = (s[key] as? JsonArray)?.toMutableList() ?: mutableListOf()
            arr.removeAll { (it as? JsonPrimitive)?.contentOrNull == "-$path" }
            if (!enabled) arr += JsonPrimitive("-$path")
            val next = JsonObject(s.toMutableMap().apply { if (arr.isEmpty()) remove(key) else put(key, JsonArray(arr)) })
            writeSettings(next)
            Outcome.Ok(Unit)
        } catch (e: Exception) {
            Outcome.Err(ErrCode.UNKNOWN, "写 settings.json 失败：${e.message}", e)
        }
    }

    /** 新建用户级提示模板。返回容器内路径。 */
    fun createTemplate(name: String, description: String, body: String, overwrite: Boolean = false): Outcome<String> {
        val n = name.trim().removePrefix("/")
        if (!NAME.matches(n)) return Outcome.Err(ErrCode.UNKNOWN, "名字只能用字母、数字、点、横线和下划线，且不能以点开头")
        if (body.isBlank()) return Outcome.Err(ErrCode.UNKNOWN, "模板内容不能为空")
        val guest = "$agentDir/prompts/$n.md"
        val f = hostFile(guest)
        if (f.exists() && !overwrite) return Outcome.Err(ErrCode.UNKNOWN, "已经有同名模板 /$n")
        return try {
            f.parentFile?.mkdirs()
            f.writeText(templateText(description, body))
            Outcome.Ok(guest)
        } catch (e: Exception) {
            Outcome.Err(ErrCode.DISK_FULL, "写入失败：${e.message}", e)
        }
    }

    private fun isUserPath(path: String?): Boolean = path != null && path.startsWith("$agentDir/")

    private fun settingsFile() = hostFile("$agentDir/settings.json")

    private fun readSettings(): JsonObject {
        val f = settingsFile()
        if (!f.isFile) return JsonObject(emptyMap())
        return try { Json.parseToJsonElement(f.readText()).jsonObject } catch (_: Exception) { throw IllegalStateException("settings.json 不是合法的 JSON，未修改") }
    }

    private fun writeSettings(o: JsonObject) {
        val f = settingsFile()
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".pigo.tmp")
        tmp.writeText(json.encodeToString(JsonElement.serializer(), o) + "\n")
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun excluded(s: JsonObject, key: String): List<String> =
        (s[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .filter { it.startsWith("-") && it.length > 1 }.map { it.substring(1) }

    companion object {
        const val MAX_READ = 512 * 1024
        val NAME = Regex("^[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}$")
        const val RELOAD_HINT = "改动在新会话或重启引擎后生效"

        fun templateText(description: String, body: String): String = buildString {
            val d = description.trim().replace("\n", " ")
            if (d.isNotEmpty()) {
                append("---\n")
                append("description: ").append(yamlString(d)).append('\n')
                append("---\n")
            }
            append(body.trimEnd()).append('\n')
        }

        /** 带引号的 YAML 字符串（转义 `\` 与 `"`）。 */
        fun yamlString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
