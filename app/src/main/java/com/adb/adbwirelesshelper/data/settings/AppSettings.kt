package com.adb.adbwirelesshelper.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.adb.adbwirelesshelper.BuildConfig
import com.adb.adbwirelesshelper.domain.model.ScrcpyConfig
import com.adb.adbwirelesshelper.util.Logx
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/** DataStore 实例（文件级私有，避免多处创建导致多实例异常） */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "adbwifi_settings"
)

/**
 * 偏好设置（DataStore Preferences，需求 A7）。
 *
 * 约定：
 * - 所有读取都带默认值，首次启动无需初始化；
 * - DataStore 读异常（IO）统一降级为默认值，不崩溃；
 * - 所有写入为 suspend，需在协程中调用。
 */
class AppSettings(private val context: Context) {

    private val dataStore: DataStore<Preferences>
        get() = context.applicationContext.settingsDataStore

    /** 轮询间隔（毫秒），默认 1500 */
    val pollIntervalMs: Flow<Long> = read(Keys.POLL_INTERVAL) { DEFAULT_POLL_INTERVAL_MS }

    /** scrcpy 投屏参数 */
    val scrcpyConfig: Flow<ScrcpyConfig> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs ->
            ScrcpyConfig(
                // ⚠️ serverVersion **绝不能**从 DataStore 读：它是 assets 里那个 jar 自身的
                // 版本，跟用户偏好无关。旧版本曾把它持久化，若这里沿用旧值，升级 jar 后
                // 启动命令仍会传旧版本号 —— scrcpy-server 启动时会校验第一个位置参数，
                // 不匹配直接抛异常退出，表现为「部署成功但永远没有画面」。
                serverVersion = BuildConfig.SCRCPY_SERVER_VERSION,
                maxSize = prefs[Keys.SC_MAX_SIZE] ?: DEFAULT_MAX_SIZE,
                videoBitRate = prefs[Keys.SC_BIT_RATE] ?: DEFAULT_VIDEO_BIT_RATE,
                maxFps = prefs[Keys.SC_MAX_FPS] ?: DEFAULT_MAX_FPS,
                videoCodec = prefs[Keys.SC_CODEC] ?: DEFAULT_CODEC,
                audioEnabled = prefs[Keys.SC_AUDIO] ?: false,
                // 音频四参数的默认值必须与 ScrcpyConfig 的声明一致（raw / 128k / output / 50ms）。
                // 尤其 audioCodec 的默认值是 raw 而非 scrcpy 自己的 opus —— 理由见 ScrcpyConfig.audioCodec
                // 的 KDoc（Opus 解码要 API 29，本工程 minSdk 26）。
                audioCodec = prefs[Keys.SC_AUDIO_CODEC] ?: ScrcpyConfig.AUDIO_CODEC_RAW,
                audioBitRate = prefs[Keys.SC_AUDIO_BIT_RATE] ?: DEFAULT_AUDIO_BIT_RATE,
                audioSource = prefs[Keys.SC_AUDIO_SOURCE] ?: ScrcpyConfig.AUDIO_SOURCE_OUTPUT,
                audioBufferMs = prefs[Keys.SC_AUDIO_BUFFER_MS] ?: DEFAULT_AUDIO_BUFFER_MS,
                controlEnabled = prefs[Keys.SC_CONTROL] ?: true,
                tunnelForward = prefs[Keys.SC_TUNNEL_FORWARD] ?: true,
                localPort = prefs[Keys.SC_LOCAL_PORT] ?: DEFAULT_LOCAL_PORT,
                stayAwake = prefs[Keys.SC_STAY_AWAKE] ?: true,
                bitrateMbps = prefs[Keys.SC_BITRATE_MBPS] ?: DEFAULT_BITRATE_MBPS,
                maxSizePreset = prefs[Keys.SC_MAX_SIZE_PRESET] ?: DEFAULT_MAX_SIZE_PRESET,
                fpsCap = prefs[Keys.SC_FPS_CAP] ?: DEFAULT_MAX_FPS
            )
        }

    /** 自动重连开关 */
    val autoReconnect: Flow<Boolean> = read(Keys.AUTO_RECONNECT) { true }

    /** 危险命令二次确认开关（默认开启） */
    val dangerConfirm: Flow<Boolean> = read(Keys.DANGER_CONFIRM) { true }

    /** 主题模式：0 跟随系统 / 1 浅色 / 2 深色 */
    val themeMode: Flow<Int> = read(Keys.THEME_MODE) { THEME_FOLLOW_SYSTEM }

    suspend fun setPollInterval(ms: Long) {
        write { prefs -> prefs[Keys.POLL_INTERVAL] = ms.coerceIn(500L, 30_000L) }
    }

    suspend fun setScrcpyConfig(cfg: ScrcpyConfig) {
        write { prefs ->
            // 不再持久化 SC_SERVER_VERSION：版本号必须与内置 jar 严格一致，
            // 持久化只会让升级 jar 后残留旧版本（真机已实测 DataStore 里存着 "3.3.2"）。
            prefs[Keys.SC_MAX_SIZE] = cfg.maxSize
            prefs[Keys.SC_BIT_RATE] = cfg.videoBitRate
            prefs[Keys.SC_MAX_FPS] = cfg.maxFps
            prefs[Keys.SC_CODEC] = cfg.videoCodec
            prefs[Keys.SC_AUDIO] = cfg.audioEnabled
            prefs[Keys.SC_AUDIO_CODEC] = cfg.audioCodec
            prefs[Keys.SC_AUDIO_BIT_RATE] = cfg.audioBitRate
            prefs[Keys.SC_AUDIO_SOURCE] = cfg.audioSource
            prefs[Keys.SC_AUDIO_BUFFER_MS] = cfg.audioBufferMs
            prefs[Keys.SC_CONTROL] = cfg.controlEnabled
            prefs[Keys.SC_TUNNEL_FORWARD] = cfg.tunnelForward
            prefs[Keys.SC_LOCAL_PORT] = cfg.localPort
            prefs[Keys.SC_STAY_AWAKE] = cfg.stayAwake
            prefs[Keys.SC_BITRATE_MBPS] = cfg.bitrateMbps
            prefs[Keys.SC_MAX_SIZE_PRESET] = cfg.maxSizePreset
            prefs[Keys.SC_FPS_CAP] = cfg.fpsCap
        }
    }

    suspend fun setAutoReconnect(v: Boolean) {
        write { prefs -> prefs[Keys.AUTO_RECONNECT] = v }
    }

    suspend fun setDangerConfirm(v: Boolean) {
        write { prefs -> prefs[Keys.DANGER_CONFIRM] = v }
    }

    suspend fun setThemeMode(v: Int) {
        write { prefs -> prefs[Keys.THEME_MODE] = v.coerceIn(0, 2) }
    }

    /** 一次性读取当前值（非 Flow 场景用） */
    suspend fun currentPollInterval(): Long = pollIntervalMs.first()
    suspend fun currentScrcpyConfig(): ScrcpyConfig = scrcpyConfig.first()
    suspend fun currentThemeMode(): Int = themeMode.first()
    suspend fun currentDangerConfirm(): Boolean = dangerConfirm.first()

    /** 通用读取：带 IO 异常降级 */
    private fun <T> read(key: Preferences.Key<T>, default: () -> T): Flow<T> =
        dataStore.data
            .catch { e ->
                if (e is IOException) {
                    Logx.w(TAG, "DataStore 读取失败，使用默认值: ${e.message}")
                    emit(emptyPreferences())
                } else {
                    throw e
                }
            }
            .map { prefs -> prefs[key] ?: default() }

    /**
     * 通用写入。DataStore 的 edit transform 非挂起，故 block 直接接收
     * [MutablePreferences] 做内存赋值即可（无任何 IO，不会阻塞）。
     */
    private suspend fun write(block: (MutablePreferences) -> Unit) {
        runCatching {
            dataStore.edit { prefs -> block(prefs) }
        }.onFailure {
            Logx.e(TAG, "DataStore 写入失败: ${it.message}")
        }
    }

    private object Keys {
        val POLL_INTERVAL = longPreferencesKey("poll_interval_ms")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val DANGER_CONFIRM = booleanPreferencesKey("danger_confirm")
        val THEME_MODE = intPreferencesKey("theme_mode")

        val SC_SERVER_VERSION = stringPreferencesKey("scrcpy_server_version")
        val SC_MAX_SIZE = intPreferencesKey("scrcpy_max_size")
        val SC_BIT_RATE = intPreferencesKey("scrcpy_bit_rate")
        val SC_MAX_FPS = intPreferencesKey("scrcpy_max_fps")
        val SC_CODEC = stringPreferencesKey("scrcpy_codec")
        val SC_AUDIO = booleanPreferencesKey("scrcpy_audio")
        val SC_AUDIO_CODEC = stringPreferencesKey("scrcpy_audio_codec")
        val SC_AUDIO_BIT_RATE = intPreferencesKey("scrcpy_audio_bit_rate")
        val SC_AUDIO_SOURCE = stringPreferencesKey("scrcpy_audio_source")
        val SC_AUDIO_BUFFER_MS = intPreferencesKey("scrcpy_audio_buffer_ms")
        val SC_CONTROL = booleanPreferencesKey("scrcpy_control")
        val SC_TUNNEL_FORWARD = booleanPreferencesKey("scrcpy_tunnel_forward")
        val SC_LOCAL_PORT = intPreferencesKey("scrcpy_local_port")
        val SC_STAY_AWAKE = booleanPreferencesKey("scrcpy_stay_awake")
        val SC_BITRATE_MBPS = intPreferencesKey("scrcpy_bitrate_mbps")
        val SC_MAX_SIZE_PRESET = intPreferencesKey("scrcpy_max_size_preset")
        val SC_FPS_CAP = intPreferencesKey("scrcpy_fps_cap")
    }

    companion object {
        private const val TAG = "AppSettings"

        const val DEFAULT_POLL_INTERVAL_MS = 1_500L
        const val DEFAULT_MAX_SIZE = 1280
        const val DEFAULT_VIDEO_BIT_RATE = 8_000_000
        const val DEFAULT_MAX_FPS = 30
        const val DEFAULT_CODEC = "h264"
        const val DEFAULT_LOCAL_PORT = 27_183
        const val DEFAULT_BITRATE_MBPS = 8
        const val DEFAULT_MAX_SIZE_PRESET = 720

        /** 音频码率（bps），与 scrcpy `Options.audioBitRate` 默认值一致。对 `raw` 不生效。 */
        const val DEFAULT_AUDIO_BIT_RATE = 128_000

        /** 控制端 AudioTrack 目标缓冲（ms），与 `ScrcpyConfig.audioBufferMs` 默认值一致。 */
        const val DEFAULT_AUDIO_BUFFER_MS = 50

        const val THEME_FOLLOW_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }
}
