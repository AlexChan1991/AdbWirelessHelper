package com.adb.adbwirelesshelper

import com.adb.adbwirelesshelper.domain.model.DeviceInfo
import com.adb.adbwirelesshelper.domain.model.ProcMem
import com.adb.adbwirelesshelper.ui.screen.mergeTopProcessesInternal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [mergeTopProcessesInternal] 的纯 JVM 单元测试。
 *
 * 存在的理由：设备详情页的 Top 进程区块曾以「出现 → 消失 → 出现」的节奏反复闪烁 ——
 * 轻量采集循环（`withHeavy = false`）拿到的 `topProcesses` 恒为空，会直接覆盖掉
 * 重量循环（3s）刚写进去的进程列表，而 UI 侧是用 `topProcesses.isNotEmpty()`
 * 决定要不要渲染这个区块的。这段回填逻辑出过一次真 bug 且**零测试覆盖**
 * （QA 风险项 R1），这里把它的 4 个分支全部钉死。
 *
 * 被测的是从 `DeviceDetailViewModel.mergeTopProcesses(fresh)` 里抽出来的纯函数版本：
 * ViewModel 自己依赖 Context / DataStore / `viewModelScope`，纯 JVM 单测构造不出来，
 * 所以只测逻辑本体；ViewModel 里那个成员函数仅仅是把 `_info.value` 作为
 * `previous` 传进来，没有别的逻辑。
 *
 * 所有 fixture 都是内联构造的，**不依赖任何 android.* 类**，
 * 也不需要 Robolectric，可在 `./gradlew test` 下直接运行。
 */
class DeviceDetailViewModelMergeTest {

    // ------------------------------------------------------------ Fixture

    /** 重量循环（3s）采回来的进程列表。 */
    private val heavyProcs: List<ProcMem> = listOf(
        ProcMem(name = "system_server", pssKb = 320_000L),
        ProcMem(name = "com.android.systemui", pssKb = 180_000L)
    )

    /** 另一份进程列表：用于验证 fresh 非空时不会被 previous 污染。 */
    private val otherProcs: List<ProcMem> = listOf(
        ProcMem(name = "com.example.demo", pssKb = 90_000L)
    )

    /**
     * 造一份 DeviceInfo。
     *
     * 只暴露本用例要用到的差异字段，其余走数据类默认值，
     * 免得无关字段干扰「哪些字段来自 fresh、哪些来自 previous」的判断。
     */
    private fun info(
        model: String = "Pixel 8 Pro",
        cpuUsagePercent: Float = 10.0f,
        memAvailableKb: Long = 2_000_000L,
        updatedAt: Long = 1_000L,
        topProcesses: List<ProcMem> = emptyList()
    ): DeviceInfo = DeviceInfo(
        serial = "192.168.1.42:39621",
        model = model,
        cpuUsagePercent = cpuUsagePercent,
        memAvailableKb = memAvailableKb,
        updatedAt = updatedAt,
        topProcesses = topProcesses
    )

    /** previous：进程列表非空，且非进程字段故意与 fresh 不同。 */
    private fun previousWithProcs(): DeviceInfo = info(
        model = "旧设备",
        cpuUsagePercent = 90.0f,
        memAvailableKb = 1_000_000L,
        updatedAt = 1_000L,
        topProcesses = heavyProcs
    )

    /** fresh：轻量循环的结果，进程列表为空，非进程字段与 previous 全都不一样。 */
    private fun freshLight(): DeviceInfo = info(
        model = "新设备",
        cpuUsagePercent = 12.5f,
        memAvailableKb = 2_000_000L,
        updatedAt = 2_500L,
        topProcesses = emptyList()
    )

    // ------------------------------------------------------------ 分支 1

    @Test
    fun `fresh 自带进程数据时直接采用 fresh，不拼接也不混入 previous`() {
        val previous: DeviceInfo = previousWithProcs()
        val fresh: DeviceInfo = freshLight().copy(topProcesses = otherProcs)

        val merged: DeviceInfo = mergeTopProcessesInternal(previous, fresh)

        // fresh 非空 → 原样采用 fresh 的那一份
        assertEquals(otherProcs, merged.topProcesses)
        // 必须是「覆盖」而不是「拼接」：不能变成 1 + 2 = 3 条
        assertEquals(1, merged.topProcesses.size)
        assertNotEquals(previous.topProcesses, merged.topProcesses)
    }

