package io.github.zr987459.pigo.feature.term

import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.RetryBanner
import io.github.zr987459.pigo.contract.SlashCommand
import io.github.zr987459.pigo.contract.actions.PiActionCatalog
import io.github.zr987459.pigo.contract.actions.TermEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端形态入口的 JVM 部分（硬性要求 ① 终端侧的静态核对；界面点击由 androidTest `TermCoverageTest` 做）：
 * 动作表里每个动作的终端入口在 TermSurface 里都有对应的东西可点 / 可输入。
 */
class TermInputTest {
    private val catalog = PiActionCatalog.all

    @Test
    fun everyTermEntryIsReachable() {
        val strip = TermInput.stripKeys(catalog).map { it.key }.toSet()
        val missing = mutableListOf<String>()
        for (spec in catalog) {
            val ok = when (val t = spec.term) {
                TermEntry.Slash -> spec.slash.isNotEmpty() && TermInput.resolveSlash("/${spec.slash.first()}", catalog)?.first == spec.id
                is TermEntry.KeyStrip -> t.key in strip
                is TermEntry.Footer -> t.segment in setOf("cwd", "model") && (t.segment != "cwd" || TermInput.actionForFooter("cwd", catalog) == spec.id)
                is TermEntry.KeyCap -> t.selector in TermInput.SELECTOR_OVERLAY || t.selector in TermInput.INLINE_SELECTORS
                is TermEntry.Explain -> t.text.isNotBlank()
            }
            if (!ok) missing += "${spec.id} ${spec.term}"
        }
        assertTrue("终端入口缺失：$missing", missing.isEmpty())
    }

