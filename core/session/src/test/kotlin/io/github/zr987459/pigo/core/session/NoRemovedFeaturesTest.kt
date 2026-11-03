package io.github.zr987459.pigo.core.session

import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File

/** DESIGN-r3 §1.4 防回潮：全仓 `src/main` 不再出现已删除功能的标识。r3/app 合入后启用。 */
class NoRemovedFeaturesTest {
    private val banned = listOf("mascot", "鲸", "Deepy", "cny", "汇率", "exchangeRate", "Dsh", "dsh_icons", "ShareTargetActivity", "AutoNamer", "FilesBrowserScreen", "GitScreens")

    @Ignore("r3/app 合入后启用")
    @Test
    fun removedFeaturesStayRemoved() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val hits = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" }
            .filter { it.isFile && it.path.contains("${File.separator}src${File.separator}main${File.separator}") && it.extension in setOf("kt", "xml", "kts", "json") }
            .flatMap { f -> f.readLines().withIndex().filter { (_, l) -> banned.any { b -> l.contains(b, ignoreCase = b == "cny") } }.map { (i, l) -> "${f.relativeTo(root)}:${i + 1}: ${l.trim().take(120)}" } }
            .toList()
        assertTrue("已删除功能又出现了：\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
