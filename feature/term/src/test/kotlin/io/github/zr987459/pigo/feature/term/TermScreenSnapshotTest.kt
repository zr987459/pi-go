package io.github.zr987459.pigo.feature.term

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.ToolStatus
import io.github.zr987459.pigo.feature.term.format.Footer
import io.github.zr987459.pigo.feature.term.format.FooterInput
import io.github.zr987459.pigo.feature.term.format.FormatOptions
import io.github.zr987459.pigo.feature.term.format.Span
import io.github.zr987459.pigo.feature.term.format.TermFormatter
import io.github.zr987459.pigo.feature.term.format.TermLine
import io.github.zr987459.pigo.feature.term.text.CellWidth
import io.github.zr987459.pigo.feature.term.theme.PiThemeLoader
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 快照产物（PR 里的截图来源）：用与 TermSurface 相同的 TermFormatter / Footer，把一段夹具会话按 80 列和 48 列排成整屏，
 * 连同每段的前景 / 底色（pi 主题解析后的 sRGB）写到 `build/term-snapshot/<主题>-<列>.json`，再由仓库外的脚本画成 PNG。
 * 断言：每行不超宽、包含页眉 / 用户 / 助手 / 工具 / 页脚。
 */
class TermScreenSnapshotTest {
    private val items = listOf(
        ChatItem.User("u1", "帮我看看 src/Login.kt 里 token 为什么会过期？"),
        ChatItem.Assistant(
            "a1",
            listOf(
                ContentBlock.Thinking(0, "先读 Login.kt，再看刷新逻辑。"),
                ContentBlock.Text(1, "## 原因\n\n刷新令牌在 `refresh()` 里**没有轮换**，过期时间沿用了旧值。\n\n1. 读取 `src/Login.kt`\n2. 修改 `refresh()`\n\n```kotlin\nval expires = now + ttl\n```"),
            ),
            streaming = false, stopReason = "toolUse",
        ),
        ChatItem.Tool("t1", "c1", "read", kotlinx.serialization.json.buildJsonObject { put("path", "src/Login.kt"); put("offset", 1); put("limit", 40) }, ToolStatus.OK, durationMs = 12),
        ChatItem.Tool(
            "t2", "c2", "bash", buildJsonObject { put("command", "./gradlew test") }, ToolStatus.ERROR,
            result = buildJsonObject { putJsonArray("content") { addJsonObject { put("type", "text"); put("text", (1..9).joinToString("\n") { "> Task :core:test$it" } + "\nFAILED: 1 test") } }; put("isError", true) },
            durationMs = 8400,
        ),
        ChatItem.Assistant("a2", listOf(ContentBlock.Text(0, "测试失败一条，我先修 `refresh()`。")), streaming = true),
    )

    private fun screen(cols: Int): List<TermLine> {
        val o = FormatOptions(width = cols, cwd = "/root/proj", home = "/root")
        val out = mutableListOf<TermLine>()
        out += TermLine(emptyList())
        out += TermLine(listOf(Span(" "), Span("pi", "accent", bold = true), Span(" v1.5.0", "dim")))
        TermFormatter.wrapSpans(listOf(Span("escape to interrupt · ctrl+c to clear · shift+tab to cycle thinking · ctrl+l to select model · / for commands · ! to run bash", "dim")), cols - 2)
            .forEach { out += TermLine(listOf(Span(" ")) + it) }
        items.forEach { out += TermFormatter.format(it, o) }
        out += TermLine(emptyList())
        out += TermLine(listOf(Span(" "), Span("⠹ ", "accent"), Span("Working...", "muted"), Span(" 9s", "dim"), Span(" (escape to interrupt)", "dim")))
        out += TermLine(emptyList())
        val box = cols - 2
        out += TermLine(listOf(Span(" "), Span("╭─ high " + "─".repeat((box - 9).coerceAtLeast(0)) + "╮", "thinkingHigh")))
        out += TermLine(listOf(Span(" "), Span("│", "thinkingHigh"), Span(CellWidth.padEnd(" 顺便把单测也补上", box - 2)), Span("│", "thinkingHigh")))
        out += TermLine(listOf(Span(" "), Span("╰" + "─".repeat(box - 2) + "╯", "thinkingHigh")))
        val f = Footer.render(
            FooterInput(
                cwd = "/root/proj", home = "/root", gitBranch = "main", sessionName = "修复登录", input = 12000, output = 3400, cacheRead = 80000, cacheWrite = 2000,
                cost = 0.042, latestCacheHitRate = 85.1, contextPercent = 38.0, contextWindow = 200000, modelId = "claude-sonnet-4.5", provider = "anthropic",
                reasoning = true, thinkingLevel = "high", availableProviderCount = 2,
            ),
            cols - 5,
        )
        out += TermLine(f[0])
        out += TermLine(f[1] + Span(" [UI]", "accent", bold = true))
        out += TermLine(emptyList())
        out += TermLine(listOf(Span(" Esc  Tab  ⇣  ↑队  ^C  ⌃O  ⌕  ⇞⇟  ⤢  /  @  !  📎  ⇄UI  ⏎", "muted")))
        return out
    }

    @Test
    fun writeSnapshots() {
        val dir = File("build/term-snapshot").apply { mkdirs() }
        for (themeName in listOf("dark", "light")) {
            val theme = PiThemeLoader.parse(File("src/main/assets/pi-theme/$themeName.json").readText())
            for (cols in listOf(80, 48)) {
                val lines = screen(cols)
                lines.forEach { assertTrue("超宽：${it.text}", CellWidth.width(it.text) <= cols) }
                val json = buildJsonObject {
                    put("cols", cols); put("theme", themeName)
                    put("lines", buildJsonArray {
                        lines.forEach { l ->
                            addJsonObject {
                                put("bg", l.bg?.let { theme[it]?.hex() } ?: "")
                                putJsonArray("spans") { l.spans.forEach { s -> addJsonObject { put("t", s.text); put("fg", s.token?.let { theme[it]?.hex() } ?: ""); put("b", s.bold); put("i", s.italic) } } }
                            }
                        }
                    })
                }
                File(dir, "$themeName-$cols.json").writeText(json.toString())
                File(dir, "$themeName-$cols.txt").writeText(lines.joinToString("\n") { it.text.trimEnd() })
            }
        }
        val txt = File(dir, "dark-80.txt").readText()
        listOf("pi v1.5.0", "帮我看看", "原因", "read src/Login.kt:1-40", "$ ./gradlew test", "earlier lines", "Took 8.4s", "~/proj (main) • 修复登录", "[UI]").forEach {
            assertTrue("快照缺少「$it」", txt.contains(it))
        }
    }
}