    @Test
    fun stripOrderFollowsDesign() {
        val keys = TermInput.stripKeys(catalog).map { it.key }
        assertEquals("Esc", keys.first())
        assertEquals("⏎", keys.last())
        assertTrue(keys.containsAll(listOf("Tab", "⇣", "↑队", "^C", "⌃O", "⌕", "⇞⇟", "⤢", "/", "@", "!", "📎", "⇄UI")))
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun escPicksByState() {
        assertEquals("abort", TermInput.actionForKey("Esc", catalog, ChatState(running = true)))
        assertEquals("retry.abort", TermInput.actionForKey("Esc", catalog, ChatState(running = true, retry = RetryBanner(1, 3, 2000, "overloaded"))))
        assertEquals("bash.abort", TermInput.actionForKey("Esc", catalog, ChatState(), bashRunning = true))
        assertNull(TermInput.actionForKey("Tab", catalog, ChatState()))
    }

    @Test
    fun slashCompletionAndResolve() {
        val cmds = listOf(SlashCommand("skill:review", "Review code"), SlashCommand("tree", "dup"))
        val c = TermInput.slashCandidates("tr", catalog, cmds)
        assertEquals("session.tree", c.single { it.name == "tree" }.actionId)
        assertTrue(c.all { it.name.startsWith("tr") })
        assertEquals(1, c.count { it.name == "tree" })
        assertTrue(TermInput.slashCandidates("skill", catalog, cmds).any { it.name == "skill:review" && it.actionId == null })
        assertEquals("session.name" to "新名字", TermInput.resolveSlash("/name 新名字", catalog))
        assertNull(TermInput.resolveSlash("/skill:review", catalog))
        assertNull(TermInput.resolveSlash("hello", catalog))
        assertEquals("tr", TermInput.slashQuery("/tr"))
        assertNull(TermInput.slashQuery("/tree x"))
        assertNotNull(TermInput.resolveSlash("/ui", catalog))
    }

    @org.junit.Test
    fun atQueryFindsTokenAtCursor() {
        org.junit.Assert.assertEquals(TermInput.AtQuery(3, 7, "mai"), TermInput.atQuery("看看 @mai", 7))
        org.junit.Assert.assertEquals("光标在词中间：结尾算到词尾", TermInput.AtQuery(0, 5, "ma"), TermInput.atQuery("@main x", 3))
        org.junit.Assert.assertNull("邮箱里的 @ 不触发", TermInput.atQuery("a@b", 3))
        org.junit.Assert.assertNull(TermInput.atQuery("no at", 5))
        org.junit.Assert.assertEquals("引号内空格不断词", TermInput.AtQuery(0, 6, "my n"), TermInput.atQuery("@\"my n", 6))
        org.junit.Assert.assertEquals("空查询", TermInput.AtQuery(0, 1, ""), TermInput.atQuery("@", 1))
    }

    @org.junit.Test
    fun retryCountdownCountsDown() {
        org.junit.Assert.assertEquals(4L, TermInput.retrySecondsLeft(4000, 0))
        org.junit.Assert.assertEquals(3L, TermInput.retrySecondsLeft(4000, 1200))
        org.junit.Assert.assertEquals(0L, TermInput.retrySecondsLeft(4000, 5000))
    }

    @org.junit.Test
    fun startupResourcesLikePiHeader() {
        val cmds = listOf(
            io.github.zr987459.pigo.contract.SlashCommand("skill:review", null, io.github.zr987459.pigo.contract.CommandSource.SKILL, "/root/.pi/agent/skills/review/SKILL.md"),
            io.github.zr987459.pigo.contract.SlashCommand("fix", null, io.github.zr987459.pigo.contract.CommandSource.PROMPT, "/root/.pi/agent/prompts/fix.md"),
            io.github.zr987459.pigo.contract.SlashCommand("hello", null, io.github.zr987459.pigo.contract.CommandSource.EXTENSION, "/root/.pi/agent/extensions/hello.ts"),
            io.github.zr987459.pigo.contract.SlashCommand("hello2", null, io.github.zr987459.pigo.contract.CommandSource.EXTENSION, "/root/.pi/agent/extensions/hello.ts"),
            io.github.zr987459.pigo.contract.SlashCommand("pigo:label", null, io.github.zr987459.pigo.contract.CommandSource.EXTENSION, "/root/.pi/pigo/pigo-bridge.ts"),
        )
        val r = TermInput.startupResources(listOf("/root/proj/AGENTS.md"), cmds, listOf("anthropic/*"), "/root")
        org.junit.Assert.assertEquals(listOf("Context", "Skills", "Prompts", "Extensions", "Models"), r.map { it.title })
        org.junit.Assert.assertEquals(listOf("~/proj/AGENTS.md"), r[0].items)
        org.junit.Assert.assertEquals("扩展按文件去重、不列 pigo-bridge", listOf("~/.pi/agent/extensions/hello.ts"), r[3].items)
        org.junit.Assert.assertTrue(TermInput.startupResources(emptyList(), emptyList(), emptyList(), null).isEmpty())
    }

    @org.junit.Test
    fun directionalBindings() {
        org.junit.Assert.assertEquals("model.cycle", io.github.zr987459.pigo.feature.term.keys.KeyBindings.ACTION_FOR["app.model.cycleBackward"])
        org.junit.Assert.assertEquals("backward", io.github.zr987459.pigo.feature.term.keys.KeyBindings.ACTION_ARGS["app.model.cycleBackward"])
        org.junit.Assert.assertEquals("prev", io.github.zr987459.pigo.feature.term.keys.KeyBindings.ACTION_ARGS["tui.altScreen.previousPrompt"])
        org.junit.Assert.assertEquals("next", io.github.zr987459.pigo.feature.term.keys.KeyBindings.ACTION_ARGS["tui.altScreen.nextPrompt"])
    }
}

/** PR #18 审查第 2 轮 Important 2：↑↓ 翻历史不覆盖、不清空共享草稿（pi-tui 行为）。 */
class HistoryNavTest {
    private val history = listOf("第一条", "修一下 foo")

    @org.junit.Test
    fun upSavesDraftAndDownPastNewestRestoresIt() {
        val nav = HistoryNav()
        org.junit.Assert.assertEquals("修一下 foo", nav.up(history, "写到一半的草稿"))
        org.junit.Assert.assertEquals("第一条", nav.up(history, "修一下 foo"))
        org.junit.Assert.assertEquals("第一条", nav.up(history, "第一条")) // 顶部停住
        org.junit.Assert.assertEquals("修一下 foo", nav.down(history))
        org.junit.Assert.assertEquals("写到一半的草稿", nav.down(history)) // 越过最新一条 → 恢复草稿而不是清空
        org.junit.Assert.assertFalse(nav.browsing)
        org.junit.Assert.assertNull(nav.down(history)) // 不在浏览时 ↓ 不改草稿
    }

