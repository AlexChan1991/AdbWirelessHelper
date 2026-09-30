package com.adb.adbwirelesshelper

import com.adb.adbwirelesshelper.data.deviceinfo.DeviceInfoParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DeviceInfoParser] 的纯 JVM 单元测试。
 *
 * 全部 fixture 都是真实的 `adb shell` 输出片段（内联），
 * **不依赖任何 android.* 类**，可在 `./gradlew test` 下直接运行。
 */
class DeviceInfoParserTest {

    // ------------------------------------------------------------ Fixture

    private val getpropRaw = """
[ro.product.manufacturer]: [Google]
[ro.product.brand]: [google]
[ro.product.model]: [Pixel 8 Pro]
[ro.product.device]: [husky]
[ro.product.name]: [husky]
[ro.serialno]: [1A2B3C4D5E6F7]
[ro.build.version.release]: [15]
[ro.build.version.sdk]: [35]
[ro.build.display.id]: [AP3A.241005.015]
[ro.build.version.security_patch]: [2026-08-05]
[ro.build.fingerprint]: [google/husky/husky:15/AP3A.241005.015/12345678:user/release-keys]
[ro.product.cpu.abi]: [arm64-v8a]
[ro.product.cpu.abilist]: [arm64-v8a,armeabi-v7a,armeabi]
[ro.chipname]: []
[ro.hardware]: [husky]
[ro.board.platform]: [zuma]
[ro.sf.lcd_density]: [480]
    """.trimIndent()

    private val memInfoWithAvailable = """
MemTotal:       12582912 kB
MemFree:          512000 kB
MemAvailable:    6039140 kB
Buffers:          123456 kB
Cached:          4500000 kB
SwapCached:            0 kB
Active:          3000000 kB
Inactive:        2500000 kB
    """.trimIndent()

    /** 旧内核没有 MemAvailable 字段，必须回退 MemFree + Cached + Buffers。 */
    private val memInfoWithoutAvailable = """
MemTotal:        8000000 kB
MemFree:          512000 kB
Buffers:          123456 kB
Cached:          4500000 kB
SwapCached:            0 kB
    """.trimIndent()

    private val cpuStatFirst = """
cpu  100 0 50 800 10 0 0 0 0 0
cpu0 50 0 25 400 5 0 0 0 0 0
cpu1 50 0 25 400 5 0 0 0 0 0
intr 123456 0 0 0 0 0 0 0 0 0
ctxt 987654
btime 1700000000
    """.trimIndent()

    private val cpuStatSecond = """
cpu  110 0 60 850 12 0 0 0 0 0
cpu0 55 0 30 425 6 0 0 0 0 0
cpu1 55 0 30 425 6 0 0 0 0 0
intr 123999 0 0 0 0 0 0 0 0 0
ctxt 988000
btime 1700000000
    """.trimIndent()

    private val dfRaw = """
Filesystem           1K-blocks      Used Available Use% Mounted on
/dev/block/dm-38     117625592  73274624  44350968  63% /data
    """.trimIndent()

    private val batteryRaw = """
Current Battery Service state:
  AC powered: false
  USB powered: true
  Wireless powered: false
  Max charging current: 1500000
  status: 2
  health: 2
  present: true
  level: 78
  scale: 100
  voltage: 3900
  temperature: 305
  technology: Li-ion
    """.trimIndent()

    private val wmSizeRaw = """
Physical size: 1080x2400
Override size: 720x1600
    """.trimIndent()

    private val wmDensityRaw = """
Physical density: 420
Override density: 320
    """.trimIndent()

    private val ipRaw = """
12: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq state UP group default qlen 3000
    link/ether 12:34:56:78:9a:bc brd ff:ff:ff:ff:ff:ff
    inet 192.168.1.9/24 brd 192.168.1.255 scope global wlan0
       valid_lft forever preferred_lft forever
    """.trimIndent()

    private val topProcsRaw = """
NAME                        RSS
system_server            291000K
com.android.chrome       412000K
com.tencent.mm           338000K
zygote64                  24000K
surfaceflinger            18000K
    """.trimIndent()

    // ------------------------------------------------------------ parseProps

    @Test
    fun parseProps_readsBracketFormat() {
        val props = DeviceInfoParser.parseProps(getpropRaw)
        assertEquals("Pixel 8 Pro", props["ro.product.model"])
        assertEquals("Google", props["ro.product.manufacturer"])
        assertEquals("husky", props["ro.product.device"])
        assertEquals("15", props["ro.build.version.release"])
        assertEquals("35", props["ro.build.version.sdk"])
        assertEquals("2026-08-05", props["ro.build.version.security_patch"])
    }

    @Test
    fun parseProps_keepsFingerprintWithColons() {
        val props = DeviceInfoParser.parseProps(getpropRaw)
        assertEquals(
            "google/husky/husky:15/AP3A.241005.015/12345678:user/release-keys",
            props["ro.build.fingerprint"],
        )
    }

    @Test
    fun parseProps_emptyValueIsPreserved() {
        val props = DeviceInfoParser.parseProps(getpropRaw)
        assertTrue(props.containsKey("ro.chipname"))
        assertEquals("", props["ro.chipname"])
    }

