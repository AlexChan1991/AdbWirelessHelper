package com.adb.adbwirelesshelper.domain.model

import com.adb.adbwirelesshelper.BuildConfig

/**
 * 领域模型：全局唯一契约。发现层（B）与 scrcpy 层（C）均依赖本文件的类型，
 * 修改任一签名都必须同步通知另外两位工程师。
 */

/** 服务来源：mDNS 发现 / 端口扫描兜底 / 手动输入 */
enum class Source { MDNS, PORT_SCAN, MANUAL }

/** 服务能力：仅可配对（pairing 服务）/ 可直接连接（connect 服务）/ 未知 */
enum class Capability { PAIRING_ONLY, CONNECT, UNKNOWN }

/** 设备连接状态，来自 `adb devices -l` 的第二列 */
enum class DeviceState { OFFLINE, UNAUTHORIZED, DEVICE, UNKNOWN }

/**
 * 一次发现结果。
 *
 * 注意：配对端口（pairPort）与连接端口（connectPort）是**两个不同的端口**，
 * 千万不要混用 —— 混用是无线调试最常见的失败原因。
 */
data class DiscoveredService(
    val instanceName: String,
    val serviceType: String,
    val host: String,
    val port: Int,
    val source: Source,
    val capability: Capability,
    val pairPort: Int? = null,
    val connectPort: Int? = null
)

/** `adb devices -l` 的单条结果 */
data class AdbDevice(
    val serial: String,
    val state: DeviceState,
    val model: String? = null,
    val product: String? = null,
    val deviceCode: String? = null
)

/**
 * 一条「曾经连接过的设备」历史记录（功能：已知设备区展示历史 + 点击直达 + 删除/清空）。
 *
 * ★ 主键是 [host] 而不是 [serial]：无线调试的**端口每次开关都会变**，
 *   同一台手机重启后 serial 从 `192.168.1.5:37123` 变成 `192.168.1.5:41207`。
 *   若以 serial 为主键，同一台手机会攒出十几条重复记录；以 host 为主键则自动去重并
 *   用最新端口覆盖（[port] / [serial] 都记最后一次连上的值）。
 *   USB 设备的 serial 不含 `:`，此时 host 就是整个 serial，[port] 为 0。
 *
 * @property host            IP（或 USB serial），同一台设备的唯一标识
 * @property port            最后一次连上的端口；0 表示 USB / 未知端口
 * @property serial          最后一次连上时 adb 报告的完整 serial
 * @property label           设备型号等可读名（来自 `adb devices -l`），可能为 null
 * @property lastConnectedAt 最后一次连上的时间戳（epoch ms）
 */
data class HistoryDevice(
    val host: String,
    val port: Int,
    val serial: String,
    val label: String? = null,
    val lastConnectedAt: Long = System.currentTimeMillis()
) {
    /** 是否无线设备（端口有效）。USB 设备为 false，点它只能靠 adb 已有连接直接跳转。 */
    val isWireless: Boolean get() = port > 0

    /** 展示用的一行副标题：`192.168.1.5:37123`。 */
    val hostPort: String get() = if (isWireless) "$host:$port" else host
}

