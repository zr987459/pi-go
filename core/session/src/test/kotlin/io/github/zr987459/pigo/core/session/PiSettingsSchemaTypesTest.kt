package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.settings.PiSettingType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 审查第 1 轮 I5：schema 的类型与枚举取值以 pi 1.5.0 为准（夹具摘自 `@earendil-works/pi-coding-agent@1.5.0`
 * 包内 `docs/settings.md` 的设置表，2026-11-04 `npm pack` 取得）；对象型键按结构保存，表单回存不破坏原值。
 */
class PiSettingsSchemaTypesTest {

    /** pi 1.5.0 docs/settings.md 里的枚举键与取值（字面量原样）。 */
    private val enumsV150 = mapOf(
        "defaultThinkingLevel" to listOf("off", "minimal", "low", "medium", "high", "xhigh", "max"),
        "cacheWarming" to listOf("off", "streaming", "idle"),
        "steeringMode" to listOf("all", "one-at-a-time"),
        "followUpMode" to listOf("all", "one-at-a-time"),
        "doubleEscapeAction" to listOf("tree", "fork", "none"),
        "treeFilterMode" to listOf("default", "no-tools", "user-only", "labeled-only", "all"),
        "defaultProjectTrust" to listOf("ask", "always", "never"),
        "codemode.mode" to listOf("on", "only"),
        "sessionShareDestination" to listOf("auto", "gist", "radius"),
        "tuiMode" to listOf("regular", "fullscreen"),
        "fullscreenExitOutput" to listOf("transcript", "resume-hint"),
        "fullscreenScrollbar" to listOf("auto", "always", "hidden"),
        "markdown.mermaid" to listOf("off", "final", "streaming"),
        "transport" to listOf("auto", "sse", "websocket", "websocket-cached"),
        "terminal.images" to listOf("kitty", "iterm2", "auto", "false"),
    )

    /** pi 1.5.0 里类型为 object / array（可含对象）的键。 */
    private val structuralV150 = setOf("modelThinkingLevels", "thinkingBudgets", "compaction.modelOverrides", "packages")

    /** pi 1.5.0 里类型为 string 的键（不能被当成数字）。 */
    private val stringsV150 = setOf("markdown.codeBlockIndent", "theme", "httpProxy", "shellPath", "shellCommandPrefix", "sessionDir")

    /** `string[]` 键。 */
    private val listsV150 = setOf("enabledModels", "defaultTools", "npmCommand", "selfUpdateCommand", "extensions", "skills", "prompts", "themes")

    private val specs = PiSettingsSchemaV150.specs.associateBy { it.key }

    @Test
    fun enumValuesMatchPi150() {
        for ((k, values) in enumsV150) {
            val t = specs[k]?.type
            assertTrue("$k 应是枚举：$t", t is PiSettingType.Enum)
            assertEquals(k, values.toSet(), (t as PiSettingType.Enum).values.toSet())
        }
    }

    @Test
    fun objectStringAndListKeysHaveRightTypes() {
        for (k in structuralV150) assertTrue("$k 应按 JSON 结构编辑", specs[k]?.let(PiSettingsCodec::isStructural) == true)
        for (k in stringsV150) assertEquals(k, PiSettingType.Str, specs[k]?.type)
        for (k in listsV150) assertEquals(k, PiSettingType.StrList, specs[k]?.type)
        // retry.provider 在 pi 里是对象，按子键编辑，不再有整体的字符串键。
        assertTrue("retry.provider" !in specs)
        for (k in listOf("retry.provider.timeoutMs", "retry.provider.maxRetries", "retry.provider.maxRetryDelayMs")) assertTrue(k, specs[k]?.type is PiSettingType.Number)
    }

    private val original = """
        {
          "modelThinkingLevels": {"anthropic/claude-sonnet-4-5": "high"},
          "thinkingBudgets": {"low": 2048, "high": 32000},
          "compaction": {"enabled": true, "modelOverrides": {"openai/gpt-5": {"reserveTokens": 8000}}},
          "retry": {"provider": {"maxRetries": 2}},
          "packages": [{"source": "npm:foo", "extensions": ["a"]}],
          "someUnknownKey": {"keep": true}
        }
    """.trimIndent()

