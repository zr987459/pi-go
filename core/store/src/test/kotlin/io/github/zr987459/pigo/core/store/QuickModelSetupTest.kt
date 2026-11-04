package io.github.zr987459.pigo.core.store

import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SecretStore
import io.github.zr987459.pigo.core.store.QuickModelSetup.Verdict
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * r4/model-config：极简模型配置写出的文件与 pi 1.5.0 的格式一致（docs/models.md「Configure a compatible endpoint」、
 * docs/settings.md 的 defaultProvider/defaultModel、auth.json 的 api_key 条目，与 pi 原生 /login 存 Key 的格式相同），
 * Key 写进 pi 自己读的 auth.json（权限 600，热加载用），不进进程环境；保存后先让 pi 读到新配置（热加载）再 set_model。
 */
class QuickModelSetupTest {
    private class MemSecrets : SecretStore {
        val map = linkedMapOf<String, String>()
        override suspend fun put(alias: String, plain: String) { map[alias] = plain }
        override suspend fun get(alias: String) = map[alias]
        override suspend fun remove(alias: String) { map.remove(alias) }
        override suspend fun last4(alias: String) = KeyRedaction.last4(map[alias])
    }

    private lateinit var rootfs: File
    private lateinit var paths: GuestPaths
    private lateinit var config: PiProviderConfigStore
    private val secrets = MemSecrets()
    private val calls = mutableListOf<String>()
    private var applyResult: Outcome<Boolean> = Outcome.Ok(true)
    private val key = "sk-test-0123456789abcdef"
    private var server: Tiny? = null

    private fun setup(
        catalog: List<String> = listOf("deepseek-chat", "deepseek-reasoner"),
    ) = QuickModelSetup(
        config,
        catalogModels = { catalog },
        applyConfig = { p, r, _ -> calls += "apply:$p:$r"; applyResult },
        setModel = { p, m -> calls += "set_model:$p/$m"; Outcome.Ok(Unit) },
    )

    @Before fun setUp() {
        rootfs = Files.createTempDirectory("pigo-quick").toFile()
        paths = GuestPaths(rootfs)
        config = PiProviderConfigStore(secrets, paths)
    }

    @After fun tearDown() {
        server?.stop()
        rootfs.deleteRecursively()
    }

    private fun json(f: File): JsonObject = Json.parseToJsonElement(f.readText()).jsonObject
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content

