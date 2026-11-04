pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        // jitpack 只在二期引入 Termux 终端库时再加（DESIGN.md §1.2）。
    }
}

rootProject.name = "pi-go"

include(":app")

include(":core:contract")
include(":core:ui")
include(":core:protocol")
include(":core:runtime")
include(":core:engine")
include(":core:store")
include(":core:catalog")
include(":core:workspace")
include(":core:session")

include(":feature:onboarding")
include(":feature:chat")
include(":feature:sessions")
include(":feature:settings")
include(":feature:models")
include(":feature:files")
include(":feature:commands")
include(":feature:term")

// 二期模块（DESIGN.md §2.1）：现在只列出，不建目录。
// include(":feature:terminal")
// include(":core:bridge")
// include(":feature:pet")
// include(":feature:web")