    @Test
    fun parseProps_onEmptyInputReturnsEmptyMap() {
        assertTrue(DeviceInfoParser.parseProps("").isEmpty())
    }

    @Test
    fun parseProps_fallsBackToPlainKeyValue() {
        val props = DeviceInfoParser.parseProps("ro.product.model: Pixel 8 Pro\nro.board: zuma")
        assertEquals("Pixel 8 Pro", props["ro.product.model"])
        assertEquals("zuma", props["ro.board"])
    }

    // ------------------------------------------------------------ parseMemInfo

    @Test
    fun parseMemInfo_prefersMemAvailable() {
        val (total, available) = DeviceInfoParser.parseMemInfo(memInfoWithAvailable)
        assertEquals(12_582_912L, total)
        assertEquals(6_039_140L, available)
    }

    @Test
    fun parseMemInfo_fallsBackToFreeCachedBuffers() {
        val (total, available) = DeviceInfoParser.parseMemInfo(memInfoWithoutAvailable)
        assertEquals(8_000_000L, total)
        // 512000 + 4500000 + 123456 = 5135456，注意不能被 SwapCached 干扰
        assertEquals(5_135_456L, available)
    }

    @Test
    fun parseMemInfo_onGarbageReturnsNulls() {
        val (total, available) = DeviceInfoParser.parseMemInfo("this is not meminfo")
        assertNull(total)
        assertNull(available)
    }

    @Test
    fun memUsedPercent_computesRatio() {
        val percent = DeviceInfoParser.memUsedPercent(12_582_912L, 6_039_140L)
        assertNotNull(percent)
        assertEquals(52.0f, percent!!, 0.1f)
    }

    @Test
    fun memUsedPercent_onZeroTotalReturnsNull() {
        assertNull(DeviceInfoParser.memUsedPercent(0L, 0L))
        assertNull(DeviceInfoParser.memUsedPercent(null, 100L))
    }

    // ------------------------------------------------------------ CPU

    @Test
    fun parseCpuStat_onlyFirstAggregateLine() {
        val snapshot = DeviceInfoParser.parseCpuStat(cpuStatFirst)
        assertNotNull(snapshot)
        assertEquals(960L, snapshot!!.total)   // 100 + 0 + 50 + 800 + 10
        assertEquals(810L, snapshot.idle)      // idle(800) + iowait(10)
    }

    @Test
    fun parseCpuStat_onMissingCpuLineReturnsNull() {
        assertNull(DeviceInfoParser.parseCpuStat("intr 12345\nctxt 999\n"))
    }

    @Test
    fun calcCpuUsage_usesDelta() {
        val a = DeviceInfoParser.parseCpuStat(cpuStatFirst)
        val b = DeviceInfoParser.parseCpuStat(cpuStatSecond)
        val usage = DeviceInfoParser.calcCpuUsage(a, b)
        assertNotNull(usage)
        // Δtotal = 72，Δidle = 52 → (72 - 52) / 72 = 27.78%
        assertEquals(27.78, usage!!.toDouble(), 0.01)
    }

    @Test
    fun calcCpuUsage_onNullSnapshotReturnsNull() {
        assertNull(DeviceInfoParser.calcCpuUsage(null, null))
        assertNull(DeviceInfoParser.calcCpuUsage(DeviceInfoParser.CpuStatSnapshot(1L, 1L), null))
    }

    @Test
    fun calcCpuUsage_onZeroDeltaReturnsNull() {
        val snapshot = DeviceInfoParser.CpuStatSnapshot(total = 100L, idle = 50L)
        assertNull(DeviceInfoParser.calcCpuUsage(snapshot, snapshot))
    }

    @Test
    fun cpuNameFallback_skipsBlankAndFallsBack() {
        val props = DeviceInfoParser.parseProps(getpropRaw)
        // ro.chipname 为空 → 回退 ro.hardware
        assertEquals("husky", DeviceInfoParser.cpuNameFallback(props))

        val onlyPlatform = mapOf("ro.board.platform" to "zuma")
        assertEquals("zuma", DeviceInfoParser.cpuNameFallback(onlyPlatform))

        val chip = mapOf("ro.chipname" to "Snapdragon 8 Gen 3")
        assertEquals("Snapdragon 8 Gen 3", DeviceInfoParser.cpuNameFallback(chip))

        assertNull(DeviceInfoParser.cpuNameFallback(emptyMap()))
    }

    // ------------------------------------------------------------ parseDf

    @Test
    fun parseDf_readsSizeAndUsed() {
        val (size, used) = DeviceInfoParser.parseDf(dfRaw)
        assertEquals(117_625_592L, size)
        assertEquals(73_274_624L, used)
    }

    @Test
    fun parseDf_onEmptyReturnsNulls() {
        val (size, used) = DeviceInfoParser.parseDf("")
        assertNull(size)
        assertNull(used)
    }

    // ------------------------------------------------------------ parseBattery

