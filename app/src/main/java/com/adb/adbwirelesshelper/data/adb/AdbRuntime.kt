package com.adb.adbwirelesshelper.data.adb

import android.content.Context
import com.adb.adbwirelesshelper.util.Logx
import java.io.File

/**
 * adb 运行时环境：负责定位内置二进制、准备密钥目录、生成环境变量。
 *
 * ★★ 铁律（最容易踩坑的地方，禁止更改）★★
 * 二进制必须**在 applicationInfo.nativeLibraryDir 原地执行**：
 *   /data/app/<pkg>/lib/<abi>/libadb.so
 * 绝不允许 copyTo(filesDir / cacheDir) 后再 chmod —— Android 10（targetSdk ≥ 29）起
 * App 私有可写目录不具备可执行权限（W^X 加固），那样做必然 Permission denied。
 */
class AdbRuntime(private val context: Context) {

    private val appContext: Context = context.applicationContext

    /** 手动导入的外部 adb 路径存这里（需求 R01 的降级入口） */
    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 当前生效的 adb 二进制绝对路径。
     * 优先使用用户在设置页手动导入的外部二进制；否则使用内置 nativeLibraryDir 下的 libadb.so。
     */
    fun binaryPath(): String {
        val external = externalBinaryPath()
        if (!external.isNullOrBlank() && File(external).exists()) {
            return external
        }
        return File(appContext.applicationInfo.nativeLibraryDir, BINARY_NAME).absolutePath
    }

    /** 二进制文件是否存在（不做可执行性判断） */
    fun binaryExists(): Boolean = runCatching { File(binaryPath()).exists() }.getOrDefault(false)

    /**
     * 执行前自检，幂等。
     *
     * @return 成功返回 Result.success(Unit)；
     *         二进制缺失或不可执行时返回 Result.failure(IllegalStateException)，
     *         错误信息为面向用户/开发者的中文指引。
     */
    fun prepare(): Result<Unit> {
        val file = File(binaryPath())
        if (!file.exists()) {
            val detail = "内置 adb 二进制缺失，请阅读项目的 README「二进制资源」章节放入 " +
                "app/src/main/jniLibs/arm64-v8a/libadb.so"
            Logx.e(
                TAG,
                "adb 二进制缺失: ${binaryPath()}（请把 libadb.so 放到 app/src/main/jniLibs/<abi>/）"
            )
            return Result.failure(IllegalStateException(detail))
        }
        if (!file.canExecute()) {
            // nativeLibraryDir 下的文件通常已带可执行位；这里做一次兜底尝试
            val ok = runCatching { file.setExecutable(true, false) }.getOrDefault(false)
            if (!ok) {
                return Result.failure(
                    IllegalStateException(
                        "内置 adb 二进制不可执行（nativeLibraryDir 权限受限），" +
                            "当前系统可能不支持内置 adb，请在设置页手动导入"
                    )
                )
            }
        }
        // 提前创建 HOME，adb 首次运行会在这里生成 adbkey / adbkey.pub
        runCatching { File(homeDir()).mkdirs() }
        return Result.success(Unit)
    }

    /**
     * adb 的 HOME 目录（filesDir/adbhome），用于存放 RSA 密钥对。
     * 每次调用都会确保目录存在。
     */
    fun homeDir(): String {
        val dir = File(appContext.filesDir, HOME_DIR_NAME)
        if (!dir.exists()) {
            runCatching { dir.mkdirs() }
        }
        return dir.absolutePath
    }

    /**
     * 二进制所在目录。
     *
     * 用于 LD_LIBRARY_PATH：当前内置的 adb 是静态链接（只依赖 libm/libdl/libc），
     * 本不需要它；但如果将来换成动态链接、需要携带伴随 .so 的 adb（例如 Termux 版
     * 依赖 libprotobuf / libcrypto），把那些 .so 一并放进 jniLibs 即可被 linker 找到，
     * 否则会 CANNOT LINK EXECUTABLE。
     */
    fun libDir(): String =
        File(binaryPath()).parent ?: appContext.applicationInfo.nativeLibraryDir

    /**
     * adb 进程环境变量。
     *
     * - HOME：adb 把 .android/adbkey 生成到这里
     * - TMPDIR：临时目录
     * - LD_LIBRARY_PATH：二进制所在目录，供携带伴随 .so 的场景使用（见 [libDir]）
     * - ANDROID_ADB_SERVER_PORT=50375：★ 必须避开默认 5037，
     *   可能与系统 adbd / Shizuku / LADB 抢端口（风险 R11）
     * - ADB_MDNS_OPENSCREEN=0 / ADB_MDNS_AUTO_CONNECT=0：
     *   关掉 adb 自带 mdns daemon，发现统一交给 NsdManager，避免抢占 5353
     */
    fun env(): Map<String, String> = mapOf(
        "HOME" to homeDir(),
        "TMPDIR" to appContext.cacheDir.absolutePath,
        "LD_LIBRARY_PATH" to libDir(),
        "ANDROID_ADB_SERVER_PORT" to ADB_SERVER_PORT,
        "ADB_MDNS_OPENSCREEN" to "0",
        "ADB_MDNS_AUTO_CONNECT" to "0"
    )

    /** 手动导入外部 adb 二进制（R01 降级路径） */
    fun setExternalBinary(path: String) {
        runCatching {
            prefs.edit().putString(KEY_EXTERNAL_BINARY, path).apply()
        }
    }

    /** 读取手动导入的外部 adb 路径；未设置返回 null */
    fun externalBinaryPath(): String? {
        return runCatching {
            prefs.getString(KEY_EXTERNAL_BINARY, null)
        }.getOrNull()
    }

    /** 清除手动导入设置，回到内置二进制 */
    fun clearExternalBinary() {
        runCatching {
            prefs.edit().remove(KEY_EXTERNAL_BINARY).apply()
        }
    }

    companion object {
        private const val TAG = "AdbRuntime"
        private const val BINARY_NAME = "libadb.so"
        private const val HOME_DIR_NAME = "adbhome"
        private const val PREFS_NAME = "adb_runtime"
        private const val KEY_EXTERNAL_BINARY = "external_binary_path"
        private const val ADB_SERVER_PORT = "50375"
    }
}
