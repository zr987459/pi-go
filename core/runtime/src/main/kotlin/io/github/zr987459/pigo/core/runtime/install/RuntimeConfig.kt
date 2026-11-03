package io.github.zr987459.pigo.core.runtime.install

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 容器架构：Ubuntu 与 Node 的包名不同。 */
enum class GuestArch(val abi: String, val ubuntu: String, val node: String) {
    ARM64("arm64-v8a", "arm64", "arm64"),
    X86_64("x86_64", "amd64", "x64"),
    ;

    companion object {
        fun fromAbis(abis: List<String>): GuestArch? = abis.firstNotNullOfOrNull { a -> entries.firstOrNull { it.abi == a } }
    }
}

/** `app/src/main/assets/pins.json`（scaffold 提供）中运行环境需要的部分。 */
data class Pins(
    val piPackage: String,
    val piVersion: String,
    val piIntegrity: String,
    val piCompat: String,
    val piBlocked: List<String>,
    val nodeVersion: String,
    val nodeSha256: Map<String, String>,
    val ubuntuVersion: String,
    val ubuntuSha256: Map<String, String>,
) {
    fun ubuntuFile(arch: GuestArch) = "ubuntu-base-$ubuntuVersion-base-${arch.ubuntu}.tar.gz"
    fun nodeFile(arch: GuestArch) = "node-v$nodeVersion-linux-${arch.node}.tar.gz"
    fun ubuntuHash(arch: GuestArch) = ubuntuSha256[arch.ubuntu] ?: error("pins.json 缺少 ubuntu ${arch.ubuntu} 哈希")
    fun nodeHash(arch: GuestArch) = nodeSha256[arch.node] ?: error("pins.json 缺少 node ${arch.node} 哈希")

    companion object {
        fun parse(text: String): Pins {
            val o = Json.parseToJsonElement(text).jsonObject
            val pi = o.getValue("pi").jsonObject
            val node = o.getValue("node").jsonObject
            val ubuntu = o.getValue("ubuntu").jsonObject
            return Pins(
                piPackage = pi.str("package"),
                piVersion = pi.str("version"),
                piIntegrity = pi.str("integrity"),
                piCompat = pi.str("compat"),
                piBlocked = (pi["blocked"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                nodeVersion = node.str("version"),
                nodeSha256 = node.getValue("sha256").jsonObject.mapValues { it.value.jsonPrimitive.content.lowercase() },
                ubuntuVersion = ubuntu.str("version"),
                ubuntuSha256 = ubuntu.getValue("sha256").jsonObject.mapValues { it.value.jsonPrimitive.content.lowercase() },
            )
        }
    }
}

data class Mirror(val name: String, val base: String) {
    fun url(file: String, version: String = ""): String = base.replace("{version}", version) + file
}

/** `core/runtime/src/main/assets/mirrors.json`。 */
data class Mirrors(
    val ubuntuBase: List<Mirror>,
    val ubuntuBaseOfficial: List<Mirror>,
    val node: List<Mirror>,
    val nodeOfficial: List<Mirror>,
    val apt: Map<String, String>,
    val npmRegistry: String,
    val dns: List<String>,
) {
    companion object {
        fun parse(text: String): Mirrors {
            val o = Json.parseToJsonElement(text).jsonObject
            fun list(key: String) = (o[key] as? JsonArray)?.map {
                val m = it.jsonObject
                Mirror(m.str("name"), m.str("base"))
            } ?: emptyList()
            return Mirrors(
                ubuntuBase = list("ubuntuBase"),
                ubuntuBaseOfficial = list("ubuntuBaseOfficial"),
                node = list("node"),
                nodeOfficial = list("nodeOfficial"),
                apt = o.getValue("apt").jsonObject.mapValues { it.value.jsonPrimitive.content },
                npmRegistry = o["npmRegistry"]?.jsonPrimitive?.content ?: "https://registry.npmmirror.com/",
                dns = o["dns"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf("223.5.5.5", "119.29.29.29"),
            )
        }
    }
}

private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

/** `>=1.5.0 <2.0.0` 这种最简单的区间判断（只支持空格分隔的 >=、<=、>、<、=）。 */
object SemVer {
    fun parse(v: String): List<Int>? = v.trim().removePrefix("v").substringBefore('-').split('.')
        .takeIf { it.size == 3 }?.map { it.toIntOrNull() ?: return null }

    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until 3) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }

    fun satisfies(version: String, range: String): Boolean {
        val v = parse(version) ?: return false
        if (version.contains('-')) return false // 预发布版本不自动升级
        return range.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { c ->
            val op = c.takeWhile { it in "<>=" }
            val target = parse(c.drop(op.length)) ?: return false
            val r = compare(v, target)
            when (op) {
                ">=" -> r >= 0
                ">" -> r > 0
                "<=" -> r <= 0
                "<" -> r < 0
                "=", "" -> r == 0
                else -> false
            }
        }
    }
}
