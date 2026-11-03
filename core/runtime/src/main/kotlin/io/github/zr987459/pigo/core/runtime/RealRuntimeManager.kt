package io.github.zr987459.pigo.core.runtime

import android.content.Context
import io.github.zr987459.pigo.contract.AppLog
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.GuestProcess
import io.github.zr987459.pigo.contract.GuestSpec
import io.github.zr987459.pigo.contract.InstallProgress
import io.github.zr987459.pigo.contract.InstallStep
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.Phase
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.RuntimeStatus
import io.github.zr987459.pigo.core.runtime.install.DomesticInstaller
import io.github.zr987459.pigo.core.runtime.install.GuestExec
import io.github.zr987459.pigo.core.runtime.install.HostInfo
import io.github.zr987459.pigo.core.runtime.install.InstallFailure
import io.github.zr987459.pigo.core.runtime.install.Mirrors
import io.github.zr987459.pigo.core.runtime.install.Pins
import io.github.zr987459.pigo.core.runtime.install.SemVer
import io.github.zr987459.pigo.core.runtime.net.Downloader
import io.github.zr987459.pigo.core.runtime.net.MirrorProbe
import io.github.zr987459.pigo.core.runtime.util.SafeFs
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/**
 * [RuntimeManager] 的真实实现：PRoot + Ubuntu 24.04 + Node 24 + pi（DESIGN.md §4）。
 *
 * 平台相关部分（Context、通知、信号）集中在构造参数与 [android] 工厂里，其余逻辑在纯 JVM 类中，便于单测。
 */
