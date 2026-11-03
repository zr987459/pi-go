plugins {
    `kotlin-dsl`
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// buildSrc 的单测（含「植入违规字符串必须被拦下」）随每次构建一起跑，CI 也会执行。
tasks.named("jar") { finalizedBy("test") }