    @org.junit.Test
    fun editingRecalledEntryExitsBrowsingAndKeepsEdit() {
        val nav = HistoryNav()
        nav.up(history, "")
        nav.onDraftChanged("修一下 foo") // 自己写进去的那次，不算编辑
        org.junit.Assert.assertTrue(nav.browsing)
        nav.onDraftChanged("修一下 foo 和 bar") // 用户编辑
        org.junit.Assert.assertFalse(nav.browsing)
        org.junit.Assert.assertNull(nav.down(history)) // ↓ 不再动草稿
        // 再按 ↑：从编辑后的文字开始翻，越过最新一条时回到它
        org.junit.Assert.assertEquals("修一下 foo", nav.up(history, "修一下 foo 和 bar"))
        org.junit.Assert.assertEquals("修一下 foo 和 bar", nav.down(history))
    }

    @org.junit.Test
    fun emptyHistoryDoesNothing() {
        val nav = HistoryNav()
        org.junit.Assert.assertNull(nav.up(emptyList(), "草稿"))
        org.junit.Assert.assertFalse(nav.browsing)
    }
}

/** PR #18 审查第 2 轮 Nit 1、Nit 2 与契约新项（Form.actions、OAuth 重试）的纯逻辑。 */
class TermPanelRoutingTest {
    @org.junit.Test
    fun atResultOnlyUsableForItsOwnQuery() {
        val m = listOf(FileMatch("src/a.kt", false, "@src/a.kt"))
        val q1 = TermInput.AtQuery(0, 3, "sr")
        val q2 = TermInput.AtQuery(0, 4, "src")
        org.junit.Assert.assertEquals(m, TermInput.atMatchesFor(q1, q1, m))
        org.junit.Assert.assertTrue("防抖期间旧词的结果不可用", TermInput.atMatchesFor(q2, q1, m).isEmpty())
        org.junit.Assert.assertTrue(TermInput.atMatchesFor(null, q1, m).isEmpty())
    }

    @org.junit.Test
    fun autocompleteMaxVisibleBelowFiveIsHonoured() {
        org.junit.Assert.assertEquals(3, TermInput.visibleCount(3))
        org.junit.Assert.assertEquals(1, TermInput.visibleCount(0))
        org.junit.Assert.assertEquals(8, TermInput.visibleCount(8))
    }

    @org.junit.Test
    fun panelsOutsideCatalogRouteToPanelHost() {
        val ids = io.github.zr987459.pigo.contract.actions.PiActionCatalog.all.map { it.id }.toSet()
        org.junit.Assert.assertTrue(TermInput.routesToPanel("provider.form:anthropic", ids))
        org.junit.Assert.assertTrue(TermInput.routesToPanel("oauth:github-copilot", ids))
        org.junit.Assert.assertFalse(TermInput.routesToPanel("session.tree", ids))
        org.junit.Assert.assertFalse("SessionHost 的面板照旧 runAction", TermInput.routesToPanel("overlay:session.cwd.confirm:/x", ids))
        org.junit.Assert.assertTrue(TermInput.routesToPanel("confirm.clear:anthropic", ids))
    }

    @org.junit.Test
    fun formJsonIsAJsonObject() {
        org.junit.Assert.assertEquals("""{"key":"sk-1","baseUrl":"http://x"}""", TermInput.formJson(linkedMapOf("key" to "sk-1", "baseUrl" to "http://x")))
    }

    @org.junit.Test
    fun oauthPanelOffersRetryOnlyWhenFailed() {
        // 审查第 3 轮 Nit 1：按结构化状态 OverlayModel.Text.flow 判断，不看文案
        val T = io.github.zr987459.pigo.contract.session.OverlayModel::Text
        org.junit.Assert.assertEquals(listOf("refresh", "retry"), TermInput.oauthCaps(T("oauth:x", "登录 x", "任意文案", io.github.zr987459.pigo.contract.session.FlowState.FAILED)).map { it.second })
        org.junit.Assert.assertEquals(listOf("refresh"), TermInput.oauthCaps(T("oauth:x", "登录 x", "登录失败：文案不算数", io.github.zr987459.pigo.contract.session.FlowState.RUNNING)).map { it.second })
        org.junit.Assert.assertTrue(TermInput.oauthCaps(T("oauth:x", "登录 x", "已登录", io.github.zr987459.pigo.contract.session.FlowState.DONE)).isEmpty())
        org.junit.Assert.assertTrue("没有结构化状态的普通文字面板没有键帽", TermInput.oauthCaps(T("about", "关于", "登录失败", null)).isEmpty())
    }
}
