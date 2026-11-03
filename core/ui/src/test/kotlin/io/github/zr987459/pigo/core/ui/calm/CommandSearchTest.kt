package io.github.zr987459.pigo.core.ui.calm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandSearchTest {
    private val tree = CommandSearch.Entry("session.tree", "分支树", listOf("tree"))
    private val mcp = CommandSearch.Entry("mcp", "MCP 服务器", listOf("mcp"))
    private val model = CommandSearch.Entry("model.select", "选择模型", listOf("model"))
    private val all = listOf(tree, mcp, model)

    @Test fun initialsOfChinese() {
        assertEquals("fzs", CommandSearch.initials("分支树"))
        assertEquals("mcpfwq", CommandSearch.initials("MCP 服务器"))
        assertEquals("xzmx", CommandSearch.initials("选择模型"))
    }

    @Test fun matchesByChineseSlashAndPinyin() {
        assertEquals(listOf(tree), CommandSearch.filter(all, "分支", { it }))
        assertEquals(listOf(tree), CommandSearch.filter(all, "/tree", { it }))
        assertEquals(listOf(tree), CommandSearch.filter(all, "fzs", { it }))
        assertEquals(listOf(model), CommandSearch.filter(all, "xzmx", { it }))
        assertEquals(listOf(mcp), CommandSearch.filter(all, "fwq", { it }))
    }

    @Test fun emptyQueryKeepsOrder() {
        assertEquals(all, CommandSearch.filter(all, "  ", { it }))
    }

    @Test fun slashExactRanksFirst() {
        val m2 = CommandSearch.Entry("model.cycle", "下一个模型 model", listOf())
        val r = CommandSearch.filter(listOf(m2, model), "model", { it })
        assertEquals(model, r.first())
    }

    @Test fun commonCharsHaveInitials() {
        for (c in "会话新建恢复分叉复制压缩导出分享导入改名信息临时一次性提问搜索模型思考强度默认刷新目录登录退出设置技能模板扩展包上下文文件系统项目信任重新加载帮助手势快捷键更新日志报告问题诊断外观关于停止引擎") {
            assertTrue("缺少 $c", PinyinTable.initial(c) != null)
        }
    }
}
