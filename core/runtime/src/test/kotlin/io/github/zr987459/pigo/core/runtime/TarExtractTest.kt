package io.github.zr987459.pigo.core.runtime

import io.github.zr987459.pigo.core.runtime.install.TarExtract
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TarExtractTest {
    private val tmp: File = Files.createTempDirectory("tarx").toFile()
    private val dest = File(tmp, "root")

    @After fun cleanup() {
        io.github.zr987459.pigo.core.runtime.util.SafeFs.deleteTree(tmp)
    }

    private sealed interface E {
        data class F(val name: String, val data: String, val mode: Int = "644".toInt(8)) : E
        data class D(val name: String) : E
        data class S(val name: String, val target: String) : E
        data class H(val name: String, val target: String) : E
    }

    private fun tar(vararg entries: E): ByteArray {
        val bos = ByteArrayOutputStream()
        TarArchiveOutputStream(bos, "UTF-8").use { out ->
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for (e in entries) when (e) {
                is E.F -> {
                    val bytes = e.data.toByteArray()
                    val te = TarArchiveEntry(e.name)
                    te.size = bytes.size.toLong(); te.mode = e.mode
                    out.putArchiveEntry(te); out.write(bytes); out.closeArchiveEntry()
                }
                is E.D -> { out.putArchiveEntry(TarArchiveEntry(e.name.trimEnd('/') + "/")); out.closeArchiveEntry() }
                is E.S -> {
                    val te = TarArchiveEntry(e.name, TarConstants.LF_SYMLINK); te.linkName = e.target
                    out.putArchiveEntry(te); out.closeArchiveEntry()
                }
                is E.H -> {
                    val te = TarArchiveEntry(e.name, TarConstants.LF_LINK); te.linkName = e.target
                    out.putArchiveEntry(te); out.closeArchiveEntry()
                }
            }
        }
        return bos.toByteArray()
    }

    private fun extract(bytes: ByteArray, x: TarExtract = TarExtract(), strip: Int = 0) =
        x.extractTar(ByteArrayInputStream(bytes), dest, strip)

    @Test fun regularFilesDirsAndPaxLongNames() {
        val long = "a/" + "x".repeat(150) + "/" + "y".repeat(120) + ".txt"
        val r = extract(tar(E.D("./etc"), E.F("./etc/hostname", "pigo\n"), E.F(long, "long")))
        assertEquals("pigo\n", File(dest, "etc/hostname").readText())
        assertEquals("long", File(dest, long).readText())
        assertEquals(2, r.files)
    }

    @Test fun parentTraversalIsRejected() {
        assertThrows(TarExtract.UnsafeEntryException::class.java) { extract(tar(E.F("../evil", "x"))) }
        assertFalse(File(tmp, "evil").exists())
        assertThrows(TarExtract.UnsafeEntryException::class.java) { extract(tar(E.F("ok/../../evil2", "x"))) }
        assertFalse(File(tmp, "evil2").exists())
    }

    @Test fun absoluteEntryNamesStayInsideRoot() {
        extract(tar(E.F("/etc/passwd", "root:x:0:0")))
        assertEquals("root:x:0:0", File(dest, "etc/passwd").readText())
    }

    @Test fun absoluteSymlinkIsResolvedAgainstRootfsRoot() {
        // lib -> /usr/lib（容器内绝对路径）。之后写 lib/x 必须落在 dest/usr/lib/x，而不是宿主的 /usr/lib/x。
        extract(tar(E.D("usr/lib"), E.S("lib", "/usr/lib"), E.F("lib/libfoo.so.1", "elf")))
        assertEquals("/usr/lib", Files.readSymbolicLink(File(dest, "lib").toPath()).toString())
        assertEquals("elf", File(dest, "usr/lib/libfoo.so.1").readText())
    }

    @Test fun relativeSymlinkIsKeptAndFollowedInside() {
        extract(tar(E.D("usr/bin"), E.S("bin", "usr/bin"), E.F("bin/sh", "#!", "755".toInt(8))))
        assertEquals("usr/bin", Files.readSymbolicLink(File(dest, "bin").toPath()).toString())
        assertTrue(File(dest, "usr/bin/sh").isFile)
    }

    @Test fun escapingViaRelativeSymlinkIsRejected() {
        assertThrows(TarExtract.UnsafeEntryException::class.java) {
            extract(tar(E.S("up", "../.."), E.F("up/pwned", "x")))
        }
        assertFalse(File(tmp, "pwned").exists())
        assertFalse(File(tmp.parentFile, "pwned").exists())
    }

    @Test fun escapingViaAbsoluteSymlinkWithDotDotIsRejected() {
        assertThrows(TarExtract.UnsafeEntryException::class.java) {
            extract(tar(E.S("esc", "/../../tmp"), E.F("esc/pwned", "x")))
        }
    }

    @Test fun symlinkItselfMayPointAnywhereButIsNeverFollowedOnWrite() {
        // 链接条目本身可以指向容器内任意路径；随后同名文件条目会替换链接，而不是写穿到目标
        val outside = File(tmp, "outside.txt").apply { writeText("keep") }
        extract(tar(E.S("f", outside.absolutePath), E.F("f", "new")))
        assertEquals("keep", outside.readText())
        assertEquals("new", File(dest, "f").readText())
        assertFalse(Files.isSymbolicLink(File(dest, "f").toPath()))
    }

    @Test fun hardLinkSharesContentInsideRoot() {
        val r = extract(tar(E.F("usr/bin/perl5.38", "perl", "755".toInt(8)), E.H("usr/bin/perl", "usr/bin/perl5.38")))
        assertEquals("perl", File(dest, "usr/bin/perl").readText())
        assertEquals(1, r.hardlinks)
    }

    @Test fun hardLinkTargetOutsideRootIsRejected() {
        assertThrows(TarExtract.UnsafeEntryException::class.java) {
            extract(tar(E.H("passwd", "../../etc/passwd")))
        }
    }

    @Test fun hardLinkToMissingTargetIsRejected() {
        assertThrows(TarExtract.UnsafeEntryException::class.java) { extract(tar(E.H("a", "nope"))) }
    }

    @Test fun executableBitIsPreserved() {
        extract(tar(E.F("bin/tool", "#!/bin/sh", "755".toInt(8)), E.F("etc/conf", "x", "644".toInt(8))))
        val tool = Files.getPosixFilePermissions(File(dest, "bin/tool").toPath())
        val conf = Files.getPosixFilePermissions(File(dest, "etc/conf").toPath())
        assertTrue(PosixFilePermission.OWNER_EXECUTE in tool)
        assertTrue(PosixFilePermission.OTHERS_EXECUTE in tool)
        assertFalse(PosixFilePermission.OWNER_EXECUTE in conf)
    }

    @Test fun totalSizeLimitAborts() {
        val big = "z".repeat(4096)
        assertThrows(TarExtract.LimitExceededException::class.java) {
            extract(tar(E.F("a", big), E.F("b", big)), TarExtract(maxTotalBytes = 6000))
        }
    }

    @Test fun entryCountLimitAborts() {
        assertThrows(TarExtract.LimitExceededException::class.java) {
            extract(tar(E.F("a", "1"), E.F("b", "2"), E.F("c", "3")), TarExtract(maxEntries = 2))
        }
    }

    @Test fun stripComponentsAndGzip() {
        val bytes = tar(E.F("node-v24.22.2-linux-arm64/bin/node", "node", "755".toInt(8)), E.S("node-v24.22.2-linux-arm64/bin/npm", "../lib/npm-cli.js"))
        val gz = File(tmp, "n.tar.gz")
        GzipCompressorOutputStream(gz.outputStream()).use { it.write(bytes) }
        TarExtract().extractTarGz(gz, dest, stripComponents = 1)
        assertArrayEquals("node".toByteArray(), File(dest, "bin/node").readBytes())
        assertEquals("../lib/npm-cli.js", Files.readSymbolicLink(File(dest, "bin/npm").toPath()).toString())
    }
}
