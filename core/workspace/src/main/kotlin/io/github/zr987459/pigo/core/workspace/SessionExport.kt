package io.github.zr987459.pigo.core.workspace

import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ExportFormat
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SessionExporter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException

/**
 * 会话导出（feature-gap E1、E2）。
 *
 * - JSONL：原样复制会话文件。
 * - Markdown：宿主直接解析会话文件（不需要引擎），沿 `parentId` 从最后一条往回走，只导出当前分支。
 * - HTML：交给 [exportHtml]（引擎的 `export_html`，只能导出引擎当前打开的会话），再从容器里取出。
 *
 * 输出写到宿主的 [outDir]（应用缓存目录），供系统分享或另存。
 */
class WorkspaceSessionExporter(
    private val workspace: RootfsWorkspace,
    private val outDir: File,
    private val exportHtml: (suspend (outputGuestPath: String) -> Outcome<String>)? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SessionExporter {

    override suspend fun export(sessionGuestPath: String, format: ExportFormat): Outcome<File> = withContext(io) {
        try {
            val src = rawSession(sessionGuestPath)
            outDir.mkdirs()
            val base = FileNames.sanitize(src.nameWithoutExtension, "session")
            when (format) {
                ExportFormat.JSONL -> Outcome.Ok(src.copyTo(File(outDir, "$base.jsonl"), overwrite = true))
                ExportFormat.MARKDOWN -> {
                    val md = SessionMarkdown.render(src.readLines())
                    Outcome.Ok(File(outDir, "$base.md").apply { writeText(md) })
                }
                ExportFormat.HTML -> {
                    val fn = exportHtml ?: return@withContext Outcome.Err(ErrCode.ENGINE_EXIT, "导出 HTML 需要引擎运行")
                    val guestOut = "/root/.pigo/exports/$base.html"
                    workspace.paths().hostFile("/root/.pigo/exports").mkdirs()
                    when (val r = fn(guestOut)) {
                        is Outcome.Err -> r
                        is Outcome.Ok -> {
                            val produced = r.value.ifBlank { guestOut }
                            val host = rawSession(produced, requireJsonl = false)
                            Outcome.Ok(host.copyTo(File(outDir, "$base.html"), overwrite = true))
                        }
                    }
                }
            }
        } catch (e: PathDeniedException) {
            Outcome.Err(ErrCode.UNKNOWN, e.message ?: "无权访问", e)
        } catch (e: IOException) {
            Outcome.Err(ErrCode.UNKNOWN, "导出失败：${e.message}", e)
        }
    }

    /** 会话文件在 `/root/.pi/agent/sessions/` 下，在 `/root` 范围内。 */
    private fun rawSession(guest: String, requireJsonl: Boolean = true): File {
        val f = workspace.paths().hostFile(guest)
        if (!f.isFile) throw IOException("文件不存在：$guest")
        if (requireJsonl && !f.name.endsWith(".jsonl")) throw IOException("不是会话文件：$guest")
        return f
    }
}

/** 会话 JSONL → Markdown。坏行跳过；未知条目忽略。 */
object SessionMarkdown {
    private val json = Json { ignoreUnknownKeys = true }
    private const val TOOL_OUTPUT_LIMIT = 4000

    fun render(lines: List<String>): String {
        val entries = lines.mapNotNull { l -> l.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() } }
        val header = entries.firstOrNull { it.str("type") == "session" }
        val name = entries.lastOrNull { it.str("type") == "session_info" }?.str("name")
        val branch = currentBranch(entries)
        val sb = StringBuilder()
        sb.append("# ").append(name?.takeIf { it.isNotBlank() } ?: "pi 会话").append("\n\n")
        header?.str("timestamp")?.let { sb.append("- 开始时间：").append(it).append('\n') }
        header?.str("cwd")?.let { sb.append("- 工作目录：`").append(it).append("`\n") }
        sb.append('\n')
        for (e in branch) {
            when (e.str("type")) {
                "message" -> (e["message"] as? JsonObject)?.let { appendMessage(sb, it) }
                "compaction" -> {
                    sb.append("> **已压缩上下文**\n")
                    e.str("summary")?.lines()?.forEach { sb.append("> ").append(it).append('\n') }
                    sb.append('\n')
                }
                "branch_summary" -> e.str("summary")?.let { sb.append("> **分支摘要**：").append(it.replace("\n", " ")).append("\n\n") }
                "model_change" -> sb.append("*切换模型：").append(e.str("provider").orEmpty()).append('/').append(e.str("modelId").orEmpty()).append("*\n\n")
            }
        }
        return sb.toString().trimEnd() + "\n"
    }

    /** 沿 parentId 从最后一个有 id 的条目往回走；没有 id 的老格式按文件顺序。 */
    internal fun currentBranch(entries: List<JsonObject>): List<JsonObject> {
        val withId = entries.filter { it.str("id") != null && it.str("type") != "session" }
        if (withId.isEmpty()) return entries
        val byId = withId.associateBy { it.str("id")!! }
        val out = ArrayList<JsonObject>()
        var cur: JsonObject? = withId.last()
        val seen = HashSet<String>()
        while (cur != null) {
            val id = cur.str("id")!!
            if (!seen.add(id)) break
            out += cur
            cur = cur.str("parentId")?.let(byId::get)
        }
        return out.asReversed()
    }

    private fun appendMessage(sb: StringBuilder, m: JsonObject) {
        when (m.str("role")) {
            "user" -> { sb.append("## 用户\n\n"); appendContent(sb, m["content"]); sb.append('\n') }
            "assistant" -> {
                val model = m.str("model")
                sb.append("## 助手").append(if (model != null) "（$model）" else "").append("\n\n")
                appendContent(sb, m["content"])
                m.str("errorMessage")?.let { sb.append("> 出错：").append(it).append("\n\n") }
            }
            "toolResult" -> {
                val err = (m["isError"] as? JsonPrimitive)?.contentOrNull == "true"
                sb.append("<details><summary>").append(if (err) "工具出错" else "工具结果").append("：").append(m.str("toolName").orEmpty()).append("</summary>\n\n")
                sb.append(fence(clip(textOf(m["content"])))).append("\n</details>\n\n")
            }
            "bashExecution" -> {
                sb.append("**$ ").append(m.str("command").orEmpty()).append("**\n\n").append(fence(clip(m.str("output").orEmpty()))).append("\n\n")
            }
            else -> {
                val t = textOf(m["content"])
                if (t.isNotBlank()) sb.append(t).append("\n\n")
            }
        }
    }

    private fun appendContent(sb: StringBuilder, c: JsonElement?) {
        when (c) {
            is JsonPrimitive -> sb.append(c.contentOrNull.orEmpty()).append("\n\n")
            is JsonArray -> for (b in c) {
                val o = b as? JsonObject ?: continue
                when (o.str("type")) {
                    "text" -> sb.append(o.str("text").orEmpty()).append("\n\n")
                    "thinking" -> o.str("thinking")?.takeIf { it.isNotBlank() }?.let {
                        sb.append("<details><summary>思考</summary>\n\n").append(it).append("\n\n</details>\n\n")
                    }
                    "image" -> sb.append("*[图片]*\n\n")
                    "toolCall" -> {
                        sb.append("**调用工具：").append(o.str("name").orEmpty()).append("**\n\n")
                        o["arguments"]?.let { sb.append(fence(clip(it.toString()), "json")).append("\n\n") }
                    }
                }
            }
            else -> {}
        }
    }

    private fun textOf(c: JsonElement?): String = when (c) {
        is JsonPrimitive -> c.contentOrNull.orEmpty()
        is JsonArray -> c.mapNotNull { (it as? JsonObject)?.let { o -> if (o.str("type") == "text") o.str("text") else if (o.str("type") == "image") "[图片]" else null } }.joinToString("\n")
        else -> ""
    }

    private fun clip(s: String) = if (s.length <= TOOL_OUTPUT_LIMIT) s else s.take(TOOL_OUTPUT_LIMIT) + "\n…（已截断，共 ${s.length} 字）"

    /** 代码块围栏比内容里最长的反引号串多一个。 */
    internal fun fence(s: String, lang: String = ""): String {
        var longest = 0
        var run = 0
        for (ch in s) { if (ch == '`') { run++; longest = maxOf(longest, run) } else run = 0 }
        val f = "`".repeat(maxOf(3, longest + 1))
        return "$f$lang\n$s\n$f"
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
}
