// r3：两种形态共用的会话层（SessionHostImpl、PiActions.run、PiSettings、SessionCatalog）。
// 骨架由 r3/core 首推建立；src/** 归 r3/session（DESIGN-r3 §8.1）。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.zr987459.pigo.core.session"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            // PiFeatureCoverageTest 读清单原文与覆盖映射（DESIGN-r3 §7.1）。
            it.systemProperty("pigo.piFeatures", rootProject.file("docs/pi-features.md").absolutePath)
            it.systemProperty("pigo.coverageMap", rootProject.file("docs/r3/coverage-map.tsv").absolutePath)
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core:contract"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