    @Test
    fun parseBattery_readsLevelStatusScale() {
        val (level, status, scale) = DeviceInfoParser.parseBattery(batteryRaw)
        assertEquals(78, level)
        assertEquals(2, status)
        assertEquals(100, scale)
    }

    @Test
    fun batteryPercent_normalizesByScale() {
        assertEquals(78, DeviceInfoParser.batteryPercent(78, 100))
        assertEquals(50, DeviceInfoParser.batteryPercent(50, 100))
        // 某些设备 scale=1000
        assertEquals(78, DeviceInfoParser.batteryPercent(780, 1000))
        assertNull(DeviceInfoParser.batteryPercent(null, 100))
    }

    @Test
    fun parseBattery_onEmptyReturnsNulls() {
        val (level, status, scale) = DeviceInfoParser.parseBattery("")
        assertNull(level)
        assertNull(status)
        assertNull(scale)
    }

    // ------------------------------------------------------------ 屏幕 / 网络

    @Test
    fun parseWmSize_prefersPhysical() {
        val (w, h) = DeviceInfoParser.parseWmSize(wmSizeRaw)
        assertEquals(1080, w)
        assertEquals(2400, h)
    }

    @Test
    fun parseWmSize_onEmptyReturnsNulls() {
        val (w, h) = DeviceInfoParser.parseWmSize("")
        assertNull(w)
        assertNull(h)
    }

    @Test
    fun parseWmDensity_readsPhysicalDensity() {
        assertEquals(420, DeviceInfoParser.parseWmDensity(wmDensityRaw))
        assertNull(DeviceInfoParser.parseWmDensity(""))
    }

    @Test
    fun parseIp_readsWlan0Inet() {
        assertEquals("192.168.1.9", DeviceInfoParser.parseIp(ipRaw))
    }

    @Test
    fun parseIp_skipsLoopback() {
        assertEquals(
            "192.168.1.42",
            DeviceInfoParser.parseIp("inet 127.0.0.1/8 scope host lo\ninet 192.168.1.42/24 brd x"),
        )
    }

    @Test
    fun parseIp_onEmptyReturnsNull() {
        assertNull(DeviceInfoParser.parseIp(""))
    }

    @Test
    fun parsePower_detectsScreenState() {
        assertEquals(true, DeviceInfoParser.parsePower("mWakefulness=Awake"))
        assertEquals(false, DeviceInfoParser.parsePower("mWakefulness=Dozing\nmScreenOn=false"))
        assertEquals(true, DeviceInfoParser.parsePower("mScreenOn=true"))
        assertEquals(false, DeviceInfoParser.parsePower("Screen off state: OFF"))
        assertNull(DeviceInfoParser.parsePower("no hint here"))
    }

    // ------------------------------------------------------------ 其它

    @Test
    fun parseKeyValue_readsGenericPairs() {
        val pairs = DeviceInfoParser.parseKeyValue("level: 78\nstatus: 2")
        assertEquals(listOf("level" to "78", "status" to "2"), pairs)
    }

    @Test
    fun parseLoadAvg_takesFirstThree() {
        val load = DeviceInfoParser.parseLoadAvg("1.23 0.98 0.87 1/1234 56789")
        assertEquals(3, load.size)
        assertEquals(1.23f, load[0], 0.001f)
        assertEquals(0.98f, load[1], 0.001f)
        assertEquals(0.87f, load[2], 0.001f)
    }

    @Test
    fun parseKernel_takesFirstThreeSegments() {
        assertEquals(
            "Linux version 6.1.68-android14",
            DeviceInfoParser.parseKernel(
                "Linux version 6.1.68-android14 (build-user@build-host) " +
                    "(Android clang version 14.0.6) #1 SMP PREEMPT Mon Jan 1 00:00:00 UTC 2024",
            ),
        )
        assertNull(DeviceInfoParser.parseKernel("   "))
    }

    @Test
    fun parseUptimeSeconds_readsFirstFloat() {
        assertEquals(287_643L, DeviceInfoParser.parseUptimeSeconds("287643.12 112345.67"))
        assertNull(DeviceInfoParser.parseUptimeSeconds(""))
    }

    @Test
    fun parseTopProcs_skipsHeaderAndReadsKb() {
        val procs = DeviceInfoParser.parseTopProcs(topProcsRaw)
        assertEquals(5, procs.size)
        assertEquals("system_server", procs[0].name)
        assertEquals(291_000L, procs[0].pssKb)
        assertEquals("com.android.chrome", procs[1].name)
        assertEquals(412_000L, procs[1].pssKb)
    }

    @Test
    fun parseTopProcs_onEmptyReturnsEmptyList() {
        assertTrue(DeviceInfoParser.parseTopProcs("").isEmpty())
        assertTrue(DeviceInfoParser.parseTopProcs("NAME                        RSS").isEmpty())
    }

    @Test
    fun firstNonBlank_picksFirstUsableKey() {
        val props = mapOf("a" to "  ", "b" to "", "c" to "value")
        assertEquals("value", DeviceInfoParser.firstNonBlank(props, "a", "b", "c"))
        assertNull(DeviceInfoParser.firstNonBlank(props, "a", "b"))
    }
}
