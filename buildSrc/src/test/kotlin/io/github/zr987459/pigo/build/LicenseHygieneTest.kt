package io.github.zr987459.pigo.build

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
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
