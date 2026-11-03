package io.github.zr987459.pigo.feature.term.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * pi 主题（DESIGN-r3 §3.2）：解析 pi 1.5.0 主题 JSON 的 `vars` + `colors`，按 `theme-tokens.js` 的 56 个 token 与 fallback 规则
 * 得到每个 token 的 sRGB。null = 终端默认色（主题里写空串）。
 */
data class PiTheme(val name: String, val dark: Boolean, val colors: Map<String, Rgb?>) {
    operator fun get(token: String): Rgb? = colors[token]
}

object PiThemeLoader {
    /** `theme-tokens.js` 的 token 表，顺序一致；[background] = background 槽。 */
    data class Token(val name: String, val background: Boolean, val fallback: String?)

    val TOKENS: List<Token> = listOf(
        Token("accent", false, null),
        Token("border", false, null),
        Token("borderAccent", false, null),
        Token("borderMuted", false, null),
        Token("success", false, null),
        Token("error", false, null),
        Token("warning", false, null),
        Token("muted", false, null),
        Token("dim", false, null),
        Token("text", false, null),
        Token("thinkingText", false, null),
        Token("scrollbarTrack", false, "muted"),
        Token("scrollbarThumb", false, "text"),
        Token("selectedBg", true, null),
        Token("searchMatchBg", true, "selectedBg"),
        Token("searchMatchText", false, "text"),
        Token("userMessageBg", true, null),
        Token("userMessageText", false, null),
        Token("customMessageBg", true, null),
        Token("customMessageText", false, null),
        Token("customMessageLabel", false, null),
        Token("toolPendingBg", true, null),
        Token("toolSuccessBg", true, null),
        Token("toolErrorBg", true, null),
        Token("toolTitle", false, null),
        Token("toolOutput", false, null),
        Token("mdHeading", false, null),
        Token("mdLink", false, null),
        Token("mdLinkUrl", false, null),
        Token("mdCode", false, null),
        Token("mdCodeBlock", false, null),
        Token("mdCodeBlockBorder", false, null),
        Token("mdQuote", false, null),
        Token("mdQuoteBorder", false, null),
        Token("mdHr", false, null),
        Token("mdListBullet", false, null),
        Token("toolDiffAdded", false, null),
        Token("toolDiffRemoved", false, null),
        Token("toolDiffContext", false, null),
        Token("syntaxComment", false, null),
        Token("syntaxKeyword", false, null),
        Token("syntaxFunction", false, null),
        Token("syntaxVariable", false, null),
        Token("syntaxString", false, null),
        Token("syntaxNumber", false, null),
        Token("syntaxType", false, null),
        Token("syntaxOperator", false, null),
        Token("syntaxPunctuation", false, null),
        Token("thinkingOff", false, null),
        Token("thinkingMinimal", false, null),
        Token("thinkingLow", false, null),
        Token("thinkingMedium", false, null),
        Token("thinkingHigh", false, null),
        Token("thinkingXhigh", false, null),
        Token("thinkingMax", false, "thinkingXhigh"),
        Token("bashMode", false, null),
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): PiTheme {
        val root = json.parseToJsonElement(text).jsonObject
        val vars = root["vars"]?.jsonObject ?: JsonObject(emptyMap())
        val colors = root["colors"]?.jsonObject ?: error("theme has no colors")
        fun resolve(v: JsonPrimitive, seen: MutableSet<String> = mutableSetOf()): JsonPrimitive {
            if (!v.isString) return v
            val s = v.content
            if (s.isEmpty() || s.startsWith("#") || Regex("^ok(lch|hsl)\\(", RegexOption.IGNORE_CASE).containsMatchIn(s)) return v
            require(seen.add(s)) { "Circular variable reference detected: $s" }
            val next = vars[s]?.jsonPrimitive ?: throw IllegalArgumentException("Variable reference not found: $s")
            return resolve(next, seen)
        }
        val raw = HashMap<String, JsonPrimitive>()
        for ((k, v) in colors) raw[k] = resolve(v.jsonPrimitive)
        for (t in TOKENS) if (raw[t.name] == null && t.fallback != null) raw[t.fallback]?.let { raw[t.name] = it }
        val out = LinkedHashMap<String, Rgb?>()
        for (t in TOKENS) {
            val v = raw[t.name] ?: throw IllegalArgumentException("Theme token ${t.name} missing")
            out[t.name] = v.intOrNull?.takeIf { !v.isString }?.let(PiColor::indexed) ?: PiColor.parse(v.content)
        }
        val name = root["name"]?.jsonPrimitive?.content ?: "custom"
        val dark = (root["appearance"]?.jsonPrimitive?.content ?: "dark") != "light"
        return PiTheme(name, dark, out)
    }
}
