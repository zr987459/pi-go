package io.github.zr987459.pigo.feature.models

import io.github.zr987459.pigo.contract.fake.FakeAuthManager
import io.github.zr987459.pigo.contract.fake.FakeCustomProviderStore
import io.github.zr987459.pigo.contract.fake.FakeEngineClient
import io.github.zr987459.pigo.contract.fake.FakeModelCatalog
import io.github.zr987459.pigo.contract.models.ModelPickerTab
import io.github.zr987459.pigo.contract.session.OverlayModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8.5「ModelPanels 产出的 OverlayModel 在两种形态的渲染器夹具里都能画」。
 *
 * 真渲染器（CalmSheet / TermSelectList）分属 r3/calm、r3/term，本线不能依赖；这里用两个与契约对齐的参考夹具渲染器：
 * [termLines]（终端：80 列字符行，键帽一行）与 [calmNodes]（UI：一组可点节点，每个可点项都有 `overlay:<id>` 下的 key）。
 * 断言每个面板两种画法都非空、每个可选项都有对应节点、终端行宽不超过 80 列（宽字符按 2 列）。
 */
class ModelPanelsRenderFixtureTest {

    @Test fun everyPanelRendersInBothForms() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val auth = FakeAuthManager()
            val p = RealModelPanels(FakeModelCatalog(auth), auth, FakeCustomProviderStore(), FakeEngineClient(scope, 0), scope)
            val all: List<OverlayModel> = listOf(
                p.modelPicker(), p.modelPicker(ModelPickerTab.SCOPED), p.modelPicker(ModelPickerTab.RECENT), p.modelPicker(query = "son"),
                p.providerList(), p.providerList("qw"), p.providerForm("deepseek"), p.providerForm("amazon-bedrock"),
                p.customProviderForm(null), p.logoutPicker(), p.oauthFlow("anthropic"), p.providerForm("nope"),
            )
            for (m in all) {
                val lines = termLines(m)
                val nodes = calmNodes(m)
                assertTrue("term ${m.id}", lines.isNotEmpty())
                assertTrue("calm ${m.id}", nodes.isNotEmpty())
                lines.forEach { assertTrue("${m.id} 超宽：$it", width(it) <= 80) }
                if (m is OverlayModel.SelectList) {
                    m.groups.flatMap { it.items }.forEach { item -> assertTrue(item.key, nodes.contains("overlay:${m.id}/${item.key}")) }
                }
                if (m is OverlayModel.Form) m.fields.forEach { f -> assertTrue(f.key, nodes.contains("overlay:${m.id}/field:${f.key}")) }
            }
        } finally { scope.cancel() }
    }

    companion object {
        fun width(s: String): Int = s.fold(0) { w, c -> w + if (c.code >= 0x1100) 2 else 1 }
        fun clip(s: String, max: Int = 80): String {
            val sb = StringBuilder(); var w = 0
            for (c in s) { val cw = if (c.code >= 0x1100) 2 else 1; if (w + cw > max) break; sb.append(c); w += cw }
            return sb.toString()
        }

        fun termLines(m: OverlayModel): List<String> = buildList {
            add(clip("── ${m.title} ──"))
            when (m) {
                is OverlayModel.SelectList -> {
                    if (m.tabs.isNotEmpty()) add(clip(m.tabs.joinToString("  ") { if (it == m.tab) "[$it]" else it }))
                    add(clip("> ${m.query}"))
                    m.groups.forEach { g ->
                        g.title?.let { add(clip("  $it")) }
                        g.items.forEach { i -> add(clip((if (i.key == m.selectedKey) "→ " else "  ") + (if (i.starred == true) "★ " else "") + i.label + (i.detail?.let { "  $it" } ?: ""))) }
                    }
                    if (m.keyCaps.isNotEmpty()) add(clip(m.keyCaps.joinToString(" ") { "${it.key} ${it.label}" }))
                }
                is OverlayModel.Form -> m.fields.forEach { f -> add(clip("${f.label}: ${m.values[f.key].orEmpty()}")); f.help?.let { add(clip("  $it")) } }
                is OverlayModel.Text -> m.text.lines().forEach { add(clip(it)) }
                else -> add(clip(m.toString()))
            }
        }

        fun calmNodes(m: OverlayModel): List<String> = buildList {
            add("overlay:${m.id}")
            when (m) {
                is OverlayModel.SelectList -> {
                    m.tabs.forEach { add("overlay:${m.id}/tab:$it") }
                    m.groups.flatMap { it.items }.forEach { add("overlay:${m.id}/${it.key}") }
                    m.keyCaps.forEach { add("overlay:${m.id}/cap:${it.actionId}") }
                }
                is OverlayModel.Form -> { m.fields.forEach { add("overlay:${m.id}/field:${it.key}") }; add("overlay:${m.id}/submit") }
                is OverlayModel.Text -> add("overlay:${m.id}/text")
                else -> Unit
            }
        }
    }
}
