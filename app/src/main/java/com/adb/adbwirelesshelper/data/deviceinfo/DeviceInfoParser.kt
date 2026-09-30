package com.adb.adbwirelesshelper.data.deviceinfo

import com.adb.adbwirelesshelper.domain.model.ProcMem

/**
 * 设备信息解析：**纯函数集合，零 Android 依赖**，可在 JVM 上直接单测。
 *
 * 所有输入都是 `adb shell` 命令的原始输出文本，输出为强类型的 Kotlin 数据。
 * 解析策略统一为「宽松匹配 + 兜底」，任何一段解析失败都返回 null / 空列表，绝不抛异常。
 */
object DeviceInfoParser {

    /** Top 进程最多保留的条数。 */
    private const val TOP_PROC_LIMIT: Int = 6

    /** `[key]: [value]`（getprop 无参输出格式）。 */
    private val PROP_BRACKET_REGEX: Regex =
        Regex("""^\s*\[([^\]]+)\]:\s*\[(.*)\]\s*$""")

    /** `key: value`（getprop 单行格式 / 通用键值）。 */
    private val PROP_PLAIN_REGEX: Regex =
        Regex("""^\s*([A-Za-z0-9_.\-]+):\s*(.*)$""")

    /** 通用 `k: v`。 */
    private val KEY_VALUE_REGEX: Regex =
        Regex("""^\s*([^:]+):\s*(.*)$""")

    /** `MemTotal:        8000000 kB`。 */
    private val MEM_LINE_REGEX: Regex =
        Regex("""^([A-Za-z]+):\s+(\d+)(?:\s*kB)?""", setOf(RegexOption.MULTILINE))

    /** `Physical size: 1080x2400`。 */
    private val WM_SIZE_REGEX: Regex =
        Regex("""(\d+)\s*[xX]\s*(\d+)""")

    /** `Physical density: 420`。 */
    private val WM_DENSITY_REGEX: Regex =
        Regex("""density\s*[:=]\s*(\d+)""", setOf(RegexOption.IGNORE_CASE))

    /** `inet 192.168.1.9/24 brd ...`。 */
    private val IP_REGEX: Regex =
        Regex("""inet\s+(\d{1,3}(?:\.\d{1,3}){3})(?:/\d+)?""")

    /** `level: 78` / `status: 2` / `scale: 100`。 */
    private val BATTERY_INT_REGEX: Regex =
        Regex("""^\s*([A-Za-z_]+):\s*(-?\d+)""", setOf(RegexOption.MULTILINE))

    /** `mWakefulness=Awake`。 */
    private val WAKEFULNESS_REGEX: Regex =
        Regex("""mWakefulness\s*[=:]\s*([A-Za-z]+)""")

    /** `mScreenOn=true`。 */
    private val SCREEN_ON_REGEX: Regex =
        Regex("""mScreenOn\s*[=:]\s*(true|false)""", setOf(RegexOption.IGNORE_CASE))

    /** `Screen off state: OFF`。 */
    private val SCREEN_STATE_REGEX: Regex =
        Regex("""Screen\s+(?:on|off)\s+state\s*[:=]\s*(ON|OFF)""", setOf(RegexOption.IGNORE_CASE))

    /** 从任意字段中抽取纯数字（用于 RSS / df 列）。 */
    private val DIGITS_REGEX: Regex = Regex("""(\d+)""")

    // ------------------------------------------------------------ 属性与键值

    /**
     * 解析 `getprop` 输出，格式形如 `[ro.product.model]: [Pixel 8 Pro]`。
     * 同时兼容 `key: value` 形式。
     */
    fun parseProps(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (raw.isEmpty()) return result
        for (line in raw.lineSequence()) {
            if (line.isBlank()) continue
            val bracket = PROP_BRACKET_REGEX.find(line)
            if (bracket != null) {
                result[bracket.groupValues[1].trim()] = bracket.groupValues[2]
                continue
            }
            val plain = PROP_PLAIN_REGEX.find(line)
            if (plain != null) {
                result[plain.groupValues[1].trim()] = plain.groupValues[2].trim()
            }
        }
        return result
    }