/** 被控端状态面板全量数据 */
data class DeviceInfo(
    val serial: String,
    val androidVersion: String? = null, val sdkInt: Int? = null,
    val romBuildId: String? = null, val securityPatch: String? = null,
    val fingerprint: String? = null,
    val brand: String? = null, val manufacturer: String? = null, val model: String? = null,
    val deviceCode: String? = null,
    val cpuName: String? = null, val abi: String? = null, val abiList: List<String> = emptyList(),
    val cpuCores: Int? = null, val cpuUsagePercent: Float? = null,
    val memTotalKb: Long? = null, val memAvailableKb: Long? = null, val memUsedPercent: Float? = null,
    val topProcesses: List<ProcMem> = emptyList(),
    val storageTotalKb: Long? = null, val storageUsedPercent: Float? = null,
    val batteryLevel: Int? = null, val batteryStatus: Int? = null,
    val screenW: Int? = null, val screenH: Int? = null, val densityDpi: Int? = null,
    val screenOn: Boolean? = null,
    val ipAddress: String? = null, val kernelVersion: String? = null,
    val uptimeSeconds: Long? = null, val loadAvg: List<Float> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** 进程内存占用（PSS，单位 kB） */
data class ProcMem(val name: String, val pssKb: Long)

/**
 * scrcpy 投屏参数。
 *
 * ★ serverVersion 必须与 assets 内 scrcpy-server.jar 自身的 BuildConfig.VERSION_NAME
 *   完全一致，否则 server 启动即退出、表现为「黑屏无画面」。
 */
data class ScrcpyConfig(
    val serverVersion: String = BuildConfig.SCRCPY_SERVER_VERSION,
    val maxSize: Int = 1280,
    val videoBitRate: Int = 8_000_000,
    val maxFps: Int = 30,
    val videoCodec: String = "h264",
    val audioEnabled: Boolean = false,
    /**
     * 音频编码。取值必须落在 [AUDIO_CODEC_RAW] / [AUDIO_CODEC_AAC] / [AUDIO_CODEC_OPUS] 内。
     *
     * ★ 默认是 `raw`（未压缩 PCM），**不是** scrcpy 自身的默认值 `opus`。理由：
     * Opus 的**解码**要控制端 Android 10（API 29）以上，而本工程 minSdk = 26，
     * 做默认会让 Android 8/9 控制端开了音频却完全没声音。`raw` 是唯一「零解码 + 全版本可用」
     * 的路径，且延迟最低。
     */
    val audioCodec: String = AUDIO_CODEC_RAW,
    /** 音频码率（bps）。服务端默认 128000；**对 `raw` 不生效**（PCM 不压缩）。 */
    val audioBitRate: Int = 128_000,
    /**
     * 音频来源。取值见 [AUDIO_SOURCE_OUTPUT] / [AUDIO_SOURCE_PLAYBACK] / [AUDIO_SOURCE_MIC]。
     * 注意 `playback` 需要被控端 Android 13+，`output`（默认）需要 Android 11+。
     */
    val audioSource: String = AUDIO_SOURCE_OUTPUT,
    /**
     * 音频目标缓冲（毫秒）。
     *
     * ⚠️ **仅作用于控制端**：它决定本机 AudioTrack 的缓冲大小，**不会**发给 scrcpy-server。
     * 服务端没有 `audio_buffer` 这个参数（v3.3.2 `Options.parse` 的 switch 里没有该 case），
     * 传过去只会走 default 分支打一行 `Unknown server option: audio_buffer` 警告 —— 不致命，
     * 但纯属噪音，且会让人误以为服务端受其影响，所以一律不发。
     * 缓冲越大越不容易断音，但延迟越高；50ms 与 scrcpy 桌面端默认值一致。
     */
    val audioBufferMs: Int = 50,
    val controlEnabled: Boolean = true,
    val tunnelForward: Boolean = true,
    val localPort: Int = 27_183,
    val stayAwake: Boolean = true,
    val bitrateMbps: Int = 8, val maxSizePreset: Int = 720, val fpsCap: Int = 30
) {
    companion object {
        /** 未压缩 PCM 16-bit LE：控制端零解码，延迟最低（默认值）。 */
        const val AUDIO_CODEC_RAW: String = "raw"

        /** AAC-LC：控制端自 API 16 起即可解码，带宽远低于 raw。 */
        const val AUDIO_CODEC_AAC: String = "aac"

        /** Opus：带宽最低，但控制端解码需 API 29+。 */
        const val AUDIO_CODEC_OPUS: String = "opus"

        /** 设备整体输出（映射 REMOTE_SUBMIX），会同时静音被控端本身。需被控端 Android 11+。 */
        const val AUDIO_SOURCE_OUTPUT: String = "output"

        /** 仅采集媒体播放（应用可 opt-out）。需被控端 Android 13+，可与 `audio_dup` 同用。 */
        const val AUDIO_SOURCE_PLAYBACK: String = "playback"

        /** 采集被控端麦克风。 */
        const val AUDIO_SOURCE_MIC: String = "mic"

        // ------------------------------------------------------------------
        // 音频流头里的 codec id（audio socket 上开头 4 字节的大端 u32）
        //
        // 取值 = scrcpy `AudioCodec` 枚举里那个 4 字节 ASCII 常量，客户端在
        // `Streamer.writeAudioHeader()` 写出的流头上读到的就是它。控制端与媒体层
        // 都以这里为唯一事实来源，避免在协议层和解码层各写一份十六进制常量。
        // ------------------------------------------------------------------

        /** Opus：`"opus"` 的 4 字节 ASCII，0x6f707573。 */
        const val AUDIO_CODEC_ID_OPUS: Int = 0x6f707573

        /** AAC：`"\0aac"` 的 4 字节 ASCII，0x00616163（最高字节为 0）。 */
        const val AUDIO_CODEC_ID_AAC: Int = 0x00616163

        /** FLAC：`"flac"` 的 4 字节 ASCII，0x666c6163。 */
        const val AUDIO_CODEC_ID_FLAC: Int = 0x666c6163

        /** RAW（未压缩 PCM16LE）：`"\0raw"` 的 4 字节 ASCII，0x00726177（最高字节为 0）。 */
        const val AUDIO_CODEC_ID_RAW: Int = 0x00726177

        /**
         * 可识别的音频流头 codec id 集合，**不含** 0 / 1 两个禁用码
         * （`Streamer.writeDisableStream` 写出的 4 字节全 0，或末字节为 1）。
         *
         * ⚠️ 判断禁用码必须比**整个 4 字节**。AAC/RAW 的 id 最高字节本来就是 0x00，
         * 只看首字节会把它们误判成「已禁用」，音频永远起不来。
         */
        val AUDIO_CODEC_IDS: Set<Int> = setOf(
            AUDIO_CODEC_ID_OPUS,
            AUDIO_CODEC_ID_AAC,
            AUDIO_CODEC_ID_FLAC,
            AUDIO_CODEC_ID_RAW
        )
    }
}

/** 一次 shell 执行的最终结果 */
data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
    /** 是否成功（exitCode == 0） */
    val isOk: Boolean get() = exitCode == 0
}

