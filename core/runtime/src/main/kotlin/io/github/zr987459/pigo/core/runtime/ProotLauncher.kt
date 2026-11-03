package io.github.zr987459.pigo.core.runtime

import io.github.zr987459.pigo.contract.GuestSpec

/**
 * PRoot 启动参数（DESIGN.md §4.4）。纯函数，便于单测。
 *
 * **环境变量的值一律不进命令行参数。** argv 会留在 `/proc/<pid>/cmdline` 里，容器绑定了 `/proc`，
 * pi 的 bash 工具用 `ps` 或读各进程的 cmdline 就能读到（Key 等密钥尤其不能出现在这里）。
 * 做法：容器内需要的全部变量（[guestEnv]）连同 proot 自己需要的变量一起放进 proot 宿主进程的环境
 * （[processEnv]，proot 会把环境原样交给容器内程序），容器内入口用 `/usr/bin/env -u <名字>…`
 * 去掉只给 proot 用的宿主变量（[hostOnlyNames]），argv 里只有变量**名**、没有值。
 * 效果与原来的 `env -i K=V…` 相同：宿主的 LD_LIBRARY_PATH 等不会漏进容器。
 * 默认**不**挂载 `/storage/emulated/0`。
 */
object ProotLauncher {
    const val GUEST_PATH = "/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin:/sbin"

    private val ENV_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** 从 App 进程环境里保留给 proot 的几项（Android 动态链接器等需要），进容器前去掉。 */
    val HOST_PASSTHROUGH: Set<String> = linkedSetOf(
        "ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT", "BOOTCLASSPATH",
    )

    /** 只给 proot 宿主进程、进容器前要去掉的变量名。 */
    fun hostOnlyNames(paths: RuntimePaths): Set<String> = LinkedHashSet<String>().apply {
        addAll(hostEnv(paths).keys)
        addAll(HOST_PASSTHROUGH)
    }

    fun buildArgv(paths: RuntimePaths, spec: GuestSpec): List<String> {
        require(spec.argv.isNotEmpty()) { "GuestSpec.argv 不能为空" }
        require(spec.cwd.startsWith("/")) { "cwd 必须是容器内绝对路径：${spec.cwd}" }
        guestEnv(spec.env, hostOnlyNames(paths)) // 只校验变量名与值；值不进 argv
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
        argv += "/usr/bin/env"
        for (name in hostOnlyNames(paths)) argv += listOf("-u", name)
        argv += spec.argv
        return argv
    }

    /**
     * proot 宿主进程的完整环境：proot 自己的变量 + 容器内变量（含 Key）。
     * 调用方须先清空进程环境，只保留 [HOST_PASSTHROUGH]，再放入这里的全部变量（见 GuestRunner.defaultStart）。
     */
    fun processEnv(paths: RuntimePaths, spec: GuestSpec): Map<String, String> {
        val host = hostEnv(paths)
        val guest = guestEnv(spec.env, hostOnlyNames(paths))
        return LinkedHashMap<String, String>(host).apply { putAll(guest) }
    }

    /** 容器内环境：固定基础变量 + GuestSpec.env（可覆盖 TERM/LANG；不允许非法名，也不允许与 proot 宿主变量同名）。 */
    fun guestEnv(extra: Map<String, String>, reserved: Set<String> = emptySet()): LinkedHashMap<String, String> {
        val env = linkedMapOf(
            "HOME" to "/root",
            "PATH" to GUEST_PATH,
            "TERM" to "dumb",
            "LANG" to "C.UTF-8",
            "TMPDIR" to "/tmp",
        )
        for ((k, v) in extra) {
            require(ENV_KEY.matches(k)) { "非法的环境变量名：$k" }
            require(k !in reserved) { "环境变量名与 proot 宿主变量冲突：$k" }
            require('\u0000' !in v) { "环境变量值含空字节：$k" }
            env[k] = v
        }
        return env
    }

    /** 宿主侧给 proot 进程自己的环境（进容器前由 `env -u` 去掉）。 */
    fun hostEnv(paths: RuntimePaths): Map<String, String> = linkedMapOf(
        "PROOT_TMP_DIR" to paths.tmp.absolutePath,
        "PROOT_LOADER" to paths.loader.absolutePath,
        "PROOT_LOADER_32" to paths.loader32.absolutePath,
        "LD_LIBRARY_PATH" to "${paths.lib.absolutePath}:${paths.nativeLibDir.absolutePath}",
    )
}
