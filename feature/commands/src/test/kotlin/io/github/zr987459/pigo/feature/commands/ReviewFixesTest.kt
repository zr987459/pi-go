package io.github.zr987459.pigo.feature.commands

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.LaunchEnvProvider
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.fake.FakeRuntimeManager
import io.github.zr987459.pigo.feature.commands.data.PiSessionIndex
import io.github.zr987459.pigo.feature.commands.data.SessionMetaStore
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** PR #10 审查第 1 轮（feature/commands 部分）。 */
class ReviewFixesTest {
    @get:Rule val tmp = TemporaryFolder()

    private class RecordingRuntime(base: RuntimeManager) : RuntimeManager by base {
        val envs = mutableListOf<Map<String, String>>()
        val argvs = mutableListOf<List<String>>()
        override suspend fun exec(cmd: List<String>, env: Map<String, String>, timeoutMs: Long): ExecResult {
            argvs += cmd; envs += env
            return ExecResult(0, "修复登录页\n", "")
        }
    }

    /** Important 2：自动命名的 pi --print 要拿到引擎同款环境变量里的 Key，Key 不进 argv。 */
    // ---------------- Nit：扫描为空不清标记；回收站会话保留标记 ----------------

    private fun writeSession(f: File, text: String) {
        f.parentFile.mkdirs()
        f.writeText(
            """{"type":"session","version":3,"id":"${f.nameWithoutExtension}","timestamp":"t","cwd":"/root/pi-cwd"}""" + "\n" +
                """{"type":"message","id":"u1","parentId":null,"timestamp":"t","message":{"role":"user","content":"$text","timestamp":1}}""" + "\n",
        )
    }

    @Test fun emptyOrMissingSessionsDirKeepsMarks() = runTest {
        val agent = tmp.newFolder("agent")
        val dir = File(agent, "sessions")
        writeSession(File(dir, "--root-pi-cwd--/a.jsonl"), "一")
        val meta = SessionMetaStore(File(tmp.root, "meta.json"))
        val idx = PiSessionIndex(dir, metaStore = meta, cacheFile = null)
        idx.rescan()
        val ga = "/root/.pi/agent/sessions/--root-pi-cwd--/a.jsonl"
        writeSession(File(dir, "--root-pi-cwd--/b.jsonl"), "二")
        idx.rescan()
        val gb = "/root/.pi/agent/sessions/--root-pi-cwd--/b.jsonl"
        idx.setPinned(ga, true)
        idx.markNameLocked(ga)
        idx.setArchived(gb, true)

        dir.deleteRecursively() // 运行环境正在修复，目录暂时不在
        idx.rescan()
        assertTrue("目录不在时不清标记", meta.get(ga).pinned && meta.get(ga).nameLockedByUser && meta.get(gb).archived)

        dir.mkdirs() // 目录在但是空的（还没解出来）
        idx.rescan()
        assertTrue(meta.get(ga).pinned)

        writeSession(File(dir, "--root-pi-cwd--/a.jsonl"), "一")
        idx.rescan()
        assertTrue(meta.get(ga).pinned)
    }

    @Test fun trashedSessionKeepsPinUntilPurged() = runTest {
        val agent = tmp.newFolder("agent2")
        val dir = File(agent, "sessions")
        writeSession(File(dir, "--root-pi-cwd--/a.jsonl"), "一")
        writeSession(File(dir, "--root-pi-cwd--/b.jsonl"), "二")
        writeSession(File(dir, "--root-pi-cwd--/c.jsonl"), "三")
        val meta = SessionMetaStore(File(tmp.root, "meta2.json"))
        val idx = PiSessionIndex(dir, metaStore = meta, cacheFile = null)
        idx.rescan()
        val g = { n: String -> "/root/.pi/agent/sessions/--root-pi-cwd--/$n.jsonl" }
        listOf("a", "b", "c").forEach { idx.setPinned(g(it), true) }

        // b 移入回收站（重名后缀 ~1），c 真删
        val trashed = File(agent, "sessions-trash/--root-pi-cwd--/b~1.jsonl")
        trashed.parentFile.mkdirs()
        assertTrue(File(dir, "--root-pi-cwd--/b.jsonl").renameTo(trashed))
        File(dir, "--root-pi-cwd--/c.jsonl").delete()
        idx.rescan()
        assertTrue(meta.get(g("a")).pinned)
        assertTrue("回收站里的会话保留置顶", meta.get(g("b")).pinned)
        assertFalse("真删的会话清掉标记", meta.get(g("c")).pinned)
        assertEquals(setOf(g("b")), idx.trashedGuestPaths())

        // 恢复后置顶还在
        assertTrue(trashed.renameTo(File(dir, "--root-pi-cwd--/b.jsonl")))
        idx.rescan()
        assertTrue(meta.get(g("b")).pinned)
    }
}
