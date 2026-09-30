// Gradle 设置脚本。
//
// ⚠️ 版本说明（重要）：本文件与 gradle/libs.versions.toml 中的 AGP / Kotlin / Compose BOM
// 版本号仅为「建议值」。若你用 Android Studio 的 New Project → Empty Activity(Compose)
// 向导生成了骨架，请保留向导给出的默认版本号，不要手工改写本文件里的数字。
// 所有 Could not resolve 类错误，请先联网核实坐标再修改。
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AdbWirelessHelper"
include(":app")
