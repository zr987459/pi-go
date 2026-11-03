package io.github.zr987459.pigo.feature.commands.data

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.NoticeKind
import io.github.zr987459.pigo.contract.Outcome
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json

/**
 * 自动命名（S5）的提示词、对话摘录与结果清洗。
 *
 * 移植自 agegr/pi-web@025e24e `lib/session-title.ts:13-48`（常量与提示词）、`:131-185`（boundTranscript）、
 * `:186-210`（lineForMessage）、`:225-270`（parseGeneratedSessionTitle），MIT。
 * 差别：pi-go 不能在宿主里直接调模型，改为容器里跑一次 `pi --print --no-session --no-tools`。
 */
object SessionTitle {
    private const val MAX_TITLE_LENGTH = 80
    private const val USER_CHARS = 800
    private const val ASSISTANT_CHARS = 300
    private const val LAST_ASSISTANT_CHARS = 600
    private const val SUMMARY_CHARS = 600
    const val TRANSCRIPT_CHARS = 6000
    private val TRANSCRIPT_HEAD_CHARS = Math.round(TRANSCRIPT_CHARS * 0.4).toInt()
    private const val ELISION = "[…]"

    const val SYSTEM_PROMPT = "You name chat sessions from a transcript. Reply with the title only."

    const val TITLE_PROMPT = """Create a concise title for this session based on the conversation above.

Requirements:
- Match the primary language used by the user.
- Describe the user's concrete goal or the outcome, not the act of chatting.
- Use 4-12 words for space-separated languages, or 8-24 characters for CJK text when practical.
- Do not call any tools.
- Return only the title as plain text, with no quotes, label, markdown, or explanation."""

    fun transcript(items: List<ChatItem>): String {
        val lastAssistant = items.lastOrNull { it is ChatItem.Assistant && textOf(it).isNotBlank() }
        val lines = items.mapNotNull { line(it, it === lastAssistant) }
        return bound(lines)
    }

    fun prompt(transcript: String): String = "$transcript\n\n$TITLE_PROMPT"

    private fun line(item: ChatItem, isLast: Boolean): String? = when (item) {
        is ChatItem.User -> item.text.trim().takeIf { it.isNotEmpty() }?.let { "User: ${clip(it, USER_CHARS)}" }
        is ChatItem.Assistant -> textOf(item).trim().takeIf { it.isNotEmpty() }
            ?.let { "Assistant: ${clip(it, if (isLast) LAST_ASSISTANT_CHARS else ASSISTANT_CHARS)}" }
        is ChatItem.Notice -> if (item.kind == NoticeKind.COMPACTION && item.text.isNotBlank()) {
            "[Earlier summary] ${clip(item.text.trim(), SUMMARY_CHARS)}"
        } else null
        else -> null
    }

    private fun textOf(a: ChatItem): String =
        (a as ChatItem.Assistant).blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

    private fun clip(text: String, max: Int): String {
        val cps = text.codePointCount(0, text.length)
        if (cps <= max) return text
        return text.substring(0, text.offsetByCodePoints(0, max)) + "…"
    }

    private fun bound(lines: List<String>): String {
        val joined = lines.joinToString("\n\n")
        if (joined.length <= TRANSCRIPT_CHARS || lines.size < 2) return joined
        val head = ArrayList<String>()
        var used = ELISION.length + 4
        var next = 0
        while (next < lines.size) {
            val size = used + lines[next].length + 2
            if (head.isNotEmpty() && size > TRANSCRIPT_HEAD_CHARS) break
            head += lines[next]
            used = size
            next++
        }
        val tail = ArrayList<String>()
        var i = lines.size - 1
        while (i >= next) {
            val size = used + lines[i].length + 2
            if (size > TRANSCRIPT_CHARS) break
            tail.add(0, lines[i])
            used = size
            i--
        }
        if (next + tail.size >= lines.size) return joined
        return (head + ELISION + tail).joinToString("\n\n")
    }

    private val QUOTES = listOf("\"" to "\"", "'" to "'", "`" to "`", "\u201c" to "\u201d", "\u300c" to "\u300d", "\u300e" to "\u300f")

