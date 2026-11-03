package io.github.zr987459.pigo.core.catalog

import com.sun.net.httpserver.HttpServer
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.core.store.BuiltinCatalog
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 桌面真 pi 1.5.0 集成测试（feature-gap W2 验收「集成」「OAuth」两项）。默认跳过，CI 不跑。
 *
 * 开关：`PIGO_PI_ROOT` = 本机 `@earendil-works/pi-coding-agent@1.5.0` 的包目录（`npm i @earendil-works/pi-coding-agent@1.5.0`
 * 后的 `node_modules/@earendil-works/pi-coding-agent`）；可选 `PIGO_NODE`（默认 `node`，需 Node 22+）。
 * 与容器里跑的是同一份脚本（`src/main/assets/pigo 下的 mjs`）；OAuth 服务端用本机 mock，测试进程用
 * `node --import fetch-redirect.mjs` 把发往 github.com / claude 的 https 请求改写到 mock（生产脚本不含该文件）。
 */
class RealPiAuthIntegrationTest {
    private val piRoot = System.getenv("PIGO_PI_ROOT")?.takeIf { File(it, "package.json").isFile }
    private val node = System.getenv("PIGO_NODE") ?: "node"
    private val scripts = File("src/main/assets/pigo").absoluteFile
    private val redirect = File("src/test/resources/node/fetch-redirect.mjs").absoluteFile
    private lateinit var home: File
    private lateinit var agentDir: File
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setUp() {
        assumeTrue("未设置 PIGO_PI_ROOT，跳过真 pi 集成测试", piRoot != null)
        home = Files.createTempDirectory("pigo-auth").toFile()
        agentDir = File(home, ".pi/agent").apply { mkdirs() }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @After fun tearDown() {
        if (piRoot == null) return
        scope.cancel()
        server.stop(0)
        home.deleteRecursively()
    }

    private fun route(prefix: String, handler: (path: String, body: String) -> Pair<Int, String>) {
        server.createContext(prefix) { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val path = ex.requestURI.toString()
            requests += path to body
            val (code, resp) = handler(path, body)
            val bytes = resp.toByteArray()
            ex.responseHeaders.add("content-type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    private fun env(extra: Map<String, String> = emptyMap()) = mapOf(
        "PIGO_PI_ROOT" to piRoot!!,
        "HOME" to home.absolutePath,
        "PI_CODING_AGENT_DIR" to agentDir.absolutePath,
        "PIGO_MOCK_BASE" to "http://127.0.0.1:${server.address.port}",
        "NO_PROXY" to "*", "no_proxy" to "*",
        "PI_OAUTH_CALLBACK_HOST" to "127.0.0.1",
    ) + extra

    private fun process(vararg args: String, withRedirect: Boolean = true, extraEnv: Map<String, String> = emptyMap()): Process {
        val cmd = buildList {
            add(node)
            if (withRedirect) { add("--import"); add(redirect.toURI().toString()) }
            addAll(args)
        }
        return ProcessBuilder(cmd).apply {
            environment().putAll(env(extraEnv))
            environment().remove("HTTPS_PROXY"); environment().remove("https_proxy")
            environment().remove("HTTP_PROXY"); environment().remove("http_proxy")
        }.start()
    }

    private class ProcLines(private val p: Process) : LineProcess {
        override val lines: Flow<String> = flow {
            p.inputStream.bufferedReader().useLines { s -> s.forEach { emit(it) } }
        }.flowOn(Dispatchers.IO)
        override suspend fun send(line: String) = withContext(Dispatchers.IO) {
            p.outputStream.write((line + "\n").toByteArray()); p.outputStream.flush()
        }
        override suspend fun stop() { p.destroy() }
    }

    private fun piBin() = File(piRoot!!, "dist/bundle/cli.js").absolutePath

    // ---------------- 目录导出 ----------------

    @Test fun catalogScriptExportsAll42Providers() {
        val p = process(File(scripts, "pigo-catalog.mjs").path, withRedirect = false)
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.waitFor())
        val snap = BuiltinCatalog.parse(out)
        assertEquals("1.5.0", snap.piVersion)
        assertEquals(42, snap.providers.size)
        assertTrue(snap.modelCount > 0)
        // 与 APK 内置快照一致（同一 pi 版本）
        assertEquals(BuiltinCatalog.bundled().providers.map { it.id }, snap.providers.map { it.id })
    }

    // ---------------- GitHub Copilot 设备码 ----------------

    @Test fun copilotDeviceCodeAgainstMockThenPiAuthCheckReady() = runBlocking {
        val exp = System.currentTimeMillis() / 1000 + 3600
        var polls = 0
        route("/github.com/login/device/code") { _, _ ->
            200 to """{"device_code":"dev-1","user_code":"WXYZ-0001","verification_uri":"https://github.com/login/device","interval":1,"expires_in":60}"""
        }
        route("/github.com/login/oauth/access_token") { _, _ ->
            polls++
            200 to if (polls < 2) """{"error":"authorization_pending"}""" else """{"access_token":"ghu_mockaccesstoken0123456789"}"""
        }
        route("/api.github.com/copilot_internal/v2/token") { _, _ ->
            200 to """{"token":"tid=1;exp=$exp;proxy-ep=proxy.individual.githubcopilot.com;sig=x","expires_at":$exp}"""
        }
        route("/api.individual.githubcopilot.com/models") { _, _ ->
            200 to """{"data":[{"id":"gpt-5.5","model_picker_enabled":true,"policy":{"state":"enabled"},"capabilities":{"supports":{"tool_calls":true}}}]}"""
        }
        val proc = process(File(scripts, "pigo-auth.mjs").path, "login", "github-copilot")
        val session = OAuthLoginSession("github-copilot", ProcLines(proc), scope)
        withTimeout(30_000) {
            val prompt = session.state.first { it is OAuthLoginSession.State.AwaitingInput } as OAuthLoginSession.State.AwaitingInput
            assertEquals("text", prompt.prompt.kind)
            session.answer(prompt.prompt.id, "") // 普通 github.com
            val dc = session.state.first { it is OAuthLoginSession.State.AwaitingDeviceCode } as OAuthLoginSession.State.AwaitingDeviceCode
            assertEquals("WXYZ-0001", dc.userCode)
            val end = session.state.first { it is OAuthLoginSession.State.Succeeded || it is OAuthLoginSession.State.Failed }
            assertEquals(OAuthLoginSession.State.Succeeded, end)
        }
        // 凭据只在 auth.json（600），stdout 从未出现令牌
        val auth = File(agentDir, "auth.json")
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(auth.toPath()))
        assertEquals("oauth", Json.parseToJsonElement(auth.readText()).jsonObject["github-copilot"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val events = session.events.replayCache.joinToString("\n")
        assertFalse(events.contains("ghu_mockaccesstoken"))
        assertFalse(events.contains("tid=1"))

        val check = process(piBin(), "auth", "check", "--provider", "github-copilot", "--json", "--no-refresh", withRedirect = false)
        val out = check.inputStream.bufferedReader().readText()
        check.waitFor()
        val r = AuthService.AuthCheck.parse(out)
        assertTrue("pi auth check: $out", r.ready)
        assertEquals("oauth", r.authType)

        // 登出：pi 自己删掉条目
        val lo = process(File(scripts, "pigo-auth.mjs").path, "logout", "github-copilot", withRedirect = false)
        val loOut = lo.inputStream.bufferedReader().readText()
        assertEquals(0, lo.waitFor())
        assertTrue(OAuthProtocol.parse(loOut.trim().lines().last()) is LoginEvent.Done)
        assertFalse(Json.parseToJsonElement(auth.readText()).jsonObject.containsKey("github-copilot"))
    }

    // ---------------- Anthropic PKCE（浏览器回调） ----------------

    @Test fun anthropicPkceCallbackExchangesCodeWithVerifier() = runBlocking {
        route("/platform.claude.com/v1/oauth/token") { _, _ ->
            200 to """{"access_token":"sk-ant-oat-mockaccess0123456789","refresh_token":"sk-ant-ort-mockrefresh0123456789","expires_in":3600}"""
        }
        val proc = process(File(scripts, "pigo-auth.mjs").path, "login", "anthropic")
        val session = OAuthLoginSession("anthropic", ProcLines(proc), scope)
        withTimeout(30_000) {
            val st = session.state.first { it is OAuthLoginSession.State.AwaitingBrowser } as OAuthLoginSession.State.AwaitingBrowser
            val q = query(URI(st.url).rawQuery)
            assertEquals("S256", q["code_challenge_method"])
            val redirectUri = q["redirect_uri"]!!
            assertTrue(Pkce.isLoopbackCallback(redirectUri, redirectUri))
            // 模拟浏览器授权完成后跳回本机回调（Android 上 Custom Tab 跳到 proot 里同一端口）
            val cb = URL("$redirectUri?code=auth-code-1&state=${q["state"]}").openConnection() as HttpURLConnection
            assertTrue(cb.responseCode in 200..399)
            val end = session.state.first { it is OAuthLoginSession.State.Succeeded || it is OAuthLoginSession.State.Failed }
            assertEquals("交换失败：$end", OAuthLoginSession.State.Succeeded, end)

            val tokenReq = requests.last { it.first.startsWith("/platform.claude.com/v1/oauth/token") }.second
            val body = Json.parseToJsonElement(tokenReq).jsonObject
            assertEquals("authorization_code", body["grant_type"]!!.jsonPrimitive.content)
            assertEquals("auth-code-1", body["code"]!!.jsonPrimitive.content)
            assertEquals(redirectUri, body["redirect_uri"]!!.jsonPrimitive.content)
            assertEquals(q["state"], body["state"]!!.jsonPrimitive.content)
            // code_verifier 与授权地址里的 code_challenge 对得上（S256）
            assertEquals(q["code_challenge"], Pkce.challengeOf(body["code_verifier"]!!.jsonPrimitive.content))
        }
        val cred = Json.parseToJsonElement(File(agentDir, "auth.json").readText()).jsonObject["anthropic"]!!.jsonObject
        assertEquals("oauth", cred["type"]!!.jsonPrimitive.content)
    }

    // ---------------- DeepSeek：get_available_models 全部出现在「可用」组 ----------------

    @Test fun deepseekAvailableModelsAllShowAsAvailable() {
        val p = process(piBin(), "--mode", "rpc", "--no-session", withRedirect = false, extraEnv = mapOf("DEEPSEEK_API_KEY" to "sk-test-0123456789abcdef"))
        p.outputStream.write("""{"id":"m1","type":"get_available_models"}""".toByteArray() + "\n".toByteArray())
        p.outputStream.flush()
        val reader = p.inputStream.bufferedReader()
        var resp: JsonObject? = null
        val deadline = System.currentTimeMillis() + 60_000
        while (resp == null && System.currentTimeMillis() < deadline) {
            val line = reader.readLine() ?: break
            val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            if (o["id"]?.jsonPrimitive?.content == "m1") resp = o
        }
        p.destroy()
        val models = resp!!["data"]!!.jsonObject["models"]!!.jsonArray.map { it.jsonObject }
            .map { ModelInfo(it["provider"]!!.jsonPrimitive.content, it["id"]!!.jsonPrimitive.content) }
        assertTrue("pi 应列出 DeepSeek 模型", models.any { it.provider == "deepseek" })
        val groups = ModelIndex.build(ModelIndex.Inputs(BuiltinCatalog.bundled(), models, emptyMap(), setOf("deepseek"), emptySet()))
        val avail = ModelIndex.availableModels(groups).map { it.key }.toSet()
        for (m in models) assertTrue("${m.provider}/${m.id} 不在可用组", "${m.provider}/${m.id}" in avail)
        assertTrue(avail.all { it.startsWith("deepseek/") })
    }

    private fun query(q: String): Map<String, String> = q.split('&').associate {
        val i = it.indexOf('=')
        java.net.URLDecoder.decode(it.substring(0, i), "UTF-8") to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
    }
}
