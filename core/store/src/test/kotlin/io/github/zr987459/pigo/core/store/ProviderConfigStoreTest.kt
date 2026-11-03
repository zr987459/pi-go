package io.github.zr987459.pigo.core.store

import io.github.zr987459.pigo.contract.PresetKind
import io.github.zr987459.pigo.contract.SecretStore
import io.github.zr987459.pigo.contract.fake.FakeAppLog
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderConfigStoreTest {
    /** 记录全部写入的内存 SecretStore。 */
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

    private val fullKey = "sk-abcdef1234567890WXYZ"

    @Before fun setUp() {
        rootfs = Files.createTempDirectory("pigo-cfg").toFile()
        paths = GuestPaths(rootfs)
    }

    @After fun tearDown() { rootfs.deleteRecursively() }

    private fun TestScope.store() = PiProviderConfigStore(secrets, paths, StandardTestDispatcher(testScheduler))

    private fun readJson(f: File) = Json.parseToJsonElement(f.readText()).jsonObject

    // ---------------- 预设 ----------------

    @Test fun presetsGroupedIntoThreeKinds() = runTest {
        val groups = store().presets().groupBy { it.kind }
        assertEquals(PresetKind.entries.toSet(), groups.keys)
        assertTrue(groups[PresetKind.CODING_PLAN]!!.all { it.note.contains("需要套餐 Key") })
        val custom = groups[PresetKind.CUSTOM_OPENAI]!!.associateBy { it.provider }
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", custom["bailian"]!!.defaultBaseUrl)
        assertEquals("https://open.bigmodel.cn/api/paas/v4", custom["zhipu"]!!.defaultBaseUrl)
        assertEquals("DEEPSEEK_API_KEY", groups[PresetKind.PAY_AS_YOU_GO]!!.first { it.provider == "deepseek" }.envVar)
    }

    // ---------------- Key 清洗与内置 Provider ----------------

    @Test fun builtInKeyIsSanitizedAndOnlyInEnv() = runTest {
        val s = store()
        s.setBuiltInKey("deepseek", "  Bearer sk-xxxx  ")
        assertEquals("sk-xxxx", secrets.map["key.deepseek"])
        assertEquals(listOf("deepseek"), s.configured())
        assertEquals(mapOf("DEEPSEEK_API_KEY" to "sk-xxxx"), s.env())
        // 内置 Provider 不写任何容器文件，尤其是 auth.json（C2）
        assertFalse(paths.authJson.exists())
        assertFalse(paths.modelsJson.exists())
        assertTrue(rootfs.walk().none { it.isFile && it.readText().contains("sk-xxxx") })
    }

    @Test fun sanitizeVariants() = runTest {
        val s = store()
        s.setBuiltInKey("openai", "\tbearer   sk-proj-1234\n")
        assertEquals("sk-proj-1234", secrets.map["key.openai"])
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { s.setBuiltInKey("openai", "  Bearer  ") } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { s.setBuiltInKey("openai", "sk-12 34") } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { s.setBuiltInKey("nope", "sk-1234") } }
    }

    // ---------------- 自定义端点：models.json ----------------

    @Test fun upsertCustomWritesEnvRefAnd600() = runTest {
        val s = store()
        s.upsertCustom("Bailian", "https://dashscope.aliyuncs.com/compatible-mode/v1/", " Bearer $fullKey ", listOf("qwen-plus", " qwen-max ", "qwen-plus"))
        val f = paths.modelsJson
        val p = readJson(f)["providers"]!!.jsonObject["bailian"]!!.jsonObject
        assertEquals("\$PIGO_KEY_BAILIAN", p["apiKey"]!!.jsonPrimitive.content)
        assertEquals("openai-completions", p["api"]!!.jsonPrimitive.content)
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", p["baseUrl"]!!.jsonPrimitive.content)
        assertEquals(listOf("qwen-plus", "qwen-max"), p["models"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(f.toPath()),
        )
        assertFalse(f.readText().contains(fullKey)) // Key 不落容器磁盘
        assertEquals(fullKey, secrets.map["key.custom.bailian"])
        assertEquals(mapOf("PIGO_KEY_BAILIAN" to fullKey), s.env())
        assertEquals(listOf("bailian"), s.configured())
        assertFalse(File(f.parentFile, ".models.json.pigo-tmp").exists())
    }

    @Test fun upsertCustomMergesWithoutLosingEntries() = runTest {
        paths.agentDir.mkdirs()
        paths.modelsJson.writeText(
            """
            {
              "${'$'}schema": "https://pi.dev/schemas/models.schema.json",
              "providers": {
                "ollama": {"baseUrl": "http://127.0.0.1:11434/v1", "api": "openai-completions", "apiKey": "ollama", "models": [{"id": "llama3"}]},
                "zhipu": {"baseUrl": "https://old", "apiKey": "${'$'}PIGO_KEY_ZHIPU", "headers": {"X-A": "1"},
                          "models": [{"id": "glm-4.6", "contextWindow": 128000}, {"id": "glm-old"}]}
              },
              "userTopLevel": 42
            }
            """.trimIndent(),
        )
        val s = store()
        s.upsertCustom("zhipu", "https://open.bigmodel.cn/api/paas/v4", fullKey, listOf("glm-4.6", "glm-4.5-air"))
        val root = readJson(paths.modelsJson)
        assertEquals("https://pi.dev/schemas/models.schema.json", root["\$schema"]!!.jsonPrimitive.content)
        assertEquals(42, root["userTopLevel"]!!.jsonPrimitive.content.toInt())
        val providers = root["providers"]!!.jsonObject
        // 其他 provider 原样保留
        assertEquals("ollama", providers["ollama"]!!.jsonObject["apiKey"]!!.jsonPrimitive.content)
        val z = providers["zhipu"]!!.jsonObject
        assertEquals("https://open.bigmodel.cn/api/paas/v4", z["baseUrl"]!!.jsonPrimitive.content)
        assertEquals("1", z["headers"]!!.jsonObject["X-A"]!!.jsonPrimitive.content) // 同一 provider 的其他字段保留
        val models = z["models"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("glm-4.6", "glm-4.5-air"), models.map { it["id"]!!.jsonPrimitive.content })
        assertEquals(128000, models[0]["contextWindow"]!!.jsonPrimitive.content.toInt()) // 已有模型附加字段保留
        // 不是 pi-go 写的条目不算「已配置」，也不注入环境变量
        assertEquals(listOf("zhipu"), s.configured())
        assertEquals(setOf("PIGO_KEY_ZHIPU"), s.env().keys)
    }

    @Test fun editingCustomWithBlankKeyKeepsOldKey() = runTest {
        val s = store()
        s.upsertCustom("my-api", "https://api.example.com/v1", fullKey, listOf("m1"))
        s.upsertCustom("my-api", "https://api.example.com/v2", "   ", listOf("m1", "m2"))
        assertEquals(fullKey, secrets.map["key.custom.my-api"])
        assertEquals(mapOf("PIGO_KEY_MY_API" to fullKey), s.env())
    }

    @Test fun customIdsMappingToSameEnvVarAreRejected() = runTest {
        val s = store()
        s.upsertCustom("my-api", "https://api.example.com/v1", fullKey, listOf("m1"))
        val e = assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                PiProviderConfigStore(secrets, paths).upsertCustom("my_api", "https://other.example.com/v1", "sk-other-key-0000", listOf("m2"))
            }
        }
        assertTrue(e.message!!.contains("PIGO_KEY_MY_API"))
        assertTrue(e.message!!.contains("my-api"))
        // 原条目与原 Key 不受影响，也没有写入冲突条目
        val providers = readJson(paths.modelsJson)["providers"]!!.jsonObject
        assertEquals(setOf("my-api"), providers.keys)
        assertEquals(fullKey, secrets.map["key.custom.my-api"])
        assertNull(secrets.map["key.custom.my_api"])
        // 同一个 id 再次保存（编辑）不算冲突
        s.upsertCustom("my-api", "https://api.example.com/v2", "", listOf("m1", "m3"))
        assertEquals(mapOf("PIGO_KEY_MY_API" to fullKey), s.env())
    }

    @Test fun customValidation() = runTest {
        val s = store()
        fun bad(block: suspend () -> Unit) =
            assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { block() } }
        bad { s.upsertCustom("deepseek", "https://x", fullKey, listOf("m")) } // 与内置重名
        bad { s.upsertCustom("custom", "https://x", fullKey, listOf("m")) }
        bad { s.upsertCustom("!!!", "https://x", fullKey, listOf("m")) }
        bad { s.upsertCustom("ok", "ftp://x", fullKey, listOf("m")) }
        bad { s.upsertCustom("ok", "https://x", fullKey, listOf(" ")) }
        bad { s.upsertCustom("ok", "https://x", "", listOf("m")) } // 新建时必须有 Key
        assertFalse(paths.modelsJson.exists())
        assertTrue(secrets.map.isEmpty())
    }

    @Test fun brokenModelsJsonIsNeverOverwritten() = runTest {
        paths.agentDir.mkdirs()
        val broken = "{ \"providers\": { oops"
        paths.modelsJson.writeText(broken)
        val s = store()
        assertThrows(JsonFiles.BrokenConfigException::class.java) {
            kotlinx.coroutines.runBlocking {
                PiProviderConfigStore(secrets, paths).upsertCustom("x", "https://x", fullKey, listOf("m"))
            }
        }
        assertEquals(broken, paths.modelsJson.readText())
        assertNull(secrets.map["key.custom.x"]) // 文件没写成功就不存 Key
        assertTrue(s.configured().isEmpty())
    }

    // ---------------- settings.json ----------------

    @Test fun setDefaultMergesSettings() = runTest {
        paths.agentDir.mkdirs()
        paths.settingsJson.writeText("""{"theme":"dark","defaultThinkingLevel":"high","packages":["a"]}""")
        val s = store()
        s.setDefault("deepseek", "deepseek-chat", null)
        var root = readJson(paths.settingsJson)
        assertEquals("dark", root["theme"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonPrimitive("a"))), root["packages"])
        assertEquals("deepseek", root["defaultProvider"]!!.jsonPrimitive.content)
        assertEquals("deepseek-chat", root["defaultModel"]!!.jsonPrimitive.content)
        assertEquals("high", root["defaultThinkingLevel"]!!.jsonPrimitive.content) // null = 不改
        s.setDefault("bailian", "qwen-plus", "low")
        root = readJson(paths.settingsJson)
        assertEquals("low", root["defaultThinkingLevel"]!!.jsonPrimitive.content)
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(paths.settingsJson.toPath()),
        )
    }

    // ---------------- 脱敏 ----------------

    @Test fun maskShowsOnlyLast4() {
        assertEquals("••••WXYZ", KeyRedaction.mask(fullKey))
        assertEquals("WXYZ", KeyRedaction.last4(fullKey))
        assertEquals("••••", KeyRedaction.mask("abc123")) // 太短：不显示任何字符
        assertNull(KeyRedaction.last4("abc123"))
        assertEquals("未设置", KeyRedaction.mask(null))
    }

    @Test fun diagnosticsAndLogsNeverContainFullKey() = runTest {
        val s = store()
        val oddKey = "AKIDzz99887766554433" // 不像 sk- 的 Key 也要靠「已知 Key」脱敏
        s.setBuiltInKey("deepseek", "Bearer $fullKey")
        s.upsertCustom("bailian", "https://dashscope.aliyuncs.com/compatible-mode/v1", oddKey, listOf("qwen-plus"))
        val diag = s.diagnostics()
        assertFalse(diag.contains(fullKey))
        assertFalse(diag.contains(oddKey))
        assertTrue(diag.contains("••••WXYZ"))
        assertTrue(diag.contains("••••4433"))

        // 日志兜底：已知 Key 与常见形态都被替换
        val line = "请求失败 Authorization: Bearer $fullKey key=$oddKey"
        val red = KeyRedaction.redact(line, s.env().values)
        assertFalse(red.contains(fullKey))
        assertFalse(red.contains(oddKey))
        assertFalse(red.contains("abcdef1234"))

        // 与 AppLog 的基础脱敏组合使用时同样不泄露
        val log = FakeAppLog()
        log.i("store", KeyRedaction.redact("保存了 $fullKey", s.env().values))
        assertTrue(log.tail(10).none { it.message.contains(fullKey) })
    }

    @Test fun normalizeIdAndEnvVar() {
        assertEquals("my-api", ProviderPresets.normalizeCustomId(" My API "))
        assertEquals("PIGO_KEY_MY_API", ProviderPresets.customEnvVar("my-api"))
        assertEquals("PIGO_KEY_BAILIAN", ProviderPresets.customEnvVar("bailian"))
    }

    @Test fun jsonObjectHelperRejectsNonObject() {
        val f = File(rootfs, "a.json").apply { writeText("[1]") }
        assertThrows(JsonFiles.BrokenConfigException::class.java) { JsonFiles.readObject(f) }
        assertEquals(JsonObject(emptyMap()), JsonFiles.readObject(File(rootfs, "missing.json")))
    }
}
