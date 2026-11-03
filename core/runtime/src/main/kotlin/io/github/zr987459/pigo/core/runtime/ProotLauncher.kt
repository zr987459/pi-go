package io.github.zr987459.pigo.core.runtime

import io.github.zr987459.pigo.contract.GuestSpec

/**
 * PRoot 启动参数（DESIGN.md §4.4）。纯函数，便于单测。
 *
 * 容器内用 `env -i` 重建环境：宿主的 LD_LIBRARY_PATH 等不会漏进容器；
 * 默认**不**挂载 `/storage/emulated/0`。
 */
object ProotLauncher {
    const val GUEST_PATH = "/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin:/sbin"

    private val ENV_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun buildArgv(paths: RuntimePaths, spec: GuestSpec): List<String> {
        require(spec.argv.isNotEmpty()) { "GuestSpec.argv 不能为空" }
        require(spec.cwd.startsWith("/")) { "cwd 必须是容器内绝对路径：${spec.cwd}" }
        val argv = ArrayList<String>()
        argv += paths.proot.absolutePath
        argv += listOf("--link2symlink", "-L", "--kill-on-exit", "-0")
        argv += listOf("-r", paths.rootfs.absolutePath)
        argv += listOf(
            "-b", "/dev",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/proc/self/fd:/dev/fd",
        )
        paths.sharedDir?.let { argv += listOf("-b", "${it.absolutePath}:/root/shared") }
        argv += listOf("-w", spec.cwd)
        argv += listOf("/usr/bin/env", "-i")
        argv += guestEnv(spec.env).map { (k, v) -> "$k=$v" }
        argv += spec.argv
        return argv
    }

    /** 容器内环境：固定基础变量 + GuestSpec.env（可覆盖 TERM/LANG，不允许覆盖 HOME/PATH 以外的非法名）。 */
    fun guestEnv(extra: Map<String, String>): LinkedHashMap<String, String> {
        val env = linkedMapOf(
            "HOME" to "/root",
            "PATH" to GUEST_PATH,
            "TERM" to "dumb",
            "LANG" to "C.UTF-8",
            "TMPDIR" to "/tmp",
        )
        for ((k, v) in extra) {
            require(ENV_KEY.matches(k)) { "非法的环境变量名：$k" }
            require('\u0000' !in v) { "环境变量值含空字节：$k" }
            env[k] = v
        }
        return env
    }

    /** 宿主侧给 proot 进程自己的环境（不进入容器）。 */
    fun hostEnv(paths: RuntimePaths): Map<String, String> = linkedMapOf(
        "PROOT_TMP_DIR" to paths.tmp.absolutePath,
        "PROOT_LOADER" to paths.loader.absolutePath,
        "PROOT_LOADER_32" to paths.loader32.absolutePath,
        "LD_LIBRARY_PATH" to "${paths.lib.absolutePath}:${paths.nativeLibDir.absolutePath}",
    )
}
