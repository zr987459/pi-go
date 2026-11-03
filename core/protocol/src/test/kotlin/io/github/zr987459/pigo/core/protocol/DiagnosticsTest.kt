package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.ErrCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN.md §3.9 的 stderr 归类表与模型错误映射，逐行对照。 */
class DiagnosticsTest {

    private data class Row(val stderr: List<String>, val kind: ErrCode, val hint: String, val action: FixAction)

    private val table = listOf(
        // 没有可用模型 / 未配置 Key（pi 1.5.0 实际文案）
        Row(listOf("No API key found for the selected model."), ErrCode.NO_MODEL, "还没有配置可用的模型", FixAction.MODEL_SETTINGS),
        Row(listOf("Error: No models available. Configure a provider first."), ErrCode.NO_MODEL, "还没有配置可用的模型", FixAction.MODEL_SETTINGS),
        // Node 堆溢出（Node 24 原样输出）
        Row(
            listOf(
                "<--- Last few GCs --->",
                "FATAL ERROR: Reached heap limit Allocation failed - JavaScript heap out of memory",
                " 1: 0xb8d0a3 node::Abort() [node]",
            ),
            ErrCode.OOM, "内存不足，已自动重启；可在设置里减少并发任务", FixAction.NONE,
        ),
        Row(listOf("FATAL ERROR: Ineffective mark-compacts near heap limit Allocation failed - JavaScript heap out of memory"), ErrCode.OOM, "内存不足，已自动重启；可在设置里减少并发任务", FixAction.NONE),
        Row(listOf("Error: ENOSPC: no space left on device, write"), ErrCode.DISK_FULL, "手机存储空间不足", FixAction.STORAGE_SETTINGS),
        Row(listOf("Error: getaddrinfo ENOTFOUND api.deepseek.com"), ErrCode.NETWORK, "网络或 DNS 不可用", FixAction.RETRY),
        Row(listOf("Error: getaddrinfo EAI_AGAIN registry.npmmirror.com"), ErrCode.NETWORK, "网络或 DNS 不可用", FixAction.RETRY),
        Row(listOf("Error: read ECONNRESET"), ErrCode.NETWORK, "网络或 DNS 不可用", FixAction.RETRY),
        Row(
            listOf("node:internal/modules/cjs/loader:1228", "Error: Cannot find module '/usr/lib/node_modules/@earendil-works/pi-coding-agent/dist/cli.js'"),
            ErrCode.ENGINE_START, "pi 安装不完整", FixAction.REPAIR_RUNTIME,
        ),
        Row(listOf("/usr/bin/env: 'pi': No such file or directory"), ErrCode.ENGINE_START, "pi 安装不完整", FixAction.REPAIR_RUNTIME),
        Row(listOf("sh: 1: pi: not found"), ErrCode.ENGINE_START, "pi 安装不完整", FixAction.REPAIR_RUNTIME),
        Row(listOf("proot error: ptrace(TRACEME): Operation not permitted"), ErrCode.ENGINE_START, "容器无法启动（系统限制）", FixAction.DIAGNOSTICS),
        Row(listOf("proot warning: can't sanitize binding \"/proc/self/fd/0\""), ErrCode.ENGINE_START, "pi 启动失败", FixAction.DIAGNOSTICS),
        // 匹配不到 → 「pi 启动失败」+ 查看日志
        Row(listOf("something unexpected"), ErrCode.ENGINE_START, "pi 启动失败", FixAction.DIAGNOSTICS),
        Row(emptyList(), ErrCode.ENGINE_START, "pi 启动失败", FixAction.DIAGNOSTICS),
    )

    @Test
    fun stderrClassificationTable() {
        for (row in table) {
            val d = StderrClassifier.classify(row.stderr, exitCode = 1, beforeReady = true)
            assertEquals("${row.stderr}", Diagnosis(row.kind, row.hint, row.action), d)
        }
    }

