package io.github.zr987459.pigo.contract.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiActionCatalogTest {

    /** DESIGN-r3 §2 各表「动作 id」列里出现的全部 id（首推第一版）。 */
    private val expectedIds = setOf(
        "oneshot", "packages", "help.authPrint", "mcp", "model.select", "help.apiKey", "context.system",
        "session.resume", "session.fork", "settings.session", "session.newEphemeral", "session.name", "model.scoped",
        "settings.tools", "thinking.select", "skills", "templates", "appearance", "settings.contextFiles",
        "session.export", "session.info", "help.tuiMode", "trust", "settings.network", "help", "about", "send",
        "send.followUp", "abort", "queue.dequeue", "session.new", "model.cycle", "thinking.cycle", "settings.queue",
        "session.compact", "settings.compaction", "settings.retry", "retry.abort", "bash", "bash.abort",
        "session.clone", "session.tree", "copy.last", "tree.label", "reload", "auth.login", "session.import",
        "settings", "auth.logout", "ext.llama", "session.share", "bug", "hotkeys", "changelog", "debug",
        "engine.stop", "form.toggle", "tree.filter", "tree.fold", "tree.time", "session.delete", "session.cwd",
        "provider.custom", "model.thinkingAdvanced", "model.setDefault", "model.refreshCatalog", "editor.clear",
        "help.suspend", "editor.expand", "attach.image", "thinking.toggle", "tools.expandAll", "editor.history",
        "transcript.search", "transcript.jump",
        // r3/session（目录移交后）补齐：DESIGN-r3 §2.5 扩展命令、§2.6 powershell、§2.10 help.env、§2.13 彩蛋的说明条目。
        "ext.commands", "help.powershell", "help.env", "help.easterEgg",
    )

    private val all = PiActionCatalog.all

    @Test
    fun containsEveryDesignActionIdExactlyOnce() {
        val ids = all.map { it.id }
        assertEquals("重复的 id", ids.size, ids.toSet().size)
        assertEquals(expectedIds, ids.toSet())
    }

    @Test
    fun everyActionHasTitleRefsAndBothEntries() {
        for (a in all) {
            assertTrue("${a.id} 标题为空", a.title.isNotBlank())
            assertTrue("${a.id} featureRefs 为空", a.featureRefs.isNotEmpty())
            assertTrue("${a.id} featureRefs 须以 § 开头", a.featureRefs.all { it.startsWith("§") })
            (a.ui as? UiEntry.Explain)?.let { assertTrue("${a.id} UI 说明为空", it.text.isNotBlank()) }
            (a.term as? TermEntry.Explain)?.let { assertTrue("${a.id} 终端说明为空", it.text.isNotBlank()) }
            if (a.term is TermEntry.Slash) assertTrue("${a.id} 终端入口是斜杠命令但没有斜杠名", a.slash.isNotEmpty())
        }
    }

    @Test
    fun slashNamesAreUniqueAndWellFormed() {
        val names = all.flatMap { it.slash }
        assertEquals("斜杠名重复", names.size, names.toSet().size)
        assertTrue(names.all { Regex("[a-z][a-z0-9-]*").matches(it) })
    }

    @Test
    fun lookupByIdAndSlash() {
        assertEquals("session.tree", PiActionCatalog.bySlash("/tree")?.id)
        assertEquals("form.toggle", PiActionCatalog.bySlash("term")?.id)
        assertEquals("engine.stop", PiActionCatalog.bySlash("quit")?.id)
        assertNotNull(PiActionCatalog["retry.abort"])
    }

    @Test
    fun naRowsAreExplainEntriesInBothForms() {
        for (id in listOf("help.authPrint", "help.apiKey", "help.tuiMode", "help.suspend")) {
            val a = PiActionCatalog[id]!!
            assertTrue(id, a.ui is UiEntry.Explain && a.term is TermEntry.Explain)
        }
    }
}
