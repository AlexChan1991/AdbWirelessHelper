// app 模块构建脚本。
//
// ⚠️ 若你用 Android Studio 向导生成骨架，请保留向导的 AGP / Kotlin / Compose BOM 版本；
// 只往本文件里加 buildFeatures / packaging / 依赖项即可。
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinCompose)
}

android {
    namespace = "com.adb.adbwirelesshelper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.adb.adbwirelesshelper"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // ★ SCRCPY_SERVER_VERSION 必须与 app/src/main/assets/scrcpy-server.jar
        //   自身的 BuildConfig.VERSION_NAME **完全一致**（scrcpy-server 会在启动时校验
        //   第一个位置参数，不匹配会直接抛异常退出，表现为「启动后无画面」）。
        //   放 jar 之前请打开 Genymobile/scrcpy 官方 Releases 页面核实实际版本号。
        // 3.3.4 相比 3.3.2 的关键收益：含有提交 e11399af（2025-09-18）
        // "Ignore unknown methods in IDisplayWindowListener" —— 3.3.2 的
        // DisplayWindowListener stub 只实现了 Android ≤15 的回调，Android 16/17 新增回调
        // 会让 binder 调用抛 AbstractMethodError 且无兜底（issue #6362「scrcpy crashes with
        // Android 16 QPR2 Beta 2」）。3.3.2 发布于 2025-09-06，恰好早于该修复 12 天。
        // 协议层 3.3.2 → 3.3.4 零变更（control/、device/Streamer.java、
        // device/DesktopConnection.java 均未改动），可 drop-in 替换，客户端无需改协议。
        buildConfigField("String", "SCRCPY_SERVER_VERSION", "\"3.3.4\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 产出 17 字节码（AGP 8.x 的最低要求），但**不要**用 kotlin { jvmToolchain(17) } 钉死工具链：
    // jvmToolchain 会强制 Gradle 去找一个语言版本恰好为 17 的 JDK，若本机只有 JDK 21 且未配置
    // 工具链下载仓库，就会直接失败（"Cannot find a Java installation ... languageVersion=17"）。
    // 不钉死时，Gradle 用它自己运行的 JVM（17 或 21 均可）以 --release 17 编译，兼容性最好。
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // 供 ScrcpyConfig.serverVersion 读取 SCRCPY_SERVER_VERSION
        buildConfig = true
    }

    packaging {
        // ★ 必须开启：让内置 adb 二进制（jniLibs/*/libadb.so）以「未压缩 + 可执行」方式
        //   落地到 nativeLibraryDir，等价于 AndroidManifest 的 extractNativeLibs=true。
        //   否则 libadb.so 会被压缩存放，无法原地 exec。
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
