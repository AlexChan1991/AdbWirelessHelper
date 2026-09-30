package com.adb.adbwirelesshelper.data.deviceinfo

import com.adb.adbwirelesshelper.data.adb.ShellExecutor
import com.adb.adbwirelesshelper.domain.model.DeviceInfo
import com.adb.adbwirelesshelper.domain.model.ProcMem
import com.adb.adbwirelesshelper.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 设备信息采集器。
 *
 * 策略：**一次 shell 批量拉回全部静态信息**（`getprop` 无参会输出所有属性），
 * 再在本地用 [DeviceInfoParser] 纯函数解析；只有 CPU 占用率需要两次 `/proc/stat` 采样差分。
 *
 * 构造为**单参**（依赖 [ShellExecutor]），AppContainer 依赖此签名。
 */
class DeviceInfoCollector(private val shell: ShellExecutor) {

    companion object {
        private const val TAG = "DeviceInfoCollector"

        // ---- 分段标记：必须与 BATCH_COMMAND 中的 echo 完全一致 ----
        const val MARKER_MEM: String = "---MEM---"
        const val MARKER_STAT: String = "---STAT---"
        const val MARKER_UPTIME: String = "---UPTIME---"
        const val MARKER_LOAD: String = "---LOAD---"
        const val MARKER_DF: String = "---DF---"
        const val MARKER_WM: String = "---WM---"
        const val MARKER_BAT: String = "---BAT---"
        const val MARKER_POWER: String = "---POWER---"
        const val MARKER_IP: String = "---IP---"
        const val MARKER_VER: String = "---VER---"

        /** 一次 shell 拉回全部静态信息。 */
        const val BATCH_COMMAND: String =
            "getprop; echo $MARKER_MEM; cat /proc/meminfo; echo $MARKER_STAT; cat /proc/stat; " +
                "echo $MARKER_UPTIME; cat /proc/uptime; echo $MARKER_LOAD; cat /proc/loadavg; " +
                "echo $MARKER_DF; df /data; echo $MARKER_WM; wm size; wm density; " +
                "echo $MARKER_BAT; dumpsys battery; " +
                "echo $MARKER_POWER; dumpsys power | grep -E 'mWakefulness|Screen'; " +
                "echo $MARKER_IP; ip -f inet addr show wlan0; echo $MARKER_VER; cat /proc/version"

        /** Top 进程单独一条命令（失败返回空列表，不抛异常）。 */
        const val TOP_COMMAND: String = "ps -A -o NAME,RSS -e | sort -k2 -n -r | head -6"

        /** CPU 核心数。 */
        const val CPU_CORE_COMMAND: String = "nproc --all"

        /** 单次 /proc/stat 采样。 */
        const val CPU_STAT_COMMAND: String = "cat /proc/stat"

        /** 批量命令的超时时间。 */
        const val BATCH_TIMEOUT_MS: Long = 8_000L

        /** 单条辅助命令的超时时间。 */
        const val SINGLE_TIMEOUT_MS: Long = 5_000L

        /** CPU 两次采样的默认间隔。 */
        const val CPU_SAMPLE_INTERVAL_MS: Long = 500L
    }

