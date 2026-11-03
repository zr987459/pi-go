package io.github.zr987459.pigo.core.runtime.util

import java.io.File
import java.security.MessageDigest

object Hashes {
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

    /**
     * 从 `SHA256SUMS` / `SHASUMS256.txt` 中取某个文件名的哈希（两种格式：`<hash>  name` 与 `<hash> *name`）。
     * 找不到返回 null。
     */
    fun lookupSum(sums: String, fileName: String): String? = sums.lineSequence()
        .map { it.trim() }
        .mapNotNull { line ->
            val m = SUM_LINE.matchEntire(line) ?: return@mapNotNull null
            m.groupValues[1].lowercase() to m.groupValues[2]
        }
        .firstOrNull { it.second == fileName }
        ?.first

    private val SUM_LINE = Regex("([0-9a-fA-F]{64})\\s+\\*?(\\S+)")
}
