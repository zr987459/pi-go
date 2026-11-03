package io.github.zr987459.pigo.core.runtime

import io.github.zr987459.pigo.contract.GuestSpec
import io.github.zr987459.pigo.core.runtime.install.GuestArch
import io.github.zr987459.pigo.core.runtime.install.Mirrors
import io.github.zr987459.pigo.core.runtime.install.Pins
import io.github.zr987459.pigo.core.runtime.install.SemVer
import io.github.zr987459.pigo.core.runtime.util.Hashes
import io.github.zr987459.pigo.core.runtime.util.SafeFs
import java.io.File
import java.net.URI
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MiscRuntimeTest {
    private val tmp: File = Files.createTempDirectory("misc").toFile()
    private val paths = RuntimePaths(File(tmp, "files"), File(tmp, "native"), File(tmp, "ext"))

    @After fun cleanup() = SafeFs.deleteTree(tmp)

    @Test fun prootArgvFollowsDesign() {
        val argv = ProotLauncher.buildArgv(paths, GuestSpec(listOf("pi", "--mode", "rpc"), env = mapOf("PIGO_KEY_X" to "k")))
        val i = argv.indexOf("/usr/bin/env")
        assertEquals(File(tmp, "native/libproot.so").absolutePath, argv[0])
        assertEquals(listOf("--link2symlink", "-L", "--kill-on-exit", "-0", "-r", paths.rootfs.absolutePath), argv.subList(1, 7))
        assertTrue(argv.containsAll(listOf("/dev", "/dev/urandom:/dev/random", "/proc", "/sys", "/proc/self/fd:/dev/fd")))
        assertTrue(argv.contains("${File(tmp, "ext").absolutePath}:/root/shared"))
        assertEquals("/root/pi-cwd", argv[argv.indexOf("-w") + 1])
        assertEquals("-i", argv[i + 1])
        assertTrue(argv.subList(i, argv.size).containsAll(listOf("HOME=/root", "TERM=dumb", "LANG=C.UTF-8", "PIGO_KEY_X=k")))
        assertEquals(listOf("pi", "--mode", "rpc"), argv.takeLast(3))
        // 默认不挂载共享存储
        assertFalse(argv.any { it.contains("/storage/emulated") || it.contains("/sdcard") })
    }

    @Test fun prootHostEnvOnlyForProot() {
        val env = ProotLauncher.hostEnv(paths)
        assertEquals(paths.tmp.absolutePath, env["PROOT_TMP_DIR"])
        assertEquals("${paths.lib.absolutePath}:${paths.nativeLibDir.absolutePath}", env["LD_LIBRARY_PATH"])
        assertTrue(env.getValue("PROOT_LOADER").endsWith("libprootloader.so"))
        assertTrue(env.getValue("PROOT_LOADER_32").endsWith("libprootloader32.so"))
    }

    @Test fun badEnvNamesRejected() {
        assertThrows(IllegalArgumentException::class.java) { ProotLauncher.guestEnv(mapOf("A=B" to "x")) }
        assertThrows(IllegalArgumentException::class.java) { ProotLauncher.guestEnv(mapOf("A" to "x\u0000y")) }
    }

    @Test fun hostFileMapsAndRejectsTraversal() {
        assertEquals(File(paths.rootfs, "root/x"), paths.guest("/root/x"))
        assertThrows(IllegalArgumentException::class.java) { paths.guest("/root/../../etc") }
        assertThrows(IllegalArgumentException::class.java) { paths.guest("relative") }
    }

    @Test fun realPinsAndMirrorsParse() {
        val pins = Pins.parse(File("../../app/src/main/assets/pins.json").readText())
        assertEquals("1.5.0", pins.piVersion)
        assertEquals("24.22.2", pins.nodeVersion)
        assertEquals("24.04.5", pins.ubuntuVersion)
        assertEquals("ubuntu-base-24.04.5-base-arm64.tar.gz", pins.ubuntuFile(GuestArch.ARM64))
        assertEquals("node-v24.22.2-linux-x64.tar.gz", pins.nodeFile(GuestArch.X86_64))
        assertEquals(64, pins.nodeHash(GuestArch.ARM64).length)

        val m = Mirrors.parse(File("src/main/assets/mirrors.json").readText())
        assertTrue(m.ubuntuBase.size >= 4)
        assertTrue(m.node.size >= 5)
        assertEquals("https://registry.npmmirror.com/-/binary/node/v24.22.2/node-v24.22.2-linux-arm64.tar.gz", m.node[0].url(pins.nodeFile(GuestArch.ARM64), "24.22.2"))
        // F2：默认（国内）下载只用国内镜像域名
        val cn = setOf("mirrors.aliyun.com", "mirrors.tuna.tsinghua.edu.cn", "mirrors.ustc.edu.cn", "mirrors.huaweicloud.com",
            "registry.npmmirror.com", "cdn.npmmirror.com")
        val hosts = (m.ubuntuBase + m.node).map { URI(it.base.replace("{version}", "1")).host } + URI(m.npmRegistry).host + m.apt.values.map { URI(it).host }
        assertTrue("非国内域名：${hosts - cn}", hosts.all { it in cn })
    }

    @Test fun abiMapping() {
        assertEquals(GuestArch.ARM64, GuestArch.fromAbis(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(GuestArch.X86_64, GuestArch.fromAbis(listOf("x86_64", "x86")))
        assertNull(GuestArch.fromAbis(listOf("armeabi-v7a")))
    }

    @Test fun semverRange() {
        assertTrue(SemVer.satisfies("1.5.0", ">=1.5.0 <2.0.0"))
        assertTrue(SemVer.satisfies("1.9.12", ">=1.5.0 <2.0.0"))
        assertFalse(SemVer.satisfies("2.0.0", ">=1.5.0 <2.0.0"))
        assertFalse(SemVer.satisfies("1.4.9", ">=1.5.0 <2.0.0"))
        assertFalse(SemVer.satisfies("1.6.0-beta.1", ">=1.5.0 <2.0.0"))
    }

    @Test fun sumsLookup() {
        val sums = "e77b6f10c2590cef872b33ee9f635a0e3fd1f57fb074c0e52b5c7f56147a0c86 *ubuntu-base-24.04.5-base-amd64.tar.gz\n" +
            "95c264150aceb7f3437a29fd6984924f5f8d1511ea3cbd6c508b83f6c555dae2  node-v24.22.2-linux-arm64.tar.gz\n"
        assertEquals("e77b6f10c2590cef872b33ee9f635a0e3fd1f57fb074c0e52b5c7f56147a0c86", Hashes.lookupSum(sums, "ubuntu-base-24.04.5-base-amd64.tar.gz"))
        assertEquals("95c264150aceb7f3437a29fd6984924f5f8d1511ea3cbd6c508b83f6c555dae2", Hashes.lookupSum(sums, "node-v24.22.2-linux-arm64.tar.gz"))
        assertNull(Hashes.lookupSum(sums, "other.tar.gz"))
    }

    @Test fun runtimeFilesCopiesSonames() {
        paths.nativeLibDir.mkdirs()
        File(paths.nativeLibDir, "libtalloc.so").writeText("talloc")
        val r = RuntimeFiles.ensure(paths)
        assertEquals("talloc", File(paths.lib, "libtalloc.so.2").readText())
        assertEquals(listOf("libandroidshmem.so"), r.missing)
        assertEquals(listOf("libtalloc.so.2"), r.copied)
        // 第二次不再复制
        assertTrue(RuntimeFiles.ensure(paths).copied.isEmpty())
    }

    @Test fun prootUnavailableWhenAnyNeededLibraryIsMissing() {
        paths.nativeLibDir.mkdirs()
        listOf("libproot.so", "libprootloader.so", "libtalloc.so").forEach {
            File(paths.nativeLibDir, it).apply { writeText("elf"); setExecutable(true) }
        }
        assertEquals(listOf("libandroidshmem.so"), RuntimeFiles.missingLibs(paths))
        assertFalse("缺 libandroidshmem.so 时 proot 不可用", RuntimeFiles.prootAvailable(paths))
        File(paths.nativeLibDir, "libandroidshmem.so").writeText("shm")
        assertTrue(RuntimeFiles.missingLibs(paths).isEmpty())
        assertTrue(RuntimeFiles.prootAvailable(paths))
    }

    @Test fun copiesPresentIsStatOnlyAndDetectsStaleCopies() {
        paths.nativeLibDir.mkdirs()
        File(paths.nativeLibDir, "libtalloc.so").writeText("talloc")
        File(paths.nativeLibDir, "libandroidshmem.so").writeText("shm")
        assertFalse(RuntimeFiles.copiesPresent(paths))
        RuntimeFiles.ensure(paths)
        assertTrue(RuntimeFiles.copiesPresent(paths))
        // APK 更新后库变了（长度不同）：需要重新复制
        File(paths.nativeLibDir, "libtalloc.so").writeText("talloc-2.6")
        assertFalse(RuntimeFiles.copiesPresent(paths))
    }

    @Test fun fixupsAreIdempotentAndKeepUserNpmrcLines() {
        paths.guest("/etc").mkdirs()
        paths.guest("/etc/group").writeText("root:x:0:\naid_inet:x:3003:\n")
        paths.guest("/root").mkdirs()
        paths.guest("/root/.npmrc").writeText("registry=https://registry.npmjs.org/\nfund=false\n")
        val fx = GuestFixups(paths)
        repeat(2) { fx.applyAll(listOf("8.8.8.8", "bogus"), listOf("223.5.5.5", "119.29.29.29", "1.1.1.1"), "https://registry.npmmirror.com/", listOf(3003, 9997)) }
        val group = paths.guest("/etc/group").readLines()
        assertEquals(1, group.count { it.contains(":3003:") })
        assertEquals(1, group.count { it.startsWith("aid_9997:") })
        assertEquals(listOf("registry=https://registry.npmmirror.com/", "fund=false"), paths.guest("/root/.npmrc").readLines())
        assertEquals(listOf("nameserver 8.8.8.8", "nameserver 223.5.5.5", "nameserver 119.29.29.29"),
            paths.guest("/etc/resolv.conf").readLines().filter { it.startsWith("nameserver") })
        assertEquals(1, paths.guest("/etc/hosts").readLines().count { it.startsWith("127.0.0.1") })
    }

    @Test fun safeDeleteDoesNotFollowSymlinks() {
        val outside = File(tmp, "outside").apply { mkdirs(); File(this, "keep.txt").writeText("x") }
        val root = File(tmp, "rootfs").apply { mkdirs() }
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "abs").toPath(), File("/usr").toPath())
        SafeFs.deleteTree(root)
        assertFalse(root.exists())
        assertTrue(File(outside, "keep.txt").isFile)
    }
}