    private fun assertOwnerOnly(f: File) =
        assertEquals("$f 权限 600", "rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath())))

    /** auth.json 里 [provider] 的条目：pi 的 api_key 格式（字面 Key）+ pi-go 标记。 */
    private fun assertAuthEntry(provider: String) {
        val e = json(paths.authJson)[provider]!!.jsonObject
        assertEquals("api_key", e.s("type"))
        assertEquals(key, e.s("key"))
        assertEquals("true", e.s(PiProviderConfigStore.MANAGED_MARK))
        assertOwnerOnly(paths.authJson)
    }

    /** 除 auth.json 外，容器里的文件都不能有 Key；进程环境里也没有（热加载不靠环境变量）。 */
    private suspend fun assertKeyOnlyInAuthJson() {
        rootfs.walkTopDown().filter { it.isFile && it != paths.authJson }.forEach { assertFalse("Key 只在 auth.json：$it", it.readText().contains(key)) }
        assertFalse("不进进程环境", config.env().values.any { it == key })
    }

    // ---------------------------------------------------------------- 写入格式

    @Test fun builtInProviderWritesKeyToAuthJsonDefaultToSettingsAndReloadsBeforeSetModel() = runBlocking {
        val r = setup().save("deepseek", key, "deepseek-chat")
        assertTrue("$r", r is Outcome.Ok)
        val settings = json(paths.settingsJson)
        assertEquals("deepseek", settings.s("defaultProvider"))
        assertEquals("deepseek-chat", settings.s("defaultModel"))
        assertEquals("Keystore 仍是主存", key, secrets.map[ProviderPresets.builtInAlias("deepseek")])
        assertAuthEntry("deepseek")
        assertFalse("内置服务商不写 models.json", paths.modelsJson.exists())
        assertEquals("先让 pi 读到新配置，再对当前会话 set_model", listOf("apply:deepseek:模型配置已更新", "set_model:deepseek/deepseek-chat"), calls)
        assertKeyOnlyInAuthJson()
    }

    @Test fun sharedEnvProviderWritesLiteralKeyToAuthJson() = runBlocking {
        // 用户自己的 OAuth 条目与手写条目不动
        paths.authJson.parentFile.mkdirs()
        paths.authJson.writeText("""{"anthropic":{"type":"oauth","access":"a","refresh":"r","expires":1},"mine":{"type":"api_key","key":"sk-mine"}}""")
        assertTrue(setup(listOf("kimi-k2")).save("moonshotai-cn", key, "kimi-k2") is Outcome.Ok)
        assertAuthEntry("moonshotai-cn")
        val root = json(paths.authJson)
        assertEquals("oauth", root["anthropic"]!!.jsonObject.s("type"))
        assertEquals("sk-mine", root["mine"]!!.jsonObject.s("key"))
        assertKeyOnlyInAuthJson()
        // 清除：只删 pi-go 的条目
        config.clearProvider("moonshotai-cn")
        assertFalse("moonshotai-cn" in json(paths.authJson))
        assertTrue("mine" in json(paths.authJson))
    }

    @Test fun legacyEnvReferenceIsMigratedToLiteralKey() = runBlocking {
        // 旧版写的 $PIGO_PK_* 引用：下次同步换成字面 Key（升级后不重新填也能热加载）
        secrets.put(ProviderPresets.builtInAlias("moonshotai-cn"), key)
        paths.authJson.parentFile.mkdirs()
        paths.authJson.writeText("""{"moonshotai-cn":{"type":"api_key","key":"${'$'}PIGO_PK_MOONSHOTAI_CN"}}""")
        config.env()
        assertAuthEntry("moonshotai-cn")
    }

    @Test fun compatiblePresetWritesModelsJsonInPiSchema() = runBlocking {
        // 用户自己写的其他 provider 要保留
        paths.modelsJson.parentFile.mkdirs()
        paths.modelsJson.writeText("""{"providers":{"ollama":{"baseUrl":"http://localhost:11434/v1","api":"openai-completions","apiKey":"ollama","models":[{"id":"q"}]}}}""")
        val r = setup().save("bailian", key, "qwen-plus", models = listOf("qwen-max", "qwen-plus", " "))
        assertTrue("$r", r is Outcome.Ok)
        val providers = json(paths.modelsJson)["providers"]!!.jsonObject
        assertTrue("其他 provider 保留", "ollama" in providers)
        val p = providers["bailian"]!!.jsonObject
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", p.s("baseUrl"))
        assertEquals("openai-completions", p.s("api"))
        // apiKey 留作 pi-go 条目的标记（未注入的 $NAME 引用）；实际 Key 在 auth.json，pi 优先用 auth.json 的凭据
        // （pi 1.5.0 provider-composer composeApiKeyAuth：有 stored credential 就用它）。
        assertEquals("\$PIGO_KEY_BAILIAN", p.s("apiKey"))
        assertEquals(listOf("qwen-plus", "qwen-max"), (p["models"] as JsonArray).map { (it as JsonObject).s("id") })
        assertOwnerOnly(paths.modelsJson)
        assertAuthEntry("bailian")
        assertKeyOnlyInAuthJson()
        assertEquals("bailian", json(paths.settingsJson).s("defaultProvider"))
        assertEquals(listOf("apply:bailian:模型配置已更新", "set_model:bailian/qwen-plus"), calls)
        // 删除端点：auth.json 的条目一起删
        config.removeCustom("bailian")
        assertFalse("bailian" in json(paths.authJson))
    }

    @Test fun customEndpointIdComesFromHost() = runBlocking {
        assertEquals("example", QuickModelSetup.customIdFor("https://api.example.com/v1"))
        assertEquals("siliconflow", QuickModelSetup.customIdFor("https://api.siliconflow.cn/v1"))
        assertEquals("与内置重名时加后缀", "deepseek-api", QuickModelSetup.customIdFor("https://api.deepseek.com"))
        // 审查 N3：IP 用完整 IP；与已有的不同端点撞 id 时加后缀，同一端点复用原 id
        assertEquals("192-168-1-5", QuickModelSetup.customIdFor("http://192.168.1.5:11434/v1"))
        assertEquals("192-168-1-6", QuickModelSetup.customIdFor("http://192.168.1.6:11434/v1"))
        val existing = mapOf("example" to "https://a.example.com/v1", "example-2" to "https://b.example.net/v1")
        assertEquals("example-3", QuickModelSetup.customIdFor("https://example.org/v1", existing))
        assertEquals("example", QuickModelSetup.customIdFor("https://a.example.com/v1/", existing))
        assertEquals("example-2", QuickModelSetup.customIdFor("https://b.example.net/v1", existing))
        val r = setup().save(QuickModelSetup.CUSTOM, key, "m1", customBaseUrl = "https://api.example.com/v1/")
        assertTrue("$r", r is Outcome.Ok)
        val p = json(paths.modelsJson)["providers"]!!.jsonObject["example"]!!.jsonObject
        assertEquals("https://api.example.com/v1", p.s("baseUrl"))
        assertEquals("\$PIGO_KEY_EXAMPLE", p.s("apiKey"))
        // 另一个主机名主体相同的端点：不覆盖已存的 example
        assertTrue(setup().save(QuickModelSetup.CUSTOM, key, "m2", customBaseUrl = "https://example.org/v1") is Outcome.Ok)
        val providers = json(paths.modelsJson)["providers"]!!.jsonObject
        assertEquals("https://api.example.com/v1", providers["example"]!!.jsonObject.s("baseUrl"))
        assertEquals("https://example.org/v1", providers["example-2"]!!.jsonObject.s("baseUrl"))
        assertEquals("example-2", json(paths.settingsJson).s("defaultProvider"))
    }

    @Test fun probeAndDiscoveryUseConfiguredProxy() = runBlocking {
        // 审查 N4：测试连接 / 拉模型列表走「高级」里设的代理，与 pi 进程的 HTTPS_PROXY 一致
        config.setProxy("http://127.0.0.1:7890")
        val seen = mutableListOf<java.net.Proxy>()
        val q = QuickModelSetup(
            config, catalogModels = { emptyList() }, applyConfig = { _, _, _ -> Outcome.Ok(true) }, setModel = { _, _ -> Outcome.Ok(Unit) },
            discover = { _, _, p -> seen += p; Outcome.Ok(listOf("m")) },
            probe = { _, _, _, p -> seen += p; ProbeResult(true, "ok", 200, 1) },
        )
        q.models("openrouter-x", key) // 不认识的服务商：不发请求
        q.models("bailian", key)
        assertEquals(Verdict.OK, q.test("openrouter", key, "openai/gpt-4o").verdict)
        assertEquals(2, seen.size)
        seen.forEach { assertEquals(java.net.Proxy.Type.HTTP, it.type()); assertTrue(it.address().toString().contains("7890")) }
    }

    @Test fun saveReportsWaitingWhenFallingBackToRestartDuringTurn() = runBlocking {
        // 热加载做不到、退回重启且正在生成：装配方先报 onWaiting（等本轮结束再重启），保存把它变成给页面的进度文字
        val progress = mutableListOf<String>()
        val q = QuickModelSetup(
            config, catalogModels = { emptyList() },
            applyConfig = { _, _, onWaiting -> onWaiting(); calls += "restart"; Outcome.Ok(false) },
            setModel = { p, m -> calls += "set_model:$p/$m"; Outcome.Ok(Unit) },
        )
        assertTrue(q.save("deepseek", key, "deepseek-chat", onProgress = { progress += it }) is Outcome.Ok)
        assertEquals(listOf(QuickModelSetup.PROGRESS_APPLYING, QuickModelSetup.PROGRESS_WAITING), progress)
        assertEquals(listOf("restart", "set_model:deepseek/deepseek-chat"), calls)
    }

    @Test fun validationAndRestartFailureAreReportedInChinese() = runBlocking {
        val q = setup()
        assertEquals("请填写「API Key」", (q.save("deepseek", "", "deepseek-chat") as Outcome.Err).message)
        assertEquals("先选一个模型", (q.save("deepseek", key, " ") as Outcome.Err).message)
        assertTrue((q.save(QuickModelSetup.CUSTOM, key, "m", customBaseUrl = "ftp://x") as Outcome.Err).message.contains("https://"))
        applyResult = Outcome.Err(ErrCode.ENGINE_START, "运行环境未就绪")
        val r = q.save("deepseek", key, "deepseek-chat") as Outcome.Err
        assertTrue(r.message, r.message.contains("pi 没能读到新配置"))
        assertFalse("重启失败就不发 set_model", calls.any { it.startsWith("set_model") })
    }

    // ---------------------------------------------------------------- 拉模型列表 / 测试连接的成败分类

    /** 本机假 OpenAI 兼容服务（单测跑在 android.jar 上，没有 com.sun.net.httpserver，同 ProviderProbeTest 用 ServerSocket）。 */
    private inner class Tiny(private val chat: (String) -> Pair<Int, String>) {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val c = runCatching { socket.accept() }.getOrNull() ?: break
                c.use { runCatching { handle(it) } }
            }
        }

