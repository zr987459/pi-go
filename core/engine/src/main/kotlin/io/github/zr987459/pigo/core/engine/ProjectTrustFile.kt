package io.github.zr987459.pigo.core.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File
import java.io.IOException

/**
 * pi 1.5.0 的项目信任文件 `<agentDir>/trust.json`（`core/trust-manager.js` 的 `ProjectTrustStore`）。
 *
 * RPC 模式没有界面，pi 遇到带 `.pi/` 资源的目录时直接判为不信任、也不询问（docs/protocol/r3-measurements.md §2）。
 * 用户在 App 里确认信任某目录后，用本类按 pi 的格式写入；**启动引擎前**写好才生效（pi 在进程内缓存了判定，
 * reload 不会重新判定），对正在运行的目录改信任要走 §5.5 的重启确认。
 *
 * 格式与 pi 一致：键 = 目录绝对路径，值 `true`/`false`/`null`；按键排序、2 空格缩进、末尾换行。
 * 互斥：与 pi 用同一个锁目录 `trust.json.lock`（proper-lockfile 的 mkdir 锁，10 秒视为过期）。
 * 读到非法内容时抛 [IOException]，不覆盖用户文件。
 *
 * @param file 宿主上的 `trust.json`（`runtime.hostFile("<agentDir>/trust.json")`）
 */
class ProjectTrustFile(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {

    /** [dir] 的信任决定：true / false / null（没有记录）。按 pi 的规则向上找最近的祖先记录。 */
    fun get(dir: String): Boolean? {
        val data = read()
        var cur = normalize(dir)
        while (true) {
            data[cur]?.let { return it }
            if (cur == "/") return null
            cur = cur.substringBeforeLast('/').ifEmpty { "/" }
        }
    }

    /** 设置 [dir] 的信任（null = 清除记录，回到「询问」）。保留其他目录的记录。 */
    fun set(dir: String, trusted: Boolean?) = withLock {
        val data = LinkedHashMap<String, Boolean?>(read())
        val key = normalize(dir)
        if (trusted == null) data.remove(key) else data[key] = trusted
        write(data)
    }

    internal fun read(): Map<String, Boolean?> {
        if (!file.isFile) return emptyMap()
        val text = file.readText().removePrefix("\uFEFF")
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw IOException("trust.json 不是 JSON 对象，未修改")
        val out = LinkedHashMap<String, Boolean?>()
        for ((k, v) in o) {
            out[k] = when {
                v is JsonNull -> null
                v is JsonPrimitive && v.booleanOrNull != null -> v.booleanOrNull
                else -> throw IOException("trust.json 中 $k 的值不是 true/false/null，未修改")
            }
        }
        return out
    }

    private fun write(data: Map<String, Boolean?>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.${System.nanoTime()}.part")
        try {
            tmp.writeText(render(data))
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw IOException("写入 trust.json 失败")
            }
        } finally {
            tmp.delete()
        }
    }

    private inline fun <T> withLock(block: () -> T): T {
        val lock = File(file.parentFile, file.name + ".lock")
        file.parentFile?.mkdirs()
        var acquired = false
        for (attempt in 0 until 50) {
            if (lock.mkdir()) { acquired = true; break }
            // pi 崩溃留下的锁：proper-lockfile 默认 10 秒过期。
            if (lock.exists() && clock() - lock.lastModified() > STALE_MS) lock.delete()
            Thread.sleep(20)
        }
        if (!acquired) throw IOException("trust.json 被占用")
        try {
            return block()
        } finally {
            lock.delete()
        }
    }

    companion object {
        const val FILE_NAME = "trust.json"
        const val STALE_MS = 10_000L

        /** 去掉多余的 `/`、`.`、`..`（只做字面规范化：容器内的符号链接 App 侧无法解析）。 */
        fun normalize(dir: String): String {
            require(dir.startsWith("/")) { "需要绝对路径：$dir" }
            val parts = ArrayList<String>()
            for (p in dir.split('/')) when (p) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += p
            }
            return "/" + parts.joinToString("/")
        }

        /** 与 pi 的 `JSON.stringify(sorted, null, 2) + "\n"` 一致。 */
        fun render(data: Map<String, Boolean?>): String {
            if (data.isEmpty()) return "{}\n"
            val sb = StringBuilder("{\n")
            val keys = data.keys.sorted()
            keys.forEachIndexed { i, k ->
                sb.append("  ").append(JsonPrimitive(k).toString()).append(": ").append(data[k]?.toString() ?: "null")
                if (i < keys.size - 1) sb.append(',')
                sb.append('\n')
            }
            return sb.append("}\n").toString()
        }
    }
}
