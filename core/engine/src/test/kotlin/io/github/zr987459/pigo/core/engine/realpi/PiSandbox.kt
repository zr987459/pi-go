package io.github.zr987459.pigo.core.engine.realpi

import io.github.zr987459.pigo.core.engine.EngineConfig
import java.io.File
import java.nio.file.Files

/**
 * 一套隔离的 pi 配置目录 + 工作目录 + 本机假模型服务。桌面集成测试与夹具录制共用。
 *
 * 开关（默认关闭，CI 不会运行）：
 * - `PIGO_PI_BIN`：本机 pi 1.5.0 可执行文件路径（`npm i -g @earendil-works/pi-coding-agent@1.5.0` 后的 `pi`）。
 */
class PiSandbox : AutoCloseable {
    val root: File = Files.createTempDirectory("pigo-realpi").toFile()
    val agentDir = File(root, "agent").apply { mkdirs() }
    val cwd = File(root, "cwd").apply { mkdirs() }
    val otherCwd = File(root, "cwd2").apply { mkdirs() }
    val mock = MockOpenAiServer()

    init {
        File(agentDir, "models.json").writeText(
            """
            {"providers": {"mock": {
              "baseUrl": "${mock.baseUrl}",
              "api": "openai-completions",
              "apiKey": "${'$'}PIGO_KEY_MOCK",
              "models": [{"id": "mock-model", "name": "Mock 模型", "reasoning": true, "contextWindow": 32000, "maxTokens": 4096}]
            }}}
            """.trimIndent(),
        )
        File(agentDir, "settings.json").writeText(
            """{"defaultProvider":"mock","defaultModel":"mock-model","defaultThinkingLevel":"medium"}""",
        )
        File(cwd, "README.md").writeText("# pi-go 集成测试工作区\n")
    }

    /** 传给 pi 的 Key 环境变量（与 LaunchEnvProvider 的用法相同）。 */
    val keyEnv: Map<String, String> = mapOf("PIGO_KEY_MOCK" to "sk-mock-0123456789abcdef")

    fun engineConfig(): EngineConfig = EngineConfig(
        agentDir = agentDir.absolutePath,
        defaultCwd = cwd.absolutePath,
        nodeHeapMb = null,
        frameMs = 0,
        restartBackoffMs = listOf(200, 500, 1_000),
        // 桌面上不要读用户自己的 ~/.pi；本机假模型服务不走代理
        extraEnv = mapOf("HOME" to root.absolutePath, "NO_PROXY" to "127.0.0.1,localhost", "no_proxy" to "127.0.0.1,localhost"),
    )

    /** 把录制结果里的主机临时路径换成容器内路径、端口换成 PORT：夹具可复现、不泄露本机信息。 */
    fun sanitize(line: String): String = line
        .replace(encoded(cwd), "--root-pi-cwd--")
        .replace(encoded(otherCwd), "--root-other-cwd--")
        .replace(agentDir.absolutePath, "/root/.pi/agent")
        .replace(otherCwd.absolutePath, "/root/other-cwd")
        .replace(cwd.absolutePath, "/root/pi-cwd")
        .replace(root.absolutePath, "/root")
        .replace("127.0.0.1:${mock.port}", "127.0.0.1:PORT")

    /** pi 的会话目录名：去掉开头分隔符，`/ \ :` 换成 `-`（session-format.md）。 */
    private fun encoded(dir: File) =
        "--" + dir.absolutePath.trimStart('/').replace('/', '-').replace('\\', '-').replace(':', '-') + "--"

    override fun close() {
        mock.close()
        root.deleteRecursively()
    }

    companion object {
        val piBin: String? get() = System.getenv("PIGO_PI_BIN")?.takeIf { it.isNotBlank() && File(it).canExecute() }
    }
}
