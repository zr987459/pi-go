package io.github.zr987459.pigo.core.store

import com.sun.net.httpserver.HttpServer
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SecretStore
import io.github.zr987459.pigo.core.store.QuickModelSetup.Verdict
import java.io.File
import java.net.InetSocketAddress
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
 * docs/settings.md 的 defaultProvider/defaultModel、auth.json 的 api_key 条目），且保存后先重启 pi 再 set_model。
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
    private var restartResult: Outcome<Unit> = Outcome.Ok(Unit)
    private val key = "sk-test-0123456789abcdef"
    private var server: HttpServer? = null

    private fun setup(
        catalog: List<String> = listOf("deepseek-chat", "deepseek-reasoner"),
    ) = QuickModelSetup(
        config,
        catalogModels = { catalog },
        restartEngine = { r -> calls += "restart:$r"; restartResult },
        setModel = { p, m -> calls += "set_model:$p/$m"; Outcome.Ok(Unit) },
    )

    @Before fun setUp() {
        rootfs = Files.createTempDirectory("pigo-quick").toFile()
        paths = GuestPaths(rootfs)
        config = PiProviderConfigStore(secrets, paths)
    }

    @After fun tearDown() {
        server?.stop(0)
        rootfs.deleteRecursively()
    }

    private fun json(f: File): JsonObject = Json.parseToJsonElement(f.readText()).jsonObject
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content

    // ---------------------------------------------------------------- 写入格式

    @Test fun builtInProviderWritesKeyToKeystoreDefaultToSettingsAndRestartsBeforeSetModel() = runBlocking {
        val r = setup().save("deepseek", key, "deepseek-chat")
        assertTrue("$r", r is Outcome.Ok)
        val settings = json(paths.settingsJson)
        assertEquals("deepseek", settings.s("defaultProvider"))
        assertEquals("deepseek-chat", settings.s("defaultModel"))
        assertEquals("Key 只在 Keystore", key, secrets.map[ProviderPresets.builtInAlias("deepseek")])
        assertEquals("新进程启动时经环境变量注入", key, config.env()["DEEPSEEK_API_KEY"])
        assertFalse("内置服务商不写 models.json", paths.modelsJson.exists())
        assertEquals("先重启 pi（新 Key 进环境），再对当前会话 set_model", listOf("restart:模型配置已更新", "set_model:deepseek/deepseek-chat"), calls)
        rootfs.walkTopDown().filter { it.isFile }.forEach { assertFalse("容器文件里不能有 Key：$it", it.readText().contains(key)) }
    }

    @Test fun sharedEnvProviderWritesAuthJsonReferenceNotKey() = runBlocking {
        assertTrue(setup(listOf("kimi-k2")).save("moonshotai-cn", key, "kimi-k2") is Outcome.Ok)
        val entry = json(paths.authJson)["moonshotai-cn"]!!.jsonObject
        assertEquals("api_key", entry.s("type"))
        assertEquals("\$PIGO_PK_MOONSHOTAI_CN", entry.s("key"))
        assertFalse(paths.authJson.readText().contains(key))
        assertEquals(key, config.env()["PIGO_PK_MOONSHOTAI_CN"])
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
        assertEquals("apiKey 是 \$NAME 环境变量引用（pi 1.5.0 docs/models.md）", "\$PIGO_KEY_BAILIAN", p.s("apiKey"))
        assertEquals(listOf("qwen-plus", "qwen-max"), (p["models"] as JsonArray).map { (it as JsonObject).s("id") })
        assertFalse(paths.modelsJson.readText().contains(key))
        assertEquals(key, config.env()["PIGO_KEY_BAILIAN"])
        assertEquals("bailian", json(paths.settingsJson).s("defaultProvider"))
        assertEquals(listOf("restart:模型配置已更新", "set_model:bailian/qwen-plus"), calls)
    }

    @Test fun customEndpointIdComesFromHost() = runBlocking {
        assertEquals("example", QuickModelSetup.customIdFor("https://api.example.com/v1"))
        assertEquals("siliconflow", QuickModelSetup.customIdFor("https://api.siliconflow.cn/v1"))
        assertEquals("与内置重名时加后缀", "deepseek-api", QuickModelSetup.customIdFor("https://api.deepseek.com"))
        val r = setup().save(QuickModelSetup.CUSTOM, key, "m1", customBaseUrl = "https://api.example.com/v1/")
        assertTrue("$r", r is Outcome.Ok)
        val p = json(paths.modelsJson)["providers"]!!.jsonObject["example"]!!.jsonObject
        assertEquals("https://api.example.com/v1", p.s("baseUrl"))
        assertEquals("\$PIGO_KEY_EXAMPLE", p.s("apiKey"))
    }

    @Test fun validationAndRestartFailureAreReportedInChinese() = runBlocking {
        val q = setup()
        assertEquals("请填写「API Key」", (q.save("deepseek", "", "deepseek-chat") as Outcome.Err).message)
        assertEquals("先选一个模型", (q.save("deepseek", key, " ") as Outcome.Err).message)
        assertTrue((q.save(QuickModelSetup.CUSTOM, key, "m", customBaseUrl = "ftp://x") as Outcome.Err).message.contains("https://"))
        restartResult = Outcome.Err(ErrCode.ENGINE_START, "运行环境未就绪")
        val r = q.save("deepseek", key, "deepseek-chat") as Outcome.Err
        assertTrue(r.message, r.message.contains("pi 没能重启"))
        assertFalse("重启失败就不发 set_model", calls.any { it.startsWith("set_model") })
    }

    // ---------------------------------------------------------------- 拉模型列表 / 测试连接的成败分类

    /** 本机假 OpenAI 兼容服务：`/v1/models` 与 `/v1/chat/completions` 按 [chat] 返回。 */
    private fun fakeServer(chat: (String) -> Pair<Int, String>): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/v1/models") { ex ->
            val ok = ex.requestHeaders.getFirst("Authorization") == "Bearer $key"
            val body = if (ok) """{"data":[{"id":"m-b"},{"id":"m-a"}]}""" else """{"error":"bad key"}"""
            val bytes = body.toByteArray(); ex.sendResponseHeaders(if (ok) 200 else 401, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        s.createContext("/v1/chat/completions") { ex ->
            val req = ex.requestBody.readBytes().decodeToString()
            val (code, body) = if (ex.requestHeaders.getFirst("Authorization") != "Bearer $key") 401 to """{"error":"invalid api key"}""" else chat(req)
            val bytes = body.toByteArray(); ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/v1"
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
        assertEquals(Verdict.MODEL_NOT_FOUND, q.test(QuickModelSetup.CUSTOM, key, "m-404", url).verdict)
        assertEquals(Verdict.QUOTA, q.test(QuickModelSetup.CUSTOM, key, "m-busy", url).verdict)
        val port = server!!.address.port
        server!!.stop(0); server = null
        val down = q.test(QuickModelSetup.CUSTOM, key, "m-a", "http://127.0.0.1:$port/v1")
        assertEquals(Verdict.NETWORK, down.verdict)
        assertTrue(down.message.startsWith("网络不通"))
        assertEquals(Verdict.BAD_URL, q.test(QuickModelSetup.CUSTOM, key, "m-a", "example.com").verdict)
        assertEquals(Verdict.BAD_KEY, q.test("deepseek", "", "deepseek-chat").verdict)
    }
}