    /** 清洗模型输出；没有可用字符时返回 null。 */
    fun parse(raw: String): String? {
        var v = raw.trim()
        Regex("^```(?:json|text)?\\s*([\\s\\S]*?)\\s*```$", RegexOption.IGNORE_CASE).find(v)?.let { v = it.groupValues[1].trim() }
        if (v.startsWith("{")) {
            try {
                val t = (Json.parseToJsonElement(v).jsonObject["title"] as? JsonPrimitive)?.contentOrNull
                if (t != null) v = t.trim()
            } catch (_: Exception) {
            }
        }
        v = v.split(Regex("\\r?\\n"), limit = 2).firstOrNull().orEmpty()
        v = v.replace(Regex("^(?:session\\s+title|title|标题)\\s*[:：-]\\s*", RegexOption.IGNORE_CASE), "")
        for ((s, e) in QUOTES) {
            if (v.startsWith(s) && v.endsWith(e) && v.length > s.length + e.length) {
                v = v.substring(s.length, v.length - e.length).trim()
                break
            }
        }
        v = v.replace(Regex("\\s+"), " ").trim().replace(Regex("[。.!！]+$"), "").trim()
        if (!v.any { it.isLetterOrDigit() }) return null
        val cps = v.codePointCount(0, v.length)
        if (cps > MAX_TITLE_LENGTH) v = v.substring(0, v.offsetByCodePoints(0, MAX_TITLE_LENGTH)).trim()
        return v
    }

    /**
     * 容器里一次性命名的命令行。提示词走 `@文件`（避免超长 argv），系统提示词单独给；
     * 不带工具、不建会话、不加载扩展与技能，思考关掉。
     */
    fun command(model: ModelInfo?, promptFileGuest: String): List<String> = buildList {
        add("pi"); add("--print"); add("--no-session"); add("--no-tools")
        add("--no-extensions"); add("--no-skills"); add("--no-context-files")
        add("--system-prompt"); add(SYSTEM_PROMPT)
        if (model != null) {
            add("--provider"); add(model.provider)
            add("--model"); add(model.id)
            if (model.reasoning) { add("--thinking"); add("off") }
        }
        add("@$promptFileGuest")
        add("Reply with the title only.")
    }
}

/**
 * 在容器里跑一次 `pi --print` 生成标题（不写入）。提示词先写成容器内的临时文件，用完即删。
 */
class TitleGenerator(
    private val exec: suspend (List<String>) -> ExecResult,
    /** 写提示词文件：返回（宿主文件，容器内路径）。 */
    private val writePrompt: (String) -> Pair<File, String>,
) {
    suspend fun generate(items: List<ChatItem>, model: ModelInfo?): Outcome<String> {
        val transcript = SessionTitle.transcript(items)
        if (transcript.isBlank()) return Outcome.Err(ErrCode.UNKNOWN, "会话里还没有可用来命名的文字")
        val (host, guest) = try {
            writePrompt(SessionTitle.prompt(transcript))
        } catch (e: Exception) {
            return Outcome.Err(ErrCode.DISK_FULL, "写临时文件失败：${e.message}", e)
        }
        val r = try {
            exec(SessionTitle.command(model, guest))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Err(ErrCode.UNKNOWN, e.message ?: "运行 pi 失败", e)
        } finally {
            host.delete()
        }
        if (r.timedOut) return Outcome.Err(ErrCode.RPC_TIMEOUT, "生成标题超时")
        if (r.exitCode != 0) return Outcome.Err(ErrCode.PROVIDER, r.stderr.lineSequence().lastOrNull { it.isNotBlank() }?.take(200) ?: "pi 退出码 ${r.exitCode}")
        val title = SessionTitle.parse(r.stdout) ?: return Outcome.Err(ErrCode.UNKNOWN, "模型没有返回可用的标题")
        return Outcome.Ok(title)
    }
}

/**
 * 自动命名的时机（S5）：一轮结束后、会话还没有名字、用户没手动改过、这个会话还没尝试过。
 * 每个会话只试一次，失败不重试，也不打扰用户。
 */
class AutoTitlePolicy {
    private val attempted = HashSet<String>()

    /** 满足条件时登记并返回 true（同一会话只会返回一次 true）。 */
    @Synchronized
    fun claim(sessionFile: String?, sessionName: String?, nameLocked: Boolean, items: List<ChatItem>): Boolean {
        if (sessionFile == null || !sessionName.isNullOrBlank() || nameLocked) return false
        if (sessionFile in attempted) return false
        if (items.none { it is ChatItem.User }) return false
        if (items.none { it is ChatItem.Assistant && !it.streaming && it.blocks.any { b -> b is ContentBlock.Text && b.text.isNotBlank() } }) return false
        attempted += sessionFile
        return true
    }
}