    /**
     * 采集一台设备的完整状态。
     *
     * @param serial    设备序列号（无线场景形如 `192.168.1.42:39621`）。
     * @param withHeavy true 时额外采集 Top 进程（较重，建议 3 秒一次）；
     *                  false 时跳过 Top 进程（轻量轮询，1.5 秒一次）。
     */
    suspend fun collect(serial: String, withHeavy: Boolean = true): DeviceInfo =
        withContext(Dispatchers.IO) {
            val raw: String = runQuietly(serial, BATCH_COMMAND, BATCH_TIMEOUT_MS)

            val propMap = DeviceInfoParser.parseProps(sectionOf(raw, null, MARKER_MEM))
            val memSection = sectionOf(raw, MARKER_MEM, MARKER_STAT)
            val uptimeSection = sectionOf(raw, MARKER_UPTIME, MARKER_LOAD)
            val loadSection = sectionOf(raw, MARKER_LOAD, MARKER_DF)
            val dfSection = sectionOf(raw, MARKER_DF, MARKER_WM)
            val wmSection = sectionOf(raw, MARKER_WM, MARKER_BAT)
            val batterySection = sectionOf(raw, MARKER_BAT, MARKER_POWER)
            val powerSection = sectionOf(raw, MARKER_POWER, MARKER_IP)
            val ipSection = sectionOf(raw, MARKER_IP, MARKER_VER)
            val versionSection = sectionOf(raw, MARKER_VER, null)

            val (memTotalKb, memAvailableKb) = DeviceInfoParser.parseMemInfo(memSection)
            val (storageTotalKb, storageUsedKb) = DeviceInfoParser.parseDf(dfSection)
            val (screenW, screenH) = DeviceInfoParser.parseWmSize(wmSection)
            val (batteryLevel, batteryStatus, batteryScale) = DeviceInfoParser.parseBattery(batterySection)

            val abiList: List<String> = propMap["ro.product.cpu.abilist"]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()

            val topProcs: List<ProcMem> = if (withHeavy) {
                DeviceInfoParser.parseTopProcs(runQuietly(serial, TOP_COMMAND, SINGLE_TIMEOUT_MS))
            } else {
                emptyList()
            }

            val cpuCores: Int? = runQuietly(serial, CPU_CORE_COMMAND, SINGLE_TIMEOUT_MS)
                .trim()
                .lineSequence()
                .firstOrNull()
                ?.trim()
                ?.toIntOrNull()

            val cpuUsage: Float? = sampleCpuUsage(serial, CPU_SAMPLE_INTERVAL_MS)

            val resolvedSerial: String = DeviceInfoParser.firstNonBlank(
                propMap, "ro.serialno", "ro.boot.serialno"
            ) ?: serial

            val densityDpi: Int? = DeviceInfoParser.parseWmDensity(wmSection)
                ?: propMap["ro.sf.lcd_density"]?.trim()?.toIntOrNull()

            DeviceInfo(
                serial = resolvedSerial,
                androidVersion = propMap["ro.build.version.release"]?.trim(),
                sdkInt = propMap["ro.build.version.sdk"]?.trim()?.toIntOrNull(),
                romBuildId = DeviceInfoParser.firstNonBlank(
                    propMap, "ro.build.display.id", "ro.build.version.incremental"
                ),
                securityPatch = propMap["ro.build.version.security_patch"]?.trim(),
                fingerprint = propMap["ro.build.fingerprint"]?.trim(),
                brand = propMap["ro.product.brand"]?.trim(),
                manufacturer = propMap["ro.product.manufacturer"]?.trim(),
                model = DeviceInfoParser.firstNonBlank(
                    propMap, "ro.product.model", "ro.product.marketname", "ro.product.name"
                ),
                deviceCode = DeviceInfoParser.firstNonBlank(
                    propMap, "ro.product.device", "ro.product.name", "ro.product.board"
                ),
                cpuName = DeviceInfoParser.cpuNameFallback(propMap),
                abi = DeviceInfoParser.firstNonBlank(propMap, "ro.product.cpu.abi")
                    ?: abiList.firstOrNull(),
                abiList = abiList,
                cpuCores = cpuCores,
                cpuUsagePercent = cpuUsage,
                memTotalKb = memTotalKb,
                memAvailableKb = memAvailableKb,
                memUsedPercent = DeviceInfoParser.memUsedPercent(memTotalKb, memAvailableKb),
                topProcesses = topProcs,
                storageTotalKb = storageTotalKb,
                storageUsedPercent = storageUsedPercent(storageTotalKb, storageUsedKb),
                batteryLevel = DeviceInfoParser.batteryPercent(batteryLevel, batteryScale),
                batteryStatus = batteryStatus,
                screenW = screenW,
                screenH = screenH,
                densityDpi = densityDpi,
                screenOn = DeviceInfoParser.parsePower(powerSection),
                ipAddress = DeviceInfoParser.parseIp(ipSection),
                kernelVersion = DeviceInfoParser.parseKernel(versionSection),
                uptimeSeconds = DeviceInfoParser.parseUptimeSeconds(uptimeSection),
                loadAvg = DeviceInfoParser.parseLoadAvg(loadSection),
                updatedAt = System.currentTimeMillis(),
            )
        }

    /**
     * 采样一次 CPU 占用率：读两次 `/proc/stat`，间隔 [intervalMs]，差分计算。
     */
    suspend fun sampleCpuUsage(serial: String, intervalMs: Long = CPU_SAMPLE_INTERVAL_MS): Float? =
        withContext(Dispatchers.IO) {
            val first = DeviceInfoParser.parseCpuStat(
                runQuietly(serial, CPU_STAT_COMMAND, SINGLE_TIMEOUT_MS)
            )
            delay(intervalMs.coerceIn(100L, 5_000L))
            val second = DeviceInfoParser.parseCpuStat(
                runQuietly(serial, CPU_STAT_COMMAND, SINGLE_TIMEOUT_MS)
            )
            DeviceInfoParser.calcCpuUsage(first, second)
        }

    // ---------------------------------------------------------------- 内部实现

    /**
     * 执行一条命令并只取 stdout；任何异常都吞掉并返回空串，保证采集流程不中断。
     */
    private suspend fun runQuietly(serial: String, command: String, timeoutMs: Long): String =
        try {
            val result = shell.runOnce(serial, command, timeoutMs)
            if (result.exitCode != 0 && result.stdout.isBlank()) {
                Logx.w(TAG, "命令返回非零：exit=${result.exitCode}, cmd=$command, err=${result.stderr.take(200)}")
            }
            result.stdout
        } catch (t: Throwable) {
            Logx.w(TAG, "命令执行失败：cmd=$command, err=${t.message}")
            ""
        }

    /**
     * 按标记截取批量输出的一段。
     *
     * @param startMarker 起始标记（null 表示从头开始）。
     * @param endMarker   结束标记（null 表示到末尾）。
     */
    private fun sectionOf(raw: String, startMarker: String?, endMarker: String?): String {
        if (raw.isEmpty()) return ""
        val startIndex: Int = if (startMarker.isNullOrEmpty()) {
            0
        } else {
            val at = raw.indexOf(startMarker)
            if (at < 0) 0 else at + startMarker.length
        }
        val endIndex: Int = if (endMarker.isNullOrEmpty()) {
            raw.length
        } else {
            val at = raw.indexOf(endMarker, startIndex)
            if (at < 0) raw.length else at
        }
        return if (endIndex <= startIndex) "" else raw.substring(startIndex, endIndex)
    }

    /** 存储已用百分比。 */
    private fun storageUsedPercent(totalKb: Long?, usedKb: Long?): Float? {
        if (totalKb == null || usedKb == null || totalKb <= 0L) return null
        val used = usedKb.coerceIn(0L, totalKb)
        return (used.toFloat() / totalKb.toFloat() * 100f).coerceIn(0f, 100f)
    }
}
