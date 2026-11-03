package io.github.zr987459.pigo.core.runtime

import io.github.zr987459.pigo.contract.GuestProcess
import io.github.zr987459.pigo.contract.GuestSpec
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 冒烟用的最小 RPC 往返：启动 `pi --mode rpc --no-session`，依次发若干条命令，
 * 等每条带 id 的响应 `success:true`。只用于安装验证与升级冒烟，不是引擎实现。
 *
 * 分帧只按 `\n` 切行（pi 的 JSONL 输出里 U+2028/U+2029 不是行分隔符）。
 */
object RpcSmoke {
    suspend fun run(
        start: (GuestSpec) -> GuestProcess,
        piBin: String,
        commands: List<Pair<String, String>>, // id -> type
        timeoutMs: Long,
    ): Boolean = coroutineScope {
        val gp = runInterruptible(Dispatchers.IO) {
            start(GuestSpec(listOf(piBin, "--mode", "rpc", "--no-session")))
        }
        // stderr 必须持续排空
        val errJob = launch(Dispatchers.IO) { runCatching { gp.stderr.copyTo(NullOut) } }
        try {
            val ok = withTimeoutOrNull(timeoutMs) {
                val reader = BufferedReader(InputStreamReader(gp.stdout, Charsets.UTF_8))
                val pending = commands.map { it.first }.toMutableSet()
                runInterruptible(Dispatchers.IO) {
                    for ((id, type) in commands) gp.stdin.write("{\"id\":\"$id\",\"type\":\"$type\"}\n".toByteArray())
                    gp.stdin.flush()
                }
                val reading = async(Dispatchers.IO) {
                    while (pending.isNotEmpty()) {
                        val line = runInterruptible { reader.readLine() } ?: return@async false
                        val obj = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                        if (obj["type"]?.jsonPrimitive?.content != "response") continue
                        val id = obj["id"]?.jsonPrimitive?.content ?: continue
                        if (id !in pending) continue
                        if (obj["success"]?.jsonPrimitive?.booleanOrNull != true) return@async false
                        pending -= id
                    }
                    true
                }
                reading.await()
            }
            ok == true
        } finally {
            gp.stop(graceMs = 2_000)
            errJob.cancel()
        }
    }

    private object NullOut : java.io.OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }
}
