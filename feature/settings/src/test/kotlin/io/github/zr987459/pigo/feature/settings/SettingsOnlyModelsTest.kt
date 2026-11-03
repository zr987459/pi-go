package io.github.zr987459.pigo.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN-r3 §7.3：设置页根节点只有「模型」一组。 */
class SettingsOnlyModelsTest {

    @Test fun rootHasOnlyModelsGroup() {
        val root = ModelsSettings.root()
        assertEquals(1, root.size)
        assertEquals("模型", root.single().title)
        assertEquals(ModelsSettings.ROOT_GROUP, root.single().key)
    }

    @Test fun modelsGroupHasSpecSections() {
        val keys = ModelsSettings.root().single().sections.map { it.key }
        assertEquals(listOf("default", "scoped", "connected", "providers", "custom", "advanced"), keys)
        assertTrue(ModelsSettings.root(42).single().sections.any { it.title == "全部服务商（42）" })
        assertEquals(listOf("思考预算", "每模型思考档", "刷新模型目录"), ModelsSettings.ADVANCED_ITEMS)
    }

    @Test fun noRemovedSettingsLeak() {
        val titles = ModelsSettings.root().flatMap { g -> listOf(g.title) + g.sections.map { it.title } }
        for (banned in listOf("代理", "费用", "回收站", "运行环境", "后台", "主题", "会话默认")) {
            assertFalse("$banned 不应出现在设置页", titles.any { banned in it })
        }
        assertEquals("pi 的其他设置在 ⌘ › pi 设置", ModelsSettings.FOOTER_HINT)
    }
}
