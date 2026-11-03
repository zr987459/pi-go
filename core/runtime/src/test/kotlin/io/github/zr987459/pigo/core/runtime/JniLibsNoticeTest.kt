package io.github.zr987459.pigo.core.runtime

import io.github.zr987459.pigo.core.runtime.util.Hashes
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** jniLibs 中每个文件的 SHA256 必须与 NOTICE-runtime.md「已入库的文件」表一致，且一一对应。 */
class JniLibsNoticeTest {
    // 单测工作目录是模块目录 core/runtime
    private val module = File("").absoluteFile
    private val jni = File(module, "src/main/jniLibs")
    private val notice = File(module, "NOTICE-runtime.md")

    private val row = Regex("^\\| `((?:arm64-v8a|x86_64)/lib[^`]+\\.so)` \\|[^|]*\\|[^|]*\\| `([0-9a-f]{64})` \\|.*")

    private fun recorded(): Map<String, String> = notice.readLines()
        .mapNotNull { row.matchEntire(it.trim()) }
        .associate { it.groupValues[1] to it.groupValues[2] }

    @Test fun everyJniLibMatchesNotice() {
        assertTrue("找不到 ${notice.path}", notice.isFile)
        val rec = recorded()
        assertTrue("NOTICE 表为空", rec.isNotEmpty())
        val actual = jni.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(jni).invariantSeparatorsPath to Hashes.sha256(it)
        }
        assertEquals("jniLibs 文件集合与 NOTICE 不一致", rec.keys.sorted(), actual.keys.sorted())
        for ((path, sha) in rec) assertEquals("$path 的 SHA256", sha, actual[path])
    }

    @Test fun bothAbisCarryProotLoaderTallocAndShmem() {
        val names = listOf("libproot.so", "libprootloader.so", "libprootloader32.so", "libtalloc.so", "libandroidshmem.so")
        for (abi in listOf("arm64-v8a", "x86_64")) for (n in names) {
            assertTrue("缺少 $abi/$n", File(jni, "$abi/$n").isFile)
        }
    }

    @Test fun onlySoFilesInJniLibs() {
        val bad = jni.walkTopDown().filter { it.isFile && !it.name.matches(Regex("lib[a-z0-9_]+\\.so")) }.toList()
        assertTrue("jniLibs 中只能有 lib*.so：$bad", bad.isEmpty())
    }
}