    @Test
    fun oomWinsOverOtherMatchesAndRuntimeCrashFallbacks() {
        val d = StderrClassifier.classify(listOf("Error: read ECONNRESET", "FATAL ERROR: Reached heap limit"), 134, beforeReady = false)
        assertEquals(ErrCode.OOM, d.kind)
        assertEquals(ErrCode.ENGINE_EXIT, StderrClassifier.classify(listOf("bye"), 1, beforeReady = false).kind)
        assertEquals("pi 意外退出（退出码 1）", StderrClassifier.classify(listOf("bye"), 1, beforeReady = false).hint)
        assertEquals("pi 意外退出", StderrClassifier.classify(emptyList(), null, beforeReady = false).hint)
        // 137 = SIGKILL（lmkd 低内存杀进程）
        assertEquals(ErrCode.OOM, StderrClassifier.classify(emptyList(), 137, beforeReady = false).kind)
        assertEquals(ErrCode.ENGINE_START, StderrClassifier.classify(emptyList(), 137, beforeReady = true).kind)
    }

    @Test
    fun providerErrorMapping() {
        fun k(m: String?) = ProviderErrors.hint(m)
        assertTrue(k("401 Incorrect API key provided: sk-mo***").text.startsWith("Key 错误"))
        assertTrue(k("403 Forbidden").text.startsWith("Key 错误"))
        assertEquals(FixAction.MODEL_SETTINGS, k("Authentication Fails (auth header format should be Bearer sk-...)").action)
        assertTrue(k("402 Insufficient Balance").text.startsWith("额度不足"))
        assertTrue(k("429 Too Many Requests").text.startsWith("额度不足"))
        assertTrue(k("rate_limit_exceeded").text.startsWith("额度不足"))
        assertEquals(ErrCode.NO_MODEL, k("404 model not found").kind)
        assertEquals(ErrCode.NO_MODEL, k("The model `x` does not exist").kind)
        assertEquals(ErrCode.NETWORK, k("Connection error.").kind)
        assertEquals(ErrCode.NETWORK, k("fetch failed").kind)
        assertEquals(ErrCode.NETWORK, k("getaddrinfo ENOTFOUND api.deepseek.com").kind)
        assertEquals("模型服务暂时不可用，请稍后重试", k("503 The server is overloaded").text)
        assertEquals("模型服务暂时不可用，请稍后重试", k("529").text)
        assertEquals(ErrCode.NO_MODEL, k("No API key found for the selected model.").kind)
        assertEquals("模型调用失败", k("weird").text)
        assertEquals("模型调用失败", k(null).text)
        // 数字边界：4010 不是 401
        assertEquals("模型调用失败", k("code 4010").text)
    }

    @Test
    fun redactorMasksKeysEverywhere() {
        val r = Redactor { listOf("abc", "my-raw-key-9999") } // 太短的已知值不替换，避免误伤
        val out = r.redact(
            "Authorization: Bearer eyJhbGciOi.xyz DEEPSEEK_API_KEY=sk-123456789 PIGO_KEY_BAILIAN=raw-secret " +
                "{\"apiKey\":\"abcdefghij\"} key my-raw-key-9999 sk-proj-ABCDEFGH12 abc",
        )
        assertFalse(out.contains("eyJhbGciOi"))
        assertFalse(out.contains("sk-123456789"))
        assertFalse(out.contains("raw-secret"))
        assertFalse(out.contains("abcdefghij"))
        assertFalse(out.contains("my-raw-key-9999"))
        assertFalse(out.contains("ABCDEFGH12"))
        assertTrue(out.contains("Bearer ***"))
        assertTrue(out.contains("DEEPSEEK_API_KEY=***"))
        assertTrue(out.contains("PIGO_KEY_BAILIAN=***"))
        assertTrue(out.endsWith(" abc"))
        assertEquals("普通日志 sk-a", Redactor().redact("普通日志 sk-a"))
    }
}