    /** 通用 `k: v` 解析，返回保持原始顺序的键值对列表。 */
    fun parseKeyValue(raw: String): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        if (raw.isEmpty()) return result
        for (line in raw.lineSequence()) {
            if (line.isBlank()) continue
            val match = KEY_VALUE_REGEX.find(line) ?: continue
            result.add(match.groupValues[1].trim() to match.groupValues[2].trim())
        }
        return result
    }

    /** 从属性表中取第一个非空的候选值。 */
    fun firstNonBlank(propMap: Map<String, String>, vararg keys: String): String? {
        for (key in keys) {
            val value = propMap[key]?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    // ------------------------------------------------------------ 内存

    /**
     * 解析 `/proc/meminfo`。
     *
     * @return Pair(MemTotal(kB), 可用内存(kB))。若不存在 `MemAvailable`，
     *         回退为 `MemFree + Cached + Buffers`（旧内核没有该字段）。
     */
    fun parseMemInfo(raw: String): Pair<Long?, Long?> {
        val total = findMemKb(raw, "MemTotal") ?: return (null to null)
        val available = findMemKb(raw, "MemAvailable")
        if (available != null) return (total to available)
        val free = findMemKb(raw, "MemFree") ?: 0L
        val cached = findMemKb(raw, "Cached") ?: 0L
        val buffers = findMemKb(raw, "Buffers") ?: 0L
        return (total to (free + cached + buffers))
    }

    /** 已用内存百分比（0–100），数据不全时返回 null。 */
    fun memUsedPercent(totalKb: Long?, availableKb: Long?): Float? {
        if (totalKb == null || availableKb == null || totalKb <= 0L) return null
        val used = (totalKb - availableKb).coerceIn(0L, totalKb)
        return (used.toFloat() / totalKb.toFloat() * 100f).coerceIn(0f, 100f)
    }

    private fun findMemKb(raw: String, key: String): Long? {
        for (match in MEM_LINE_REGEX.findAll(raw)) {
            if (match.groupValues[1] == key) {
                return match.groupValues[2].toLongOrNull()
            }
        }
        return null
    }

    // ------------------------------------------------------------ CPU

    /** `/proc/stat` 的采样快照。 */
    data class CpuStatSnapshot(val total: Long, val idle: Long)

    /**
     * 解析 `/proc/stat`，**只取首行 `cpu `**（不是 `cpu0` 等单核行）。
     *
     * idle 取 `idle + iowait`，与 `top` 的口径一致。
     */
    fun parseCpuStat(raw: String): CpuStatSnapshot? {
        val line = raw.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("cpu ") }
            ?: return null
        val numbers = line.removePrefix("cpu").trim()
            .split(Regex("""\s+"""))
            .mapNotNull { it.trim().toLongOrNull() }
        if (numbers.size < 4) return null
        val total = numbers.sum()
        val idle = numbers[3] + numbers.getOrElse(4) { 0L }
        return CpuStatSnapshot(total = total, idle = idle)
    }

    /**
     * 用两次 `/proc/stat` 采样差分计算 CPU 占用率（0–100）。
     * `usage = (Δtotal - Δidle) / Δtotal × 100`
     */
    fun calcCpuUsage(a: CpuStatSnapshot?, b: CpuStatSnapshot?): Float? {
        if (a == null || b == null) return null
        val deltaTotal = b.total - a.total
        val deltaIdle = b.idle - a.idle
        if (deltaTotal <= 0L) return null
        val busy = (deltaTotal - deltaIdle).coerceIn(0L, deltaTotal)
        return (busy.toFloat() / deltaTotal.toFloat() * 100f).coerceIn(0f, 100f)
    }

    /**
     * CPU 型号四级兜底：`ro.chipname` → `ro.hardware` → `ro.board.platform`。
     */
    fun cpuNameFallback(propMap: Map<String, String>): String? =
        firstNonBlank(propMap, "ro.chipname", "ro.hardware", "ro.board.platform")

    // ------------------------------------------------------------ 存储

    /**
     * 解析 `df /data`（或 `df -k /data`）输出。
     *
     * @return Pair(总容量(kB), 已用(kB))，按 1K-blocks 计。
     */
    fun parseDf(raw: String): Pair<Long?, Long?> {
        for (line in raw.lineSequence()) {
            val text = line.trim()
            if (text.isEmpty()) continue
            if (text.startsWith("Filesystem", ignoreCase = true)) continue
            val tokens = text.split(Regex("""\s+"""))
            if (tokens.size < 3) continue
            // 跳过第一列（文件系统名，可能含数字），只取纯数字列：Size / Used / Avail
            val numbers = tokens.drop(1)
                .mapNotNull { token -> token.toLongOrNull() }
            if (numbers.size >= 2) return (numbers[0] to numbers[1])
        }
        return (null to null)
    }

    // ------------------------------------------------------------ 电量

    /**
     * 解析 `dumpsys battery`。
     *
     * @return Triple(level, status, scale)，任一缺失为 null。
     */
    fun parseBattery(raw: String): Triple<Int?, Int?, Int?> {
        val level = findBatteryInt(raw, "level")
        val status = findBatteryInt(raw, "status")
        val scale = findBatteryInt(raw, "scale")
        return Triple(level, status, scale)
    }

    /** 电量百分比（0–100），自动按 scale 归一化。 */
    fun batteryPercent(level: Int?, scale: Int?): Int? {
        if (level == null) return null
        val max = scale ?: 100
        if (max <= 0) return level
        return ((level * 100) / max).coerceIn(0, 100)
    }

    private fun findBatteryInt(raw: String, key: String): Int? {
        for (match in BATTERY_INT_REGEX.findAll(raw)) {
            if (match.groupValues[1] == key) {
                return match.groupValues[2].toIntOrNull()
            }
        }
        return null
    }

    // ------------------------------------------------------------ 屏幕与电源

    /** 解析 `wm size`，如 `Physical size: 1080x2400`。 */
    fun parseWmSize(raw: String): Pair<Int?, Int?> {
        val physicalLine = raw.lineSequence().firstOrNull { line ->
            line.contains("size", ignoreCase = true) && !line.contains("override", ignoreCase = true)
        }
        val target = physicalLine ?: raw
        val match = WM_SIZE_REGEX.find(target) ?: return (null to null)
        return (match.groupValues[1].toIntOrNull() to match.groupValues[2].toIntOrNull())
    }

    /** 解析 `wm density`，如 `Physical density: 420`。 */
    fun parseWmDensity(raw: String): Int? =
        WM_DENSITY_REGEX.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()

    /**
     * 解析 `dumpsys power | grep -E 'mWakefulness|Screen'`。
     *
     * @return true=亮屏，false=息屏，无法确定时返回 null。
     */
    fun parsePower(raw: String): Boolean? {
        val wakefulness = WAKEFULNESS_REGEX.find(raw)?.groupValues?.getOrNull(1)
        if (wakefulness != null) return wakefulness.equals("Awake", ignoreCase = true)

        val screenOn = SCREEN_ON_REGEX.find(raw)?.groupValues?.getOrNull(1)
        if (screenOn != null) return screenOn.equals("true", ignoreCase = true)

        val state = SCREEN_STATE_REGEX.find(raw)?.groupValues?.getOrNull(1)
        if (state != null) return state.equals("ON", ignoreCase = true)

        return null
    }

    // ------------------------------------------------------------ 网络 / 内核 / 负载

    /** 解析 `ip -f inet addr show wlan0`，取非回环的 IPv4 地址。 */
    fun parseIp(raw: String): String? {
        for (match in IP_REGEX.findAll(raw)) {
            val address = match.groupValues[1]
            if (!address.startsWith("127.")) return address
        }
        return IP_REGEX.find(raw)?.groupValues?.getOrNull(1)
    }

    /** 解析 `cat /proc/loadavg`，取前 3 个浮点。 */
    fun parseLoadAvg(raw: String): List<Float> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        return text.split(Regex("""\s+""")).take(3).mapNotNull { it.toFloatOrNull() }
    }

    /** 解析 `cat /proc/version`，取前 3 段，如 `Linux version 6.1.68-android14`。 */
    fun parseKernel(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val parts = text.split(Regex("""\s+""")).take(3)
        val joined = parts.joinToString(" ").trim()
        return joined.takeIf { it.isNotEmpty() }
    }

    /** 解析 `cat /proc/uptime`，返回开机时长（秒）。 */
    fun parseUptimeSeconds(raw: String): Long? {
        val first = raw.trim().split(Regex("""\s+""")).firstOrNull() ?: return null
        return first.toFloatOrNull()?.toLong()
    }

    // ------------------------------------------------------------ Top 进程

    /**
     * 解析 `ps -A -o NAME,RSS -e | sort -k2 -n -r | head -6` 的输出。
     *
     * RSS 单位为 kB（部分 ROM 会带 `K` 后缀，此处只取数字）。
     */
    fun parseTopProcs(raw: String): List<ProcMem> {
        val result = ArrayList<ProcMem>()
        for (line in raw.lineSequence()) {
            val text = line.trim()
            if (text.isEmpty()) continue
            val tokens = text.split(Regex("""\s+"""))
            if (tokens.size < 2) continue
            val name = tokens[0].trim()
            if (name.isEmpty()) continue
            if (name.equals("NAME", ignoreCase = true)) continue
            if (name.equals("RSS", ignoreCase = true)) continue
            val kb = tokens.drop(1)
                .mapNotNull { token -> DIGITS_REGEX.find(token)?.groupValues?.getOrNull(1)?.toLongOrNull() }
                .firstOrNull() ?: continue
            if (kb <= 0L) continue
            result.add(ProcMem(name = name, pssKb = kb))
            if (result.size >= TOP_PROC_LIMIT) break
        }
        return result
    }
}
