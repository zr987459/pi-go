package io.github.zr987459.pigo.core.runtime

import java.io.File

/**
 * 宿主侧目录布局（全部在 App 私有目录内）。
 *
 * - `files/rootfs`           Ubuntu rootfs（容器的 /）
 * - `files/staging`          解压中的临时目录，步骤开始时先清掉
 * - `files/downloads`        已下载/下载中的包（`.part`、`.part.src`）
 * - `files/tmp`              PROOT_TMP_DIR
 * - `files/lib`              按 SONAME 命名的库副本（libtalloc.so.2 等），只给 proot 用
 * - `files/install-markers`  每步完成标记
 * - `files/preserve`         重装/重置时暂存 /root/.pi/agent 与 /root/pi-cwd
 */
class RuntimePaths(
    val filesDir: File,
    val nativeLibDir: File,
    /** `getExternalFilesDir(null)`，绑定到容器的 /root/shared；可能为 null（外部存储不可用）。 */
    val sharedDir: File?,
) {
    val rootfs = File(filesDir, "rootfs")
    val staging = File(filesDir, "staging")
    val downloads = File(filesDir, "downloads")
    val tmp = File(filesDir, "tmp")
    val lib = File(filesDir, "lib")
    val markers = File(filesDir, "install-markers")
    val preserve = File(filesDir, "preserve")

    val proot = File(nativeLibDir, "libproot.so")
    val loader = File(nativeLibDir, "libprootloader.so")
    val loader32 = File(nativeLibDir, "libprootloader32.so")

    val runtimeRecord get() = guest("/etc/pi-go-runtime.json")

    /** `/root/x` → `files/rootfs/root/x`。拒绝 `..`，保证结果在 rootfs 内。 */
    fun guest(guestPath: String): File {
        require(guestPath.startsWith("/")) { "容器路径必须是绝对路径：$guestPath" }
        val parts = guestPath.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.none { it == ".." }) { "容器路径不能包含 ..：$guestPath" }
        return parts.fold(rootfs) { f, p -> File(f, p) }
    }
}