    private fun impl(): Pair<PiSettingsImpl, java.io.File> {
        val f = Files.createTempDirectory("pis").toFile().resolve("settings.json").also { it.writeText(original) }
        return PiSettingsImpl(f) to f
    }

    @Test
    fun resubmittingEveryGroupFormKeepsFileIdentical() = runBlocking {
        val (s, f) = impl()
        s.refresh()
        val before = Json.parseToJsonElement(f.readText())
        for (group in s.schema.specs.map { it.group }.distinct()) {
            val form = PiSettingsForms.group(s, group)
            for ((k, v) in form.values) {
                if (isSideKey(k)) continue
                val r = s.set(k, JsonPrimitive(v))
                assertTrue("$group/$k=$v 回存失败：$r", r is Outcome.Ok)
            }
        }
        assertEquals("原样回存整组表单不得改动文件（对象值、默认值都不写）", before, Json.parseToJsonElement(f.readText()))
    }

    @Test
    fun objectKeysAreEditedAsJsonAndNeverOverwrittenByStrings() = runBlocking {
        val (s, f) = impl()
        s.refresh()
        assertEquals("""{"anthropic/claude-sonnet-4-5":"high"}""", PiSettingsForms.valuesFor(s, "模型")["modelThinkingLevels"])
        // 非法 JSON、类型不对：拒绝，文件不动。
        assertTrue(s.set("thinkingBudgets", JsonPrimitive("high=1")) is Outcome.Err)
        assertTrue(s.set("thinkingBudgets", JsonPrimitive("[1,2]")) is Outcome.Err)
        assertTrue(s.set("thinkingBudgets", null) is Outcome.Err)
        // 留空 = 不改。
        assertTrue(s.set("compaction.modelOverrides", JsonPrimitive("")) is Outcome.Ok)
        val root = Json.parseToJsonElement(f.readText()) as JsonObject
        assertEquals(Json.parseToJsonElement(original), root)
        // 合法 JSON：按结构写入。
        assertTrue(s.set("thinkingBudgets", JsonPrimitive("""{"low": 1024}""")) is Outcome.Ok)
        assertEquals(Json.parseToJsonElement("""{"low": 1024}"""), SettingsJson.get(SettingsJson.read(f), listOf("thinkingBudgets")))
    }

    @Test
    fun formTextIsConvertedToPiTypes() = runBlocking {
        val (s, f) = impl()
        s.refresh()
        s.set("terminal.hyperlinks", JsonPrimitive("true"))
        s.set("terminal.images", JsonPrimitive("false"))
        s.set("outputPad", JsonPrimitive("1"))
        s.set("httpIdleTimeoutMs", JsonPrimitive("disabled"))
        s.set("fullscreenWheelScrollLines", JsonPrimitive("3"))
        s.set("markdown.codeBlockIndent", JsonPrimitive("    "))
        s.set("npmCommand", JsonPrimitive("pnpm\n--silent"))
        val r = SettingsJson.read(f)
        assertEquals(JsonPrimitive(true), r["terminal"]!!.let { (it as JsonObject)["hyperlinks"] })
        assertEquals(JsonPrimitive(false), (r["terminal"] as JsonObject)["images"])
        assertEquals(JsonPrimitive(1), r["outputPad"])
        assertEquals(JsonPrimitive("disabled"), r["httpIdleTimeoutMs"])
        assertEquals(JsonPrimitive(3), r["fullscreenWheelScrollLines"])
        assertEquals(JsonPrimitive("    "), (r["markdown"] as JsonObject)["codeBlockIndent"])
        assertEquals(Json.parseToJsonElement("""["pnpm","--silent"]"""), r["npmCommand"])
        assertTrue("枚举外的值要拒绝", s.set("cacheWarming", JsonPrimitive("always")) is Outcome.Err)
        assertTrue("未知键要拒绝", s.set("noSuchKey", JsonPrimitive("x")) is Outcome.Err)
    }
}
