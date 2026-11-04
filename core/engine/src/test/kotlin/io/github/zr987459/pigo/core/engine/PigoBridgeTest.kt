package io.github.zr987459.pigo.core.engine

import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.bridge.BridgeState
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** r3/core：pigo-bridge 协议解析、命令文本、安装、BridgeChannel 状态机（DESIGN-r3 §5.6）。 */
class PigoBridgeTest {
    private val asset = File("src/main/assets/pi/pigo-bridge.ts")

    @Test
    fun assetDeclaresAllCommandsAndPrefix() {
        assertTrue("资源文件存在：${asset.absolutePath}", asset.isFile)
        val src = asset.readText()
        for (c in listOf("pigo:tree-nav", "pigo:label", "pigo:reload", "pigo:switch")) assertTrue(c, src.contains("\"$c\""))
        assertTrue(src.contains("const PREFIX = \"${PigoBridge.PREFIX}\""))
        assertTrue(src.contains("const VERSION = ${PigoBridge.VERSION};"))
        assertTrue("只用公开 API，不 import 任何包", src.lineSequence().none { it.trimStart().startsWith("import ") })
        assertEquals("/root/.pi/pigo/pigo-bridge.ts", EngineConfig().bridgeExtension)
    }

    @Test
    fun parseMessages() {
        assertNull(PigoBridge.parse("普通通知"))
        assertNull(PigoBridge.parse("pigo-bridge:不是json"))
        assertNull(PigoBridge.parse(null))
        val r = PigoBridge.parse("""pigo-bridge:{"event":"ready","version":1,"reason":"reload","sessionFile":"/s.jsonl"}""")
        assertEquals(PigoBridge.Message.Ready(1, "reload", "/s.jsonl"), r)
        val rep = PigoBridge.parse("""pigo-bridge:{"cmd":"tree-nav","ok":true,"cancelled":false,"leafId":"ab12"}""") as PigoBridge.Message.Reply
        assertEquals("tree-nav", rep.cmd); assertTrue(rep.ok); assertEquals("ab12", rep.str("leafId")); assertFalse(rep.bool("cancelled"))
        val err = PigoBridge.parse("""pigo-bridge:{"cmd":"label","ok":false,"error":"x"}""") as PigoBridge.Message.Reply
        assertFalse(err.ok); assertEquals("x", err.error)
    }

    @Test
    fun commandText() {
        assertEquals("/pigo:tree-nav e1", PigoBridge.treeNavCommand("e1", false))
        assertEquals("/pigo:tree-nav e1 --summarize", PigoBridge.treeNavCommand("e1", true))
        assertEquals("/pigo:label e1 a b", PigoBridge.labelCommand("e1", " a\r\n\tb "))
        assertEquals("清除标签", "/pigo:label e1", PigoBridge.labelCommand("e1", "  "))
        assertEquals("/pigo:label e1", PigoBridge.labelCommand("e1", null))
        assertEquals("/pigo:switch /a b/c.jsonl", PigoBridge.switchCommand("/a b/c.jsonl\n"))
        assertTrue(PigoBridge.validEntryId("1ce20f1d"))
        assertFalse(PigoBridge.validEntryId("a b"))
        assertFalse(PigoBridge.validEntryId(""))
        assertFalse(PigoBridge.validEntryId("--summarize;x"))
    }

