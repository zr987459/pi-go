package io.github.zr987459.pigo.build

import java.io.File
import java.security.MessageDigest

/**
 * 许可证防回归扫描（DESIGN.md §4.1 第 8 条）。
 *
 * - 源码（各模块 `src/` 与 `buildSrc/`、`build-logic/`、根构建脚本）中出现 GPL-3.0 DSH-Folk 的包名或名称；
 * - 任意位置出现 PiMet / DSH-Folk 那组 `.so` 的已知 SHA256。
 *
 * 违规关键字用拼接写出，避免本文件自身被扫描命中。
 */
object LicenseHygiene {

    /** 源码中禁止出现的关键字。 */
    val forbiddenPatterns: List<Regex> = listOf(
        Regex(Regex.escape("me.bmax" + ".apatch")),
        Regex(Regex.escape("me/bmax" + "/apatch")),
        Regex(Regex.escape("DSH" + "-Folk"), RegexOption.IGNORE_CASE),
        Regex("\\b" + "DS" + "HA" + "\\b"),
    )

    /** PiMet `app/src/main/jniLibs/`（与 DSH-Folk `app/libs/` 相同）各 .so 的 SHA256，取自 zr987459/pimet main。 */
    val knownBadSoSha256: Set<String> = setOf(
        // arm64-v8a
        "84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731", // libandroidshmem.so
        "cbde86eb01b58413f9e4673e182caca5440e8fc4af8482a0e23d0faa0846f721", // libproot.so
        "a2cbccfc776e23225b75e6700dd1990fcd505d2e973f5bfa19c76642d4e41c7d", // libprootloader.so
        "4c649eb2dce258c9111231940fdbf0474bcf1cd6dd447ad9bdf2898b35a85554", // libprootloader32.so
        "1c5bc9537a270e8bf8b1c70222813f57b60b828bfb5503ddf8fe37685092de2f", // libproroot-bridge.so
        "51a0ec5bfed00e572a0de09e22d9057e2befc386b78e426613d3e0ab03f4ecee", // libproroot-linker.so
        "8c47a0a7db32d84c179ebb5bf3640f655a3181860ece5886ae44d92858730c34", // libproroot-runtime.so
        "06c6624db3bdc45b9ced151cd781df439a37b47731d244b93e9d6a58cd48cde0", // libproroot-stub-loader.so
        "a4e74d75b66cdc02b080adfe863dbf9951c3b30610d77beddc95488d5fe5de01", // libproroot.so
        "3c9b207c0a6ea2896b7523e03f55d9ab0d9e88baa115d4c32b84058ff4246fbb", // libtalloc.so
        // x86_64
        "092926060298acd3778e6239033d7aef1280dcb59aebe021a3719612e6a3465f", // libandroidshmem.so
        "5c6b99c48ebb87580551afd654e49eb8d178fd45fef0b65397c99a1d901c417b", // libproot.so
        "914564ea1c66f50b38f18cac857fcf814c6b1ab027789178880fca1d530599b3", // libprootloader.so
        "7fb73fa7f1879f7d210db70a0e3961161d0439e892c38bb834e6e17f162fae30", // libprootloader32.so
        "77be445f4ec245fff9c19e9874ebcf99618244cf48737f5fca938316daaa70da", // libtalloc.so
    )

    private val sourceExtensions = setOf(
        "kt", "kts", "java", "xml", "json", "ts", "js", "mjs", "sh", "properties", "toml", "pro", "gradle", "aidl",
        "c", "cc", "cpp", "h",
    )

    private val skippedDirs = setOf("build", ".gradle", ".git", ".idea", ".kotlin", "out", "node_modules")

    data class Violation(val file: File, val line: Int, val reason: String) {
        override fun toString(): String = if (line > 0) "${file.path}:$line  $reason" else "${file.path}  $reason"
    }

    fun scan(root: File, badSo: Set<String> = knownBadSoSha256): List<Violation> {
        val out = mutableListOf<Violation>()
        root.walkTopDown()
            .onEnter { dir -> dir == root || dir.name !in skippedDirs }
            .filter { it.isFile }
            .forEach { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                if (file.extension.equals("so", ignoreCase = true) || rel.contains("/jniLibs/")) {
                    val sha = sha256(file)
                    if (sha in badSo) out += Violation(file, 0, "与 PiMet/GPL 派生项目的 .so 相同（SHA256 $sha）")
                }
                if (isSource(rel, file)) scanText(file, out)
            }
        return out
    }

    private fun isSource(rel: String, file: File): Boolean {
        if (file.extension.lowercase() !in sourceExtensions) return false
        val top = rel.substringBefore('/')
        // 根目录下的构建脚本、buildSrc / build-logic，以及任何模块的 src/。
        return !rel.contains('/') || top == "buildSrc" || top == "build-logic" || top == "gradle" ||
            rel.contains("/src/")
    }

    private fun scanText(file: File, out: MutableList<Violation>) {
        if (file.length() > 8L * 1024 * 1024) return
        file.useLines(Charsets.UTF_8) { lines ->
            lines.forEachIndexed { index, line ->
                for (p in forbiddenPatterns) {
                    if (p.containsMatchIn(line)) {
                        out += Violation(file, index + 1, "出现禁止的 GPL 派生来源标识：${p.pattern}")
                        break
                    }
                }
            }
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
