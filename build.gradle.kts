import io.github.zr987459.pigo.build.LicenseHygieneTask

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// 许可证防回归（DESIGN.md §4.1 第 8 条）：任何模块构建前先扫描源码与 jniLibs。
val checkLicenseHygiene = tasks.register<LicenseHygieneTask>("checkLicenseHygiene") {
    group = "verification"
    description = "源码中出现 GPL 派生项目的包名/名称，或 jniLibs 中出现 PiMet 那组 .so 时构建失败。"
    projectRoot.set(layout.projectDirectory)
}

subprojects {
    // Android 模块挂到 preBuild；纯 JVM 模块挂到编译任务。
    tasks.matching { it.name == "preBuild" || it.name == "compileKotlin" }.configureEach {
        dependsOn(checkLicenseHygiene)
    }
    // CI 的单测步骤是 `./gradlew testDebugUnitTest`，纯 JVM 模块（core/contract、core/protocol）没有这个任务，
    // 它们的单测原来不跑（PR #15 遗留 Nit）。给纯 JVM 模块补一个同名任务，依赖它们的 `test`。
    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.register("testDebugUnitTest") {
            group = "verification"
            description = "纯 JVM 模块的单测（与 Android 模块的 testDebugUnitTest 同名，CI 一条命令全跑）。"
            dependsOn("test")
        }
    }
}
