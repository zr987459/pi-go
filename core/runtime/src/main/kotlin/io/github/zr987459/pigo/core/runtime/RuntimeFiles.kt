package io.github.zr987459.pigo.core.runtime

import io.github.zr987459.pigo.core.runtime.util.Hashes
import java.io.File

/**
 * 按动态链接器要找的名字准备库副本（DESIGN.md §4.2 `RuntimeFiles`，按 ELF 规范重新编写）。
 *
 * Android 打包只接受 `lib*.so` 文件名，所以 Termux 的库在 APK 里被改了名；而 `libproot.so` 的
 * `DT_NEEDED` 写的是原名：`libtalloc.so.2`、`libandroid-shmem.so`。这里把 nativeLibDir 中的文件
 * 复制成原名放进 `files/lib`，再通过 proot 自己的 `LD_LIBRARY_PATH`（见 [ProotLauncher.hostEnv]）加载。
 */
object RuntimeFiles {
    /** nativeLibDir 中的文件名 → 动态链接器查找的名字。 */
    val SONAME_COPIES: Map<String, String> = linkedMapOf(
        "libtalloc.so" to "libtalloc.so.2",
        "libandroidshmem.so" to "libandroid-shmem.so",
    )

    data class Report(val copied: List<String>, val missing: List<String>)

    fun ensure(paths: RuntimePaths): Report {
        paths.lib.mkdirs()
        paths.tmp.mkdirs()
        val copied = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for ((apkName, soname) in SONAME_COPIES) {
            val src = File(paths.nativeLibDir, apkName)
            val dst = File(paths.lib, soname)
            if (!src.isFile) {
                missing += apkName
                continue
            }
            if (dst.isFile && dst.length() == src.length() && Hashes.sha256(dst) == Hashes.sha256(src)) continue
            val tmp = File(paths.lib, "$soname.tmp")
            src.copyTo(tmp, overwrite = true)
            tmp.setReadable(true, true)
            tmp.setExecutable(true, true)
            if (!tmp.renameTo(dst)) {
                dst.delete()
                tmp.renameTo(dst)
            }
            copied += soname
        }
        return Report(copied, missing)
    }

    /** APK（nativeLibDir）中缺少的、proot 启动必需的库（按 APK 内文件名）。 */
    fun missingLibs(paths: RuntimePaths): List<String> =
        SONAME_COPIES.keys.filter { !File(paths.nativeLibDir, it).isFile }

    /**
     * `files/lib` 中按 SONAME 命名的副本是否都在、且与 APK 中的源文件长度一致（只做 stat，不读内容、不算哈希；
     * [GuestRunner.start] 用）。完整的 SHA256 比对在安装/修复流程的 [ensure] 里做。
     */
    fun copiesPresent(paths: RuntimePaths): Boolean = SONAME_COPIES.all { (apkName, soname) ->
        val src = File(paths.nativeLibDir, apkName)
        val dst = File(paths.lib, soname)
        src.isFile && dst.isFile && dst.length() == src.length()
    }

    /**
     * proot、loader 以及 `libproot.so` 的 `DT_NEEDED` 依赖库是否都在（PREFLIGHT 用）。
     * 缺任何一个 proot 都无法在真机上启动，必须视为硬失败，不能只记警告。
     */
    fun prootAvailable(paths: RuntimePaths): Boolean =
        paths.proot.isFile && paths.proot.canExecute() && paths.loader.isFile && missingLibs(paths).isEmpty()
}
