package io.github.zr987459.pigo.build

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseHygieneTest {

    private val root: File = Files.createTempDirectory("hygiene").toFile()

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun write(path: String, text: String): File =
        File(root, path).apply { parentFile.mkdirs(); writeText(text) }

    // 植入用字符串在运行时拼出，仓库里不留原文。
    private val plantedPackage = "me.bmax" + ".apatch"

    @Test
    fun cleanTreePasses() {
        write("app/src/main/java/a/A.kt", "package io.github.zr987459.pigo\nclass A\n")
        write("settings.gradle.kts", "rootProject.name = \"pi-go\"\n")
        assertTrue(LicenseHygiene.scan(root).isEmpty())
    }

    @Test
    fun plantedPackageInSourceFails() {
        write("core/runtime/src/main/java/x/Tar.kt", "package x\n// from $plantedPackage.dsh\nclass Tar\n")
        val v = LicenseHygiene.scan(root)
        assertEquals(1, v.size)
        assertEquals(2, v.single().line)
    }

    @Test
    fun plantedProjectNameFails() {
        write("core/runtime/src/main/kotlin/x/Y.kt", "// see " + "DSH" + "-Folk" + "\n")
        write("core/runtime/src/main/kotlin/x/Z.kt", "// ref " + "DS" + "HA" + " resolver\n")
        assertEquals(2, LicenseHygiene.scan(root).size)
    }

    @Test
    fun docsAndBuildOutputsAreIgnored() {
        write("docs/DESIGN.md", "mentions $plantedPackage on purpose\n")
        write("app/build/generated/X.kt", "$plantedPackage\n")
        assertTrue(LicenseHygiene.scan(root).isEmpty())
    }

    @Test
    fun packageDirectoriesNamedBuildOrOutAreScanned() {
        write("core/runtime/build.gradle.kts", "plugins {}\n")
        write("core/runtime/src/main/kotlin/x/build/Y.kt", "package x.build\nimport $plantedPackage.dsh.Foo\n")
        write("core/runtime/src/main/kotlin/x/out/Z.kt", "package x.out\n// $plantedPackage\n")
        write("buildSrc/src/main/kotlin/io/github/zr987459/pigo/build/W.kt", "// $plantedPackage\n")
        val v = LicenseHygiene.scan(root)
        assertEquals(v.joinToString(), 3, v.size)
    }

    @Test
    fun soInsideSrcBuildDirIsChecked() {
        write("core/runtime/build.gradle.kts", "plugins {}\n")
        val so = write("core/runtime/src/main/jniLibs/build/libx.so", "pretend binary")
        val v = LicenseHygiene.scan(root, badSo = setOf(LicenseHygiene.sha256(so)))
        assertEquals(1, v.size)
    }

    @Test
    fun onlyModuleRootOutputsAreSkipped() {
        // 用 .so（不受 isSource 过滤，任何位置都按哈希检查）才能真正测到 isSkipped 分支。
        write("core/runtime/build.gradle.kts", "plugins {}\n")
        val moduleOut = write("core/runtime/build/intermediates/liba.so", "pretend binary a") // 模块根输出：跳过
        val rootOut = write("build/tmp/libb.so", "pretend binary b")                          // 仓库根输出：跳过
        val notModule = write("core/build/libc.so", "pretend binary c")                       // core 不是模块根：扫描
        val inSrc = write("core/runtime/src/main/res/raw/build/libd.so", "pretend binary d")  // src 下的 build 目录：扫描
        val bad = listOf(moduleOut, rootOut, notModule, inSrc).map { LicenseHygiene.sha256(it) }.toSet()
        assertEquals(4, bad.size)

        val v = LicenseHygiene.scan(root, badSo = bad)
        assertEquals(v.joinToString(), listOf("libc.so", "libd.so"), v.map { it.file.name }.sorted())

        assertTrue(LicenseHygiene.isSkipped(root, File(root, "core/runtime/build")))
        assertTrue(LicenseHygiene.isSkipped(root, File(root, "build")))
        assertFalse(LicenseHygiene.isSkipped(root, File(root, "core/build")))
        assertFalse(LicenseHygiene.isSkipped(root, File(root, "core/runtime/src/main/res/raw/build")))
    }

    @Test
    fun hygieneSourcesThemselvesContainNoPlainKeywords() {
        // 单测工作目录为 buildSrc/；把扫描器自身源码放进临时模块的 src/ 下扫描，必须干净。
        val dir = File("src/main/kotlin/io/github/zr987459/pigo/build")
        assertTrue("找不到 ${dir.absolutePath}", dir.isDirectory)
        dir.listFiles()!!.filter { it.isFile }.forEach { it.copyTo(File(root, "probe/src/main/kotlin/" + it.name)) }
        val v = LicenseHygiene.scan(root)
        assertTrue(v.joinToString(), v.isEmpty())
    }

    @Test
    fun soWithKnownHashFails() {
        val so = write("core/runtime/src/main/jniLibs/arm64-v8a/libproot.so", "pretend binary")
        val sha = LicenseHygiene.sha256(so)
        assertTrue(LicenseHygiene.scan(root).isEmpty()) // 不在名单里：放行
        val v = LicenseHygiene.scan(root, badSo = setOf(sha))
        assertEquals(1, v.size)
        assertTrue(v.single().reason.contains(sha))
    }

    @Test
    fun badSoListIsComplete() {
        assertEquals(15, LicenseHygiene.knownBadSoSha256.size)
        assertTrue(LicenseHygiene.knownBadSoSha256.all { it.matches(Regex("[0-9a-f]{64}")) })
    }
}