        private fun handle(c: Socket) {
            val input = c.getInputStream().buffered()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) return
                head.append(b.toChar())
            }
            val lines = head.toString().split("\r\n")
            val path = lines[0].split(" ")[1]
            val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(len).also { var off = 0; while (off < len) { val n = input.read(it, off, len - off); if (n < 0) break; off += n } }
            val ok = headers["authorization"] == "Bearer $key"
            val (code, resp) = when {
                !ok && path.endsWith("/models") -> 401 to """{"error":"bad key"}"""
                !ok -> 401 to """{"error":"invalid api key"}"""
                path.endsWith("/models") -> 200 to """{"data":[{"id":"m-b"},{"id":"m-a"}]}"""
                else -> chat(String(body))
            }
            val bytes = resp.toByteArray()
            val out = c.getOutputStream()
            out.write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(bytes)
            out.flush()
        }

        fun stop() { socket.close(); worker.join(1000) }
    }

    private fun fakeServer(chat: (String) -> Pair<Int, String>): String {
        val s = Tiny(chat)
        server = s
        return "http://127.0.0.1:${s.port}/v1"
    }

    @Test fun modelListAndConnectionVerdicts() = runBlocking {
        val url = fakeServer { req ->
            when {
                "\"m-gone\"" in req -> 400 to """{"error":{"message":"Model Not Exist"}}"""
                "\"m-404\"" in req -> 404 to """{"error":"not found"}"""
                "\"m-busy\"" in req -> 429 to """{"error":"rate limited"}"""
                else -> 200 to """{"choices":[{"message":{"content":"OK"}}]}"""
            }
        }
        val q = setup()
        assertEquals(Outcome.Ok(listOf("m-a", "m-b")), q.models(QuickModelSetup.CUSTOM, key, url))
        assertTrue("错 Key 拉列表给中文原因", (q.models(QuickModelSetup.CUSTOM, "sk-wrong", url) as Outcome.Err).message.contains("Key"))
        assertEquals("内置服务商用 pi 目录", Outcome.Ok(listOf("deepseek-chat", "deepseek-reasoner")), q.models("deepseek", ""))

        assertEquals(Verdict.OK, q.test(QuickModelSetup.CUSTOM, key, "m-a", url).verdict)
        val bad = q.test(QuickModelSetup.CUSTOM, "sk-wrong", "m-a", url)
        assertEquals(Verdict.BAD_KEY, bad.verdict)
        assertTrue(bad.message.startsWith("Key 不对"))
        assertEquals(Verdict.MODEL_NOT_FOUND, q.test(QuickModelSetup.CUSTOM, key, "m-gone", url).verdict)
        val notFound = q.test(QuickModelSetup.CUSTOM, key, "m-404", url)
        assertEquals("404 多半是地址不对（审查 N2）", Verdict.BAD_URL, notFound.verdict)
        assertTrue(notFound.message, notFound.message.startsWith("接口地址或模型不对"))
        assertEquals(Verdict.QUOTA, q.test(QuickModelSetup.CUSTOM, key, "m-busy", url).verdict)
        val s = server!!
        val port = s.port
        s.stop(); server = null
        val down = q.test(QuickModelSetup.CUSTOM, key, "m-a", "http://127.0.0.1:$port/v1")
        assertEquals(Verdict.NETWORK, down.verdict)
        assertTrue(down.message.startsWith("网络不通"))
        assertEquals(Verdict.BAD_URL, q.test(QuickModelSetup.CUSTOM, key, "m-a", "example.com").verdict)
        assertEquals(Verdict.BAD_KEY, q.test("deepseek", "", "deepseek-chat").verdict)
    }
}