    @Test
    fun installIsIdempotentAndUpdates() {
        val dir = Files.createTempDirectory("pigo-inst").toFile()
        try {
            val t = File(dir, "a/b/pigo-bridge.ts")
            assertTrue(PigoBridge.install("v1".byteInputStream(), t))
            assertEquals("v1", t.readText())
            val mtime = t.lastModified()
            Thread.sleep(20)
            assertTrue(PigoBridge.install("v1".byteInputStream(), t))
            assertEquals("内容相同不重写", mtime, t.lastModified())
            assertTrue(PigoBridge.install("v2".byteInputStream(), t))
            assertEquals("v2", t.readText())
            assertEquals("不留临时文件", listOf("pigo-bridge.ts"), t.parentFile.list()!!.toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun channelStateMachine() = runBlocking {
        val ch = BridgeChannel()
        assertEquals(BridgeState.UNAVAILABLE, ch.status.value.state)
        ch.reset(1, loaded = true)
        assertEquals(BridgeState.PENDING, ch.status.value.state)
        assertFalse("普通通知不消费", ch.onNotify("hello"))
        val w = ch.expectReply("label")
        assertTrue(ch.onNotify("""pigo-bridge:{"event":"ready","version":1,"reason":"startup"}"""))
        assertEquals(BridgeState.READY, ch.status.value.state)
        assertTrue(ch.onNotify("""pigo-bridge:{"cmd":"label","ok":true}"""))
        assertTrue(w.await(100) is Outcome.Ok)
        // 旧进程退出不影响新进程
        ch.reset(2, loaded = true)
        ch.onExit(1)
        assertEquals(BridgeState.PENDING, ch.status.value.state)
        // 新进程 ready 前别的扩展出错：不影响
        assertFalse(ch.onExtensionError("/x/other.ts", "boom"))
        assertEquals(BridgeState.PENDING, ch.status.value.state)
        val pending = ch.expectReply("reload")
        assertTrue(ch.onExtensionError("/root/.pi/pigo/pigo-bridge.ts", "SyntaxError"))
        assertEquals(BridgeState.UNAVAILABLE, ch.status.value.state)
        assertTrue("在途请求失败", pending.await(100) is Outcome.Err)
        ch.reset(3, loaded = false, reason = "未安装")
        assertEquals("未安装", ch.status.value.reason)
        ch.reset(4, loaded = true)
        assertFalse(ch.awaitSettled(50))
        assertEquals(BridgeState.UNAVAILABLE, ch.status.value.state)
    }

    @Test
    fun specArgs() {
        val c = EngineConfig(bridgeExtension = "/root/.pi/pigo/pigo-bridge.ts")
        assertEquals(listOf("pi", "--mode", "rpc"), c.spec(null, null, emptyMap()).argv)
        assertEquals(listOf("pi", "--mode", "rpc", "--session", "/s.jsonl", "-e", "/root/.pi/pigo/pigo-bridge.ts"), c.spec("/s.jsonl", null, emptyMap(), withBridge = true).argv)
        assertEquals(listOf("pi", "--mode", "rpc", "-a"), c.copy(approveProject = true).spec(null, null, emptyMap()).argv)
        assertEquals(listOf("pi", "--mode", "rpc"), c.copy(bridgeExtension = null).spec(null, null, emptyMap(), withBridge = true).argv)
    }

    // ---------------------------------------------------------------- r3/nits-core：请求序号与路径规范化

    @Test
    fun commandsCarryRequestId() {
        assertEquals("/pigo:tree-nav --req=r1 e1 --summarize", PigoBridge.treeNavCommand("e1", true, "r1"))
        assertEquals("/pigo:label --req=r2 e1 a b", PigoBridge.labelCommand("e1", "a\nb", "r2"))
        assertEquals("/pigo:label --req=r2 e1", PigoBridge.labelCommand("e1", null, "r2"))
        assertEquals("/pigo:reload --req=r3", PigoBridge.reloadCommand("r3"))
        assertEquals("/pigo:switch --req=r4 /a b/c.jsonl", PigoBridge.switchCommand("/a b/c.jsonl", "r4"))
        val reply = PigoBridge.parse("""pigo-bridge:{"cmd":"tree-nav","ok":true,"req":"r1"}""") as PigoBridge.Message.Reply
        assertEquals("r1", reply.req)
        assertEquals(PigoBridge.Message.Ready(1, "resume", "/s.jsonl", "r4"), PigoBridge.parse("""pigo-bridge:{"event":"ready","version":1,"reason":"resume","sessionFile":"/s.jsonl","req":"r4"}"""))
    }

    @Test
    fun waitersClaimOnlyTheirOwnRequestIdAndUnclaimedIsDropped() = kotlinx.coroutines.runBlocking {
        val dropped = mutableListOf<PigoBridge.Message>()
        val ch = BridgeChannel { dropped += it }
        val old = ch.expectReply("tree-nav", "r1")
        val cur = ch.expectReply("tree-nav", "r2")
        assertTrue(ch.onNotify("""pigo-bridge:{"cmd":"tree-nav","ok":true,"req":"r1","leafId":"a"}"""))
        assertTrue("r1 的回复只给 r1", old.isDone)
        assertFalse("r2 不被 r1 的回复认领", cur.isDone)
        ch.onNotify("""pigo-bridge:{"cmd":"tree-nav","ok":true,"req":"r2","leafId":"b"}""")
        assertEquals("b", ((cur.await(100) as Outcome.Ok).value as PigoBridge.Message.Reply).str("leafId"))
        assertTrue(dropped.isEmpty())
        ch.onNotify("""pigo-bridge:{"cmd":"tree-nav","ok":true,"req":"r9"}""")
        assertEquals("没人认领 → 丢弃并回调", 1, dropped.size)
        // ready：带序号只认同一序号；不带序号按 fallback
        val sw = ch.expectReadyOrReply("resume", "switch", "r5") { it.sessionFile == "/b.jsonl" }
        ch.onNotify("""pigo-bridge:{"event":"ready","version":1,"reason":"resume","req":"r4","sessionFile":"/a.jsonl"}""")
        assertFalse(sw.isDone)
        ch.onNotify("""pigo-bridge:{"event":"ready","version":1,"reason":"resume","sessionFile":"/a.jsonl"}""")
        assertFalse("不带序号、会话不是目标：不认", sw.isDone)
        ch.onNotify("""pigo-bridge:{"event":"ready","version":1,"reason":"resume","sessionFile":"/b.jsonl"}""")
        assertTrue(sw.isDone)
        assertEquals("startup 这类不带序号的 ready 不算丢弃", 2, dropped.size)
    }

    @Test
    fun normalizePath() {
        assertEquals("/root/.pi/a.jsonl", PigoBridge.normalizePath("~/.pi/a.jsonl"))
        assertEquals("/root/.pi/a.jsonl", PigoBridge.normalizePath("/root//.pi/./x/../a.jsonl/"))
        assertEquals("/proj/s/a.jsonl", PigoBridge.normalizePath("s/a.jsonl", cwd = "/proj"))
        assertEquals("/root", PigoBridge.normalizePath("~"))
        assertEquals(null, PigoBridge.normalizePath("  "))
    }
}
