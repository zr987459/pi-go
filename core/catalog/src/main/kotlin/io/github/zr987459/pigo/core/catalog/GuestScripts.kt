package io.github.zr987459.pigo.core.catalog

import io.github.zr987459.pigo.contract.RuntimeManager
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * 把 APK 里的容器脚本（`assets/pigo 下的 mjs`）放进容器 `/opt/pi-go/scripts/`。内容没变就不重写；文件权限 700。
 */
class GuestScripts(
    private val runtime: RuntimeManager,
    private val openAsset: (String) -> InputStream,
) {
    companion object {
        const val DIR = "/opt/pi-go/scripts"
        val FILES = listOf("pigo-common.mjs", "pigo-catalog.mjs", "pigo-auth.mjs")
        const val CATALOG = "$DIR/pigo-catalog.mjs"
        const val AUTH = "$DIR/pigo-auth.mjs"
        const val NODE = "/usr/local/bin/node"
    }

    @Synchronized
    fun ensure() {
        for (name in FILES) {
            val bytes = openAsset("pigo/$name").use { it.readBytes() }
            val target = runtime.hostFile("$DIR/$name")
            if (target.isFile && sha(target.readBytes()) == sha(bytes)) continue
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, ".$name.tmp")
            tmp.writeBytes(bytes)
            tmp.setReadable(false, false); tmp.setReadable(true, true)
            tmp.setExecutable(false, false); tmp.setExecutable(true, true)
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        }
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
