package io.github.zr987459.pigo.feature.files

import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.FileNode
import io.github.zr987459.pigo.contract.GitStatus
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.WorkspaceFs
import io.github.zr987459.pigo.contract.fake.FakeWorkspaceFs
import io.github.zr987459.pigo.feature.files.browser.FilesController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审查第 2 轮：真实 RootfsWorkspace 的 list / gitStatus 只检查不扩大，/root 以外的会话 cwd 要先经 roots(sessionCwd) 登记。
 * 这里的 fs 在 roots 返回之前拒绝访问该 cwd（模拟登记前），且 roots 被闸门挂起，验证控制器等 roots 完成后才列目录、查 git。
 */
class FilesControllerOrderTest {
    private class GatedFs(private val gate: CompletableDeferred<Unit>) : WorkspaceFs by FakeWorkspaceFs() {
        @Volatile var registered = false
        val calls = ArrayList<String>()

        override suspend fun roots(sessionCwd: String?): List<FileNode> {
            gate.await()
            registered = true
            synchronized(calls) { calls += "roots" }
            return listOf(FileNode(sessionCwd!!, "w", isDir = true))
        }

        override suspend fun list(guestDir: String, showHidden: Boolean): Outcome<List<FileNode>> {
            synchronized(calls) { calls += "list" }
            return if (registered) Outcome.Ok(emptyList()) else Outcome.Err(ErrCode.UNKNOWN, "不能访问工作目录以外的位置")
        }

        override suspend fun gitStatus(cwd: String): Outcome<GitStatus> {
            synchronized(calls) { calls += "gitStatus" }
            return if (registered) Outcome.Ok(GitStatus(isRepo = false)) else Outcome.Err(ErrCode.UNKNOWN, "不能访问工作目录以外的位置")
        }
    }

    @Test
    fun listAndGitStatusWaitForRootsRegistration() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val fs = GatedFs(gate)
        val c = FilesController(fs, CoroutineScope(Dispatchers.Unconfined), "/data/proj")
        assertTrue("roots 完成前不得 list / gitStatus", synchronized(fs.calls) { fs.calls.isEmpty() })
        gate.complete(Unit)
        val s = c.state.value
        assertEquals("roots", fs.calls.first())
        assertTrue(fs.calls.containsAll(listOf("list", "gitStatus")))
        assertEquals("首次列目录不应因未登记失败", null, s.error)
        assertNotNull("首次 gitStatus 就应拿到结果，而非 Err", s.git)
    }
}
