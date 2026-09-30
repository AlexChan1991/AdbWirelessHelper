// 顶层构建脚本：只声明插件「别名」，不写版本号（版本集中在 gradle/libs.versions.toml）。
// 若 Android Studio 向导生成的根 build.gradle.kts 与你本机环境不同，以向导版本为准。
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinCompose) apply false
}
