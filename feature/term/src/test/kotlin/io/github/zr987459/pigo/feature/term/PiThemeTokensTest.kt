package io.github.zr987459.pigo.feature.term

import io.github.zr987459.pigo.feature.term.theme.PiColor
import io.github.zr987459.pigo.feature.term.theme.PiThemeLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * DESIGN-r3 §7.3 PiThemeTokensTest：原样打包的 dark/light 主题 56 个 token 全部解析，
 * 值与在 node 里跑 pi 1.5.0 主题模块（pi-tui `parseColor` + `colorToHex`，含 fallback）的输出逐个一致。
 * 夹具 `pi/theme-tokens-golden.json` 由 `node` 跑 pi 包生成（生成脚本见 docs/notices/r3-term.md）。
 */
class PiThemeTokensTest {
    private val golden = Json.parseToJsonElement(javaClass.getResource("/pi/theme-tokens-golden.json")!!.readText()).jsonObject

    private fun asset(name: String) = File("src/main/assets/pi-theme/$name.json").readText()

    @Test
    fun all56TokensMatchPi() {
        assertEquals(56, PiThemeLoader.TOKENS.size)
        for (name in listOf("dark", "light")) {
            val theme = PiThemeLoader.parse(asset(name))
            val want = golden[name]!!.jsonObject
            assertEquals(want.keys.toList(), theme.colors.keys.toList())
            for ((k, v) in want) assertEquals("$name.$k", v.jsonPrimitive.content, theme[k]?.hex() ?: "")
        }
    }

    @Test
    fun parsesOtherColorForms() {
        assertEquals("#aabbcc", PiColor.parse("#abc")!!.hex())
        assertEquals("#ffffff", PiColor.parse("oklch(100% 0.3 150)")!!.hex())
        assertEquals("#5f87af", PiColor.indexed(67).hex())
        assertEquals(null, PiColor.parse(""))
    }
}