    // ------------------------------------------------------------ 分支 2

    @Test
    fun `fresh 为空而 previous 非空时，把 previous 的进程列表补回去`() {
        val previous: DeviceInfo = previousWithProcs()
        val fresh: DeviceInfo = freshLight()

        val merged: DeviceInfo = mergeTopProcessesInternal(previous, fresh)

        assertEquals(heavyProcs, merged.topProcesses)
        // 这正是修的那个 bug：补回去之后区块不能消失
        assertTrue("轻量结果也必须带着进程列表，否则区块会闪", merged.topProcesses.isNotEmpty())
    }

    // ------------------------------------------------------------ 分支 3

    @Test
    fun `fresh 为空且 previous 也为空时，原样返回 fresh`() {
        val previous: DeviceInfo = info(topProcesses = emptyList())
        val fresh: DeviceInfo = freshLight()

        val merged: DeviceInfo = mergeTopProcessesInternal(previous, fresh)

        assertTrue(merged.topProcesses.isEmpty())
        // 上一轮就没有进程数据 → 不该凭空造出区块
        assertEquals(fresh, merged)
    }

    // ------------------------------------------------------------ 分支 4

    @Test
    fun `previous 为 null 即首次采集时，原样返回 fresh`() {
        val fresh: DeviceInfo = freshLight()

        val merged: DeviceInfo = mergeTopProcessesInternal(previous = null, fresh = fresh)

        assertTrue(merged.topProcesses.isEmpty())
        assertEquals(fresh, merged)
    }

    // ------------------------------------------------------------ 附加断言

    @Test
    fun `回填只替换 topProcesses，其余字段必须取 fresh 的值`() {
        val previous: DeviceInfo = previousWithProcs()
        val fresh: DeviceInfo = freshLight()

        val merged: DeviceInfo = mergeTopProcessesInternal(previous, fresh)

        // 非进程字段全部跟随 fresh，不能沿用 previous
        assertEquals(fresh.model, merged.model)
        assertEquals(fresh.cpuUsagePercent, merged.cpuUsagePercent)
        assertEquals(fresh.memAvailableKb, merged.memAvailableKb)
        assertEquals(fresh.updatedAt, merged.updatedAt)
        assertEquals(fresh.serial, merged.serial)

        // 反向确认：这些值确实不等于 previous 的，上面的断言才不是「两边都是默认值」的假通过
        assertNotEquals(previous.model, merged.model)
        assertNotEquals(previous.cpuUsagePercent, merged.cpuUsagePercent)
        assertNotEquals(previous.memAvailableKb, merged.memAvailableKb)
        assertNotEquals(previous.updatedAt, merged.updatedAt)
    }

    @Test
    fun `连续多轮轻量采集不会把进程列表冲掉（闪烁回归）`() {
        // 真实时序：3s 的重量结果写进去 → 1.5s 的轻量结果连着覆盖两次
        val heavy: DeviceInfo = previousWithProcs()
        val light1: DeviceInfo = freshLight().copy(updatedAt = 2_500L)
        val light2: DeviceInfo = freshLight().copy(updatedAt = 4_000L)
        val light3: DeviceInfo = freshLight().copy(updatedAt = 5_500L)

        val after1: DeviceInfo = mergeTopProcessesInternal(heavy, light1)
        val after2: DeviceInfo = mergeTopProcessesInternal(after1, light2)
        val after3: DeviceInfo = mergeTopProcessesInternal(after2, light3)

        // 区块必须一直挂着，不能出现「有 → 无 → 有」
        assertEquals(heavyProcs, after1.topProcesses)
        assertEquals(heavyProcs, after2.topProcesses)
        assertEquals(heavyProcs, after3.topProcesses)

        // 但时间戳这类字段仍然每轮刷新，说明拿到的确实是 fresh 而不是旧的 previous
        assertEquals(2_500L, after1.updatedAt)
        assertEquals(4_000L, after2.updatedAt)
        assertEquals(5_500L, after3.updatedAt)
    }
}