/** 流式 shell 输出分片：逐行 Line，结束 Finished */
sealed interface ShellChunk {
    data class Line(val text: String) : ShellChunk
    data class Finished(val result: ShellResult) : ShellChunk
}

// ---------------------------------------------------------------- serial 切分 / 设备别名

/**
 * adb serial → 主机段（同一台设备的稳定标识）。**全仓唯一的 serial 切分实现**。
 *
 * ★ 必须取**最后一个**冒号，不是第一个。IPv6 的 serial 形如 `[::1]:5555` /
 *   `[2001:db8::1]:5555`：取第一个冒号会把 `[::1]:5555` 切成 **`[`** —— 于是
 *   **所有 IPv6 设备的键塌缩成同一个 `[`**，A 设备设的别名会显示在 B 设备上；
 *   取最后一个冒号则天然给出 `[::1]` / `[2001:db8::1]`，**不需要对方括号做特殊处理**。
 *
 * ★ 谁都不要再自己写一份 `substringBefore(':')`。曾经出现过两套规则并存：
 *   历史记录主键用 `lastIndexOf(':')`、已知设备去重与别名键用 `substringBefore(':')`。
 *   两者在 IPv4 上结果相同、在 IPv6 上不同，直接后果是——同一台 IPv6 设备在
 *   「已知设备」和「历史设备」两组各出现一次，且两个入口算出的别名键对不上，
 *   别名功能整条失效。现在历史库、别名库、UI 去重、按 host 反查**共用这一个函数**，
 *   所以它们不可能再不一致。
 *
 * USB serial 不含 `:`，原样返回；`:5555` 这类没有主机段的畸形 serial 也原样返回
 * （与历史库的历史行为保持一致，不做额外聪明处理）。
 */
fun serialToHost(serial: String): String {
    val idx: Int = serial.lastIndexOf(':')
    return if (idx > 0) serial.substring(0, idx) else serial
}

/**
 * 设备别名表的**主键**。
 *
 * ★ 不能拿完整 serial 当键：无线调试的端口**每次开关都会变**
 *   （`192.168.1.5:37123` → `192.168.1.5:41207`），用完整 serial 的话，
 *   同一台手机换一次端口就丢一次别名 —— 而这功能存在的意义恰恰是「下次自动套用」。
 *
 * 实现上直接委托 [serialToHost]，因此它与历史记录的主键 [HistoryDevice.host]
 * 是**同一个函数**算出来的：在「已知设备」里设的别名，在「历史设备」里一定查得到，
 * 反之亦然。这不是「两边碰巧用了同样的规则」，是「两边调用的是同一份代码」。
 */
fun deviceAliasKey(serial: String): String = serialToHost(serial)
