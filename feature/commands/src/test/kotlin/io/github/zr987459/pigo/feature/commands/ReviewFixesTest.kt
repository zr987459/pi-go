package io.github.zr987459.pigo.feature.commands

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.LaunchEnvProvider
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.fake.FakeRuntimeManager
import io.github.zr987459.pigo.feature.commands.data.TitleGenerator
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
    @Test fun titleExecPassesLaunchEnvKeys() = runTest {
        val rt = RecordingRuntime(FakeRuntimeManager(root = tmp.newFolder("rootfs")))
        var reads = 0
        val env = object : LaunchEnvProvider {
            override suspend fun env(): Map<String, String> {
                reads++
                return mapOf("DEEPSEEK_API_KEY" to "sk-test-0000", "PIGO_KEY_BAILIAN" to "sk-custom-1111")
            }
        }
        val gen = TitleGenerator(
            exec = CommandsGraph.titleExec(rt, env),
            writePrompt = { text -> File(tmp.root, "p.txt").apply { writeText(text) } to "/tmp/pigo-title.txt" },
        )
        val r = gen.generate(listOf(ChatItem.User("u1", "帮我修登录页")), ModelInfo("deepseek", "deepseek-chat"))
        assertEquals(Outcome.Ok("修复登录页"), r)
        val sent = rt.envs.single()
        assertEquals("sk-test-0000", sent["DEEPSEEK_API_KEY"])
        assertEquals("sk-custom-1111", sent["PIGO_KEY_BAILIAN"])
        assertTrue(sent.values.all { it.isNotEmpty() })
        assertFalse("Key 不能出现在 argv 里", rt.argvs.single().any { it.contains("sk-test") || it.contains("sk-custom") })
        assertEquals(1, reads)

        gen.generate(listOf(ChatItem.User("u1", "再来一次")), ModelInfo("deepseek", "deepseek-chat"))
        assertEquals("每次命名都重新读环境（Key 可能刚改过）", 2, reads)
    }
}