class RealRuntimeManager internal constructor(
    private val paths: RuntimePaths,
    private val pins: Pins,
    private val mirrors: Mirrors,
    private val host: HostInfo,
    private val runner: GuestRunner,
    private val http: OkHttpClient,
    private val log: AppLog,
    private val onInstallProgress: (InstallProgress?) -> Unit = {},
) : RuntimeManager {

    private val installLock = Mutex()
    private val badUrls: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    private val markers = DomesticInstaller.Markers(paths.markers)

    private val _status = MutableStateFlow(computeStatus())
    override val status: StateFlow<RuntimeStatus> = _status.asStateFlow()

    private val guestExec = object : GuestExec {
        override suspend fun exec(cmd: List<String>, env: Map<String, String>, timeoutMs: Long): ExecResult =
            runner.exec(cmd, env, timeoutMs)

        override suspend fun rpcHandshake(timeoutMs: Long): Boolean =
            RpcSmoke.run(runner::start, "pi", listOf("v-1" to "get_state"), timeoutMs)
    }

    private fun installer(): DomesticInstaller = DomesticInstaller(
        paths = paths,
        pins = pins,
        mirrors = mirrors,
        downloader = Downloader(http),
        probe = MirrorProbe(http),
        host = host,
        guest = guestExec,
        log = log,
        badUrls = badUrls,
    )

    override fun install(): Flow<InstallProgress> = flow {
        if (!installLock.tryLock()) throw InstallFailure(ErrCode.INSTALL_FAILED, "安装已在进行中")
        try {
            _status.value = RuntimeStatus(Phase.INSTALLING, null, null, null, null)
            installer().run().collect { p ->
                onInstallProgress(p)
                emit(p)
            }
            _status.value = computeStatus()
        } catch (e: Throwable) {
            val msg = when (e) {
                is CancellationException -> "安装已中止，可稍后继续"
                is InstallFailure -> e.message
                else -> "安装失败：${e.message ?: e.javaClass.simpleName}"
            }
            log.w(TAG, msg ?: "安装失败", e.takeUnless { it is CancellationException })
            _status.value = computeStatus().copy(error = msg)
            throw e
        } finally {
            onInstallProgress(null)
            installLock.unlock()
        }
    }

    override suspend fun repair(): Outcome<Unit> = withContext(Dispatchers.IO) {
        invalidateStaleMarkers()
        try {
            install().collect()
            Outcome.Ok(Unit)
        } catch (e: InstallFailure) {
            Outcome.Err(e.code, e.message ?: "修复失败", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Err(ErrCode.INSTALL_FAILED, e.message ?: "修复失败", e)
        }
    }

    /** 标记还在但产物已经缺失/损坏的步骤，清掉标记以便重跑。 */
    internal fun invalidateStaleMarkers() {
        if (!File(paths.rootfs, "usr/bin").isDirectory || !paths.guest("/bin/sh").let { SafeFs.exists(it) }) {
            markers.clearAll()
            return
        }
        if (!SafeFs.exists(paths.guest("/opt/node/current/bin/node"))) markers.clear(InstallStep.NODE)
        if (!SafeFs.exists(paths.guest("/opt/pi-go/pi/current/bin/pi")) || !SafeFs.exists(paths.guest("/usr/local/bin/pi"))) {
            markers.clear(InstallStep.PI)
        }
        markers.clear(InstallStep.VERIFY)
    }

    override suspend fun upgradePi(version: String): Outcome<String> = withContext(Dispatchers.IO) {
        if (!SemVer.satisfies(version, pins.piCompat) || version in pins.piBlocked) {
            return@withContext Outcome.Err(ErrCode.RPC_REJECTED, "pi $version 不在兼容范围 ${pins.piCompat} 内或已被屏蔽")
        }
        installLock.withLock {
            val base = "/opt/pi-go/pi"
            val prefix = "$base/$version"
            val previous = SafeFs.readSymlink(paths.guest("$base/current"))
            try {
                val metaUrl = mirrors.npmRegistry.trimEnd('/') + "/" + pins.piPackage + "/" + version
                val meta = Json.parseToJsonElement(Downloader(http).fetchText(metaUrl)).jsonObject
                if (meta["hasInstallScript"]?.jsonPrimitive?.content == "true") {
                    return@withContext Outcome.Err(ErrCode.RPC_REJECTED, "pi $version 含 install 脚本，不能用 --ignore-scripts 安装，已拒绝自动升级")
                }
                if (!SafeFs.exists(paths.guest("$prefix/bin/pi"))) {
                    val r = runner.exec(
                        listOf("npm", "install", "-g", "--prefix", prefix, "--ignore-scripts", "--no-audit", "--no-fund",
                            "--registry", mirrors.npmRegistry, "${pins.piPackage}@$version"),
                        timeoutMs = 20 * 60_000,
                    )
                    if (r.exitCode != 0) throw InstallFailure(ErrCode.INSTALL_FAILED, "npm 安装失败：${r.stderr.takeLast(300)}")
                }
                val v = runner.exec(listOf("$prefix/bin/pi", "--version"), timeoutMs = 60_000)
                if (v.stdout.trim() != version) throw InstallFailure(ErrCode.INSTALL_FAILED, "新版本 --version 输出不符：${v.stdout.trim()}")
                val rpcOk = RpcSmoke.run(runner::start, "$prefix/bin/pi", listOf("u-1" to "get_state", "u-2" to "get_available_models"), 30_000)
                if (!rpcOk) throw InstallFailure(ErrCode.ENGINE_START, "新版本 RPC 冒烟失败")
                // 冒烟通过才切软链（原子 rename）；引擎的停止/重启由调用方（engine）负责
                SafeFs.replaceSymlink(paths.guest("$base/current"), version)
                SafeFs.replaceSymlink(paths.guest("/usr/local/bin/pi"), "$base/current/bin/pi")
                pruneOldPi(base, keep = setOfNotNull(version, previous))
                rewriteRecordPi(version)
                _status.value = computeStatus()
                Outcome.Ok(version)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 旧软链未动；删掉半装的新目录（不是当前版本时）
                if (previous != version) SafeFs.deleteTree(paths.guest(prefix))
                val code = (e as? InstallFailure)?.code ?: ErrCode.INSTALL_FAILED
                Outcome.Err(code, e.message ?: "升级失败", e)
            }
        }
    }

    /** 只保留最近 2 个版本（当前 + 上一个）。 */
    private fun pruneOldPi(base: String, keep: Set<String>) {
        val dir = paths.guest(base)
        dir.listFiles().orEmpty()
            .filter { it.name != "current" && it.name !in keep && !it.name.startsWith(".") }
            .forEach { SafeFs.deleteTree(it) }
    }

    private fun rewriteRecordPi(version: String) {
        val f = paths.runtimeRecord
        if (!f.isFile) return
        f.writeText(f.readText().replace(Regex("\"pi\"\\s*:\\s*\"[^\"]*\""), "\"pi\":\"$version\""))
    }

    override fun start(spec: GuestSpec): GuestProcess = runner.start(spec)

    override suspend fun exec(cmd: List<String>, env: Map<String, String>, timeoutMs: Long): ExecResult =
        runner.exec(cmd, env, timeoutMs)

    override fun hostFile(guestPath: String): File = paths.guest(guestPath)

    /** 由标记与 `/etc/pi-go-runtime.json` 推出当前状态；与 pins.json 不一致时在 error 中提示（§4.6）。 */
    internal fun computeStatus(): RuntimeStatus {
        val record = paths.runtimeRecord
        if (record.isFile && markers.done(InstallStep.VERIFY)) {
            val o = runCatching { Json.parseToJsonElement(record.readText()).jsonObject }.getOrNull()
            val ubuntu = o?.get("ubuntu")?.jsonPrimitive?.content
            val node = o?.get("node")?.jsonPrimitive?.content
            val pi = o?.get("pi")?.jsonPrimitive?.content
            val drift = when {
                ubuntu != pins.ubuntuVersion || node != "v${pins.nodeVersion}" -> "运行环境与当前版本内置的 Ubuntu/Node 版本不一致，可在设置中更新"
                pi != null && SemVer.parse(pi)?.let { SemVer.compare(it, SemVer.parse(pins.piVersion)!!) < 0 } == true ->
                    "pi 版本低于内置的 ${pins.piVersion}，可在设置中更新"
                else -> null
            }
            return RuntimeStatus(Phase.READY, ubuntu, node, pi, drift)
        }
        val any = InstallStep.entries.any { markers.done(it) } || SafeFs.exists(paths.rootfs)
        return if (any) RuntimeStatus(Phase.BROKEN, null, null, null, "安装尚未完成，可继续安装")
        else RuntimeStatus(Phase.NOT_INSTALLED, null, null, null, null)
    }

    companion object {
        private const val TAG = "Runtime"

        /** Android 装配入口（di/RuntimeBindings.real() 调用）。 */
        fun android(context: Context, log: AppLog): RealRuntimeManager {
            val app = context.applicationContext
            val paths = RuntimePaths(
                filesDir = app.filesDir,
                nativeLibDir = File(app.applicationInfo.nativeLibraryDir),
                sharedDir = runCatching { app.getExternalFilesDir(null) }.getOrNull(),
            )
            val pins = Pins.parse(app.assets.open("pins.json").bufferedReader().use { it.readText() })
            val mirrors = Mirrors.parse(app.assets.open("mirrors.json").bufferedReader().use { it.readText() })
            val host = AndroidHost(app)
            val http = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val runner = GuestRunner(paths, host.uid, host.signaller)
            var lastPct = -1
            var lastAt = 0L
            return RealRuntimeManager(paths, pins, mirrors, host, runner, http, log) { p ->
                if (p == null) {
                    lastPct = -1
                    InstallService.stop(app)
                } else {
                    val now = System.currentTimeMillis()
                    // 通知更新节流：百分比变化或每 2 秒一次
                    if (p.percent != lastPct || now - lastAt > 2_000) {
                        lastPct = p.percent
                        lastAt = now
                        InstallService.update(app, p.message, p.percent)
                    }
                }
            }
        }
    }
}
