package io.github.zr987459.pigo.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "每次构建都要重新扫描整个仓库")
abstract class LicenseHygieneTask : DefaultTask() {

    @get:Internal
    abstract val projectRoot: DirectoryProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val violations = LicenseHygiene.scan(projectRoot.get().asFile)
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("checkLicenseHygiene 失败：发现 ${violations.size} 处许可证违规（见 docs/DESIGN.md §4.1）")
                    violations.forEach { appendLine("  - $it") }
                },
            )
        }
        logger.lifecycle("checkLicenseHygiene：通过")
    }
}
