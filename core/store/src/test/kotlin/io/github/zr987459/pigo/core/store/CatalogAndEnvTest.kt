package io.github.zr987459.pigo.core.store

import io.github.zr987459.pigo.contract.PresetKind
import io.github.zr987459.pigo.contract.SecretStore
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** W2：目录解析、预设由目录生成、多变量服务商注入、共用变量引用、代理、models.json 完整写法、脱敏。 */
class CatalogAndEnvTest {
    private class MemSecrets : SecretStore {
        val map = linkedMapOf<String, String>()
        override suspend fun put(alias: String, plain: String) { map[alias] = plain }
        override suspend fun get(alias: String) = map[alias]
        override suspend fun remove(alias: String) { map.remove(alias) }
        override suspend fun last4(alias: String) = KeyRedaction.last4(map[alias])
    }

    private lateinit var rootfs: File
    private lateinit var paths: GuestPaths
    private val secrets = MemSecrets()

    @Before fun setUp() {
        rootfs = Files.createTempDirectory("pigo-w2").toFile()
        paths = GuestPaths(rootfs)
    }

    @After fun tearDown() { rootfs.deleteRecursively() }

    private fun TestScope.store() = PiProviderConfigStore(secrets, paths, StandardTestDispatcher(testScheduler))
    /** 挂起版 assertThrows：不能在 runTest 里用 runBlocking（测试调度器会死锁）。 */
    private suspend fun expectIae(block: suspend () -> Unit): IllegalArgumentException {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return e
        }
        throw AssertionError("应抛出 IllegalArgumentException")
    }

    private fun json(f: File) = Json.parseToJsonElement(f.readText()).jsonObject

    // ---------------- 目录 ----------------

    @Test fun bundledCatalogHas42ProvidersAndModels() {
        val snap = BuiltinCatalog.bundled()
        assertEquals("1.5.0", snap.piVersion)
        assertEquals(42, snap.providers.size)
        assertTrue(snap.modelCount > 0)
        for (p in snap.providers) for (m in p.models) {
            assertTrue(m.id.isNotBlank())
            assertEquals(p.id, m.provider)
        }
        val ds = snap.provider("deepseek")!!
        assertTrue(ds.models.isNotEmpty())
        assertTrue(snap.provider("github-copilot")!!.oauth)
        assertFalse(snap.provider("openai-codex")!!.apiKeyAuth)
        // 每个目录服务商都有配置方式（登记表或通用规则）
        val unregistered = snap.providers.filter { ProviderSpecs.get(it.id) == null }.map { it.id }
        assertEquals("登记表应覆盖 pi 1.5.0 全部服务商", emptyList<String>(), unregistered)
    }

    @Test fun parseRejectsBadSchemaAndTakesLastJsonLine() {
        assertThrows(BuiltinCatalog.CatalogFormatException::class.java) { BuiltinCatalog.parse("""{"schema":99,"providers":[]}""") }
        assertThrows(BuiltinCatalog.CatalogFormatException::class.java) { BuiltinCatalog.parse("") }
        val s = BuiltinCatalog.parse(
            "(node) warning: something\n" +
                """{"schema":1,"piVersion":"9.9.9","providers":[{"id":"x","name":"X","auth":{"apiKey":true,"oauth":false},"models":[{"id":"m","reasoning":true,"input":["text","image"],"contextWindow":1000,"cost":{"input":1,"output":2,"cacheRead":0,"cacheWrite":0}},{"name":"no id"}]}]}""",
        )
        assertEquals("9.9.9", s.piVersion)
        val m = s.providers.single().models.single()
        assertEquals("x", m.provider)
        assertTrue(m.supportsImages && m.reasoning)
        assertEquals(1000L, m.contextWindow)
        assertEquals(2.0, m.cost!!.output, 0.0)
    }

    @Test fun presetsGeneratedFromCatalog() {
        val builtIn = ProviderPresets.BUILT_IN
        // openai-codex 只能 OAuth，不在 Key 预设里；其余 41 个都在
        assertEquals(41, builtIn.size)
        assertTrue(builtIn.none { it.provider == "openai-codex" })
        assertEquals("deepseek", builtIn.first().provider) // 国内常用在前
        assertEquals("AWS_BEARER_TOKEN_BEDROCK", builtIn.first { it.provider == "amazon-bedrock" }.envVar)
        assertEquals(3, ProviderPresets.ALL.count { it.kind == PresetKind.CUSTOM_OPENAI })
    }

    // ---------------- 多变量服务商 ----------------

    @Test fun bedrockAndAzureInjectAllVariablesAtOnce() = runTest {
        val s = store()
        s.setProviderFields(
            "amazon-bedrock",
            mapOf("AWS_ACCESS_KEY_ID" to "AKIAEXAMPLE", "AWS_SECRET_ACCESS_KEY" to "secretsecret123456", "AWS_REGION" to "us-west-2"),
        )
        s.setProviderFields(
            "azure",
            mapOf("AZURE_OPENAI_API_KEY" to "az-key-0123456789", "AZURE_OPENAI_RESOURCE_NAME" to "my-res", "AZURE_OPENAI_API_VERSION" to "v1"),
        )
        val env = s.env()
        assertEquals("AKIAEXAMPLE", env["AWS_ACCESS_KEY_ID"])
        assertEquals("secretsecret123456", env["AWS_SECRET_ACCESS_KEY"])
        assertEquals("us-west-2", env["AWS_REGION"])
        assertNull(env["AWS_BEARER_TOKEN_BEDROCK"])
        // Bedrock 有多个密文字段：仍经进程环境注入（改了要重启）；Azure 只有一个密文字段：热加载，Key 与非密文字段写 auth.json
        assertNull(env["AZURE_OPENAI_API_KEY"])
        val az = json(paths.authJson)["azure"]!!.jsonObject
        assertEquals("az-key-0123456789", az["key"]!!.jsonPrimitive.content)
        assertEquals("my-res", az["env"]!!.jsonObject["AZURE_OPENAI_RESOURCE_NAME"]!!.jsonPrimitive.content)
        assertEquals("v1", az["env"]!!.jsonObject["AZURE_OPENAI_API_VERSION"]!!.jsonPrimitive.content)
        assertTrue(s.configured().containsAll(listOf("amazon-bedrock", "azure")))
        assertNull("Bedrock 不写 auth.json", json(paths.authJson)["amazon-bedrock"])
        assertFalse(paths.authJson.readText().contains("secretsecret123456"))
        // 预填：密文只给末 4 位
        val f = s.providerFields("amazon-bedrock")
        assertEquals("AKIAEXAMPLE", f["AWS_ACCESS_KEY_ID"])
        assertEquals("••••3456", f["AWS_SECRET_ACCESS_KEY"])
    }

    @Test fun multiFieldValidation() = runTest {
        val s = store()
        val e1 = expectIae { s.setProviderFields("amazon-bedrock", mapOf("AWS_REGION" to "us-east-1")) }
        assertTrue(e1.message!!.contains("Bearer Token"))
        val e2 = expectIae { s.setProviderFields("azure", mapOf("AZURE_OPENAI_API_KEY" to "k-0123456789")) }
        assertTrue(e2.message!!.contains("接口地址或资源名"))
        val e3 = expectIae { s.setProviderFields("cloudflare-workers-ai", mapOf("CLOUDFLARE_API_KEY" to "cf-0123456789")) }
        assertTrue(e3.message!!.contains("账户 ID"))
        assertTrue(secrets.map.isEmpty())
        // 编辑时密文留空 = 保留原值
        s.setProviderFields("azure", mapOf("AZURE_OPENAI_API_KEY" to "k-0123456789", "AZURE_OPENAI_BASE_URL" to "https://a.openai.azure.com"))
        s.setProviderFields("azure", mapOf("AZURE_OPENAI_API_KEY" to "", "AZURE_OPENAI_BASE_URL" to "https://b.openai.azure.com"))
        val az = json(paths.authJson)["azure"]!!.jsonObject
        assertEquals("k-0123456789", az["key"]!!.jsonPrimitive.content)
        assertEquals("https://b.openai.azure.com", az["env"]!!.jsonObject["AZURE_OPENAI_BASE_URL"]!!.jsonPrimitive.content)
        s.clearProvider("azure")
        assertTrue(s.env().isEmpty())
        assertNull(json(paths.authJson)["azure"])
    }

    // ---------------- 共用 Key 变量 ----------------

    @Test fun sharedEnvProvidersWriteOwnKeysToAuthJson() = runTest {
        val s = store()
        paths.authJson.parentFile.mkdirs()
        paths.authJson.writeText("""{"github-copilot":{"type":"oauth","refresh":"r","access":"a","expires":1},"mine":{"type":"api_key","key":"literal"}}""")
        s.setBuiltInKey("moonshotai", "sk-intl-0123456789")
        s.setBuiltInKey("moonshotai-cn", "sk-cn-0123456789")
        s.setProviderFields("cloudflare-workers-ai", mapOf("CLOUDFLARE_API_KEY" to "cf-workers-0123456789", "CLOUDFLARE_ACCOUNT_ID" to "acc1"))
        s.setProviderFields("cloudflare-ai-gateway", mapOf("CLOUDFLARE_API_KEY" to "cf-gw-0123456789", "CLOUDFLARE_ACCOUNT_ID" to "acc2", "CLOUDFLARE_GATEWAY_ID" to "gw"))

        val env = s.env()
        assertNull("热加载：Key 不进进程环境", env["PIGO_PK_MOONSHOTAI"])
        assertNull("共用变量不直接注入，避免互相覆盖", env["MOONSHOT_API_KEY"])
        assertNull(env["CLOUDFLARE_API_KEY"])
        assertNull(env["CLOUDFLARE_ACCOUNT_ID"])

        val auth = json(paths.authJson)
        // 各存各的 Key（与 pi /login 存 Key 的格式相同），共用环境变量的服务商也不会互相覆盖
        assertEquals("sk-cn-0123456789", auth["moonshotai-cn"]!!.jsonObject["key"]!!.jsonPrimitive.content)
        assertEquals("sk-intl-0123456789", auth["moonshotai"]!!.jsonObject["key"]!!.jsonPrimitive.content)
        assertEquals("acc2", auth["cloudflare-ai-gateway"]!!.jsonObject["env"]!!.jsonObject["CLOUDFLARE_ACCOUNT_ID"]!!.jsonPrimitive.content)
        assertEquals("oauth", auth["github-copilot"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("literal", auth["mine"]!!.jsonObject["key"]!!.jsonPrimitive.content)
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(paths.authJson.toPath()))
        assertFalse(File(paths.authJson.parentFile, "auth.json.lock").exists())

        s.clearProvider("moonshotai")
        val after = json(paths.authJson)
        assertNull(after["moonshotai"])
        assertNotNull(after["moonshotai-cn"])
        assertNotNull(after["github-copilot"])
    }

    // ---------------- 代理 ----------------

    @Test fun proxyInjectedAndValidated() = runTest {
        val s = store()
        assertTrue(s.env().isEmpty())
        expectIae { s.setProxy("socks5://127.0.0.1:1080") }
        expectIae { s.setProxy("http://127.0.0.1") }
        s.setProxy("http://user:pass@127.0.0.1:7890")
        val env = s.env()
        for (k in listOf("HTTPS_PROXY", "HTTP_PROXY", "https_proxy", "http_proxy")) assertEquals("http://user:pass@127.0.0.1:7890", env[k])
        assertEquals(PiProviderConfigStore.DEFAULT_NO_PROXY, env["NO_PROXY"])
        assertEquals("1", env["NODE_USE_ENV_PROXY"])
        val diag = s.diagnostics()
        assertFalse(diag.contains("user:pass"))
        assertTrue(diag.contains("代理：已设置"))
        s.setProxy("")
        assertTrue(s.env().isEmpty())
    }

    // ---------------- models.json 完整写法（M7） ----------------

    @Test fun customProviderFullStructureMergesWithoutLosingFields() = runTest {
        val s = store()
        paths.modelsJson.parentFile.mkdirs()
        paths.modelsJson.writeText(
            """{"providers":{
              "other":{"baseUrl":"https://x","models":[{"id":"keep"}]},
              "gw":{"baseUrl":"https://old","api":"openai-completions","apiKey":"${'$'}PIGO_KEY_GW","headers":{"X-A":"1"},
                    "models":[{"id":"a","compat":{"foo":true},"contextWindow":1000},{"id":"gone"}]}
            },"extra":1}""",
        )
        secrets.map["key.custom.gw"] = "sk-old-0123456789"
        s.upsertCustomProvider(
            "gw", "https://gw.example.com/v1/", PiProviderConfigStore.CustomApi.ANTHROPIC_MESSAGES, "",
            listOf(
                PiProviderConfigStore.CustomModel("a", name = "模型 A", reasoning = true, supportsImages = true, maxTokens = 8000,
                    cost = BuiltinCatalog.Cost(1.0, 2.0, 0.1, 0.0)),
                PiProviderConfigStore.CustomModel("b", contextWindow = 200000),
            ),
        )
        val root = json(paths.modelsJson)
        assertEquals(1, root["extra"]!!.jsonPrimitive.content.toInt())
        assertNotNull(root["providers"]!!.jsonObject["other"])
        val gw = root["providers"]!!.jsonObject["gw"]!!.jsonObject
        assertEquals("anthropic-messages", gw["api"]!!.jsonPrimitive.content)
        assertEquals("https://gw.example.com/v1", gw["baseUrl"]!!.jsonPrimitive.content)
        assertEquals("1", gw["headers"]!!.jsonObject["X-A"]!!.jsonPrimitive.content)
        val models = gw["models"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("a", "b"), models.map { it["id"]!!.jsonPrimitive.content })
        val a = models[0]
        assertEquals("true", a["compat"]!!.jsonObject["foo"]!!.jsonPrimitive.content)
        assertEquals(1000, a["contextWindow"]!!.jsonPrimitive.content.toInt())
        assertEquals("模型 A", a["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("text", "image"), a["input"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2.0, a["cost"]!!.jsonObject["output"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertFalse(paths.modelsJson.readText().contains("sk-old"))

        val read = s.customProviders()["gw"]!!
        assertEquals(PiProviderConfigStore.CustomApi.ANTHROPIC_MESSAGES, read.api)
        assertEquals(true, read.models.first().supportsImages)
        assertEquals(200000L, read.models[1].contextWindow)

        expectIae { s.removeCustom("other") }
        s.removeCustom("gw")
        assertNull(json(paths.modelsJson)["providers"]!!.jsonObject["gw"])
        assertNull(secrets.map["key.custom.gw"])
    }

    @Test fun customIdCannotShadowBuiltIn() = runTest {
        val e = expectIae { store().upsertCustom("amazon-bedrock", "https://x", "sk-0123456789", listOf("m")) }
        assertTrue(e.message!!.contains("重名"))
    }

    // ---------------- 脱敏 ----------------

    @Test fun oauthTokensAreRedacted() {
        val text = """refresh ghu_abcdefghijklmnop0123 access eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig and {"access":"tid=1;exp=2;proxy-ep=x"} Bearer abcdefghijk"""
        val out = KeyRedaction.redact(text)
        assertFalse(out.contains("ghu_abcdefghijklmnop0123"))
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(out.contains("tid=1;exp=2"))
        assertFalse(out.contains("abcdefghijk"))
        assertEquals("http://***@h:1", KeyRedaction.redactUrlCredentials("http://u:p@h:1"))
    }

    // ---------------- 偏好 ----------------

    @Test fun modelPrefsRecentOnly() = runTest {
        val f = File(rootfs, "prefs/models.json")
        f.parentFile!!.mkdirs()
        // 旧版文件里的其他键（已删除的费用换算设置）读取时忽略，保存后丢弃。
        f.writeText("""{"legacyKey":true,"recent":["a/b"]}""")
        val p = ModelPrefsStore(f, StandardTestDispatcher(testScheduler))
        assertEquals(listOf("a/b"), p.prefs.value.recent)
        for (i in 1..7) p.touchRecent("p", "m$i")
        p.touchRecent("p", "m5")
        assertEquals(listOf("p/m5", "p/m7", "p/m6", "p/m4", "p/m3"), p.prefs.value.recent)
        val reloaded = ModelPrefsStore(f, StandardTestDispatcher(testScheduler))
        assertEquals(5, reloaded.prefs.value.recent.size)
        assertFalse(f.readText().contains("legacyKey"))
    }
}
