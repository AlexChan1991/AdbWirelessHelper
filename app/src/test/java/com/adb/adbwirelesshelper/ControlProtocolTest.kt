package com.adb.adbwirelesshelper

import com.adb.adbwirelesshelper.data.scrcpy.ControlProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * scrcpy 控制协议编码器的纯 JVM 单测（JUnit4）。
 *
 * ## ⚠️ 这些断言的**唯一权威依据**是 scrcpy v3.3.2 官方源码
 *
 * - 服务端读：[`server/.../control/ControlMessageReader.java`]（决定真实字节数）
 * - 官方客户端写：[`app/src/control_msg.c`] `sc_control_msg_serialize()`（决定我们应该怎么写）
 *
 * **本文件的价值完全取决于「断言是不是从官方源码抄下来的」**。曾经这里断言
 * `INJECT_TOUCH_EVENT` 是 28 字节，而编码器也正好写 28 字节 —— 两边基于**同一个错误认知**，
 * 于是单测全绿、真机上却「触控和所有按钮同时失灵」（服务端实际读 32 字节，整条流错位，
 * 控制线程被 `Unknown event type` 打死）。**「测试通过」不等于「协议正确」，
 * 布局必须逐字段对着上游源码核，不能对着自己的代码核。**
 *
 * 覆盖点：
 * - 每种消息的包长必须与协议表一致；
 * - 关键字段的偏移量与 **big-endian** 字节序必须正确；
 * - 触摸/按键等高频消息需在真实参数下逐字段校验，避免「长度对了但位置错了」。
 *
 * 本文件禁止 import android.*，保证可以直接跑 `./gradlew test`。
 */
class ControlProtocolTest {

    private fun bufferOf(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

    /** 把 short 按无符号读，方便和协议表里的 u16 直接比。 */
    private fun ByteBuffer.u16(): Int = short.toInt() and 0xFFFF

    @Test
    fun injectKeycode_hasFourteenBytesAndCorrectLayout() {
        val packet: ByteArray = ControlProtocol.injectKeycode(
            action = ControlProtocol.KEY_ACTION_DOWN,
            keycode = ControlProtocol.KEYCODE_BACK,
            repeat = 0,
            metaState = 0
        )

        assertEquals("INJECT_KEYCODE 包长应为 14 字节", 14, packet.size)

        val buffer: ByteBuffer = bufferOf(packet)
        assertEquals("type 应为 0", 0.toByte(), buffer.get())
        assertEquals("action 应为 DOWN(0)", ControlProtocol.KEY_ACTION_DOWN.toByte(), buffer.get())
        assertEquals("第 2 字节起是 big-endian keycode", ControlProtocol.KEYCODE_BACK, buffer.int)
        assertEquals("第 6 字节起是 repeat", 0, buffer.int)
        assertEquals("第 10 字节起是 metaState", 0, buffer.int)
        assertEquals("读完后不应有剩余字节", 0, buffer.remaining())
    }

    @Test
    fun injectKeycode_encodesMetaStateInBigEndian() {
        val packet: ByteArray = ControlProtocol.injectKeycode(
            action = ControlProtocol.KEY_ACTION_UP,
            keycode = ControlProtocol.KEYCODE_APP_SWITCH,
            repeat = 2,
            metaState = 0x0000_1001
        )
        val buffer: ByteBuffer = bufferOf(packet)

        assertEquals(14, packet.size)
        assertEquals(0.toByte(), buffer.get())
        assertEquals(ControlProtocol.KEY_ACTION_UP.toByte(), buffer.get())
        assertEquals(ControlProtocol.KEYCODE_APP_SWITCH, buffer.int)
        assertEquals(2, buffer.int)
        assertEquals(0x0000_1001, buffer.int)

        // metaState 高字节必须在末位：显式验证 big-endian
        assertEquals(0x00.toByte(), packet[10])
        assertEquals(0x00.toByte(), packet[11])
        assertEquals(0x10.toByte(), packet[12])
        assertEquals(0x01.toByte(), packet[13])
    }

    @Test
    fun injectText_hasFiveBytesPlusUtf8Payload() {
        val packet: ByteArray = ControlProtocol.injectText("ab")

        assertEquals("INJECT_TEXT = 1 + 4 + UTF-8 长度", 5 + 2, packet.size)

        val buffer: ByteBuffer = bufferOf(packet)
        assertEquals("type 应为 1", 1.toByte(), buffer.get())
        assertEquals("长度字段应为 UTF-8 字节数", 2, buffer.int)
        assertEquals('a'.code.toByte(), buffer.get())
        assertEquals('b'.code.toByte(), buffer.get())
    }

    @Test
    fun injectText_supportsMultibyteUtf8() {
        val text: String = "中"
        val packet: ByteArray = ControlProtocol.injectText(text)
        val utf8: ByteArray = text.toByteArray(Charsets.UTF_8)

        assertEquals(3, utf8.size)
        assertEquals(5 + 3, packet.size)
        val textBuffer: ByteBuffer = bufferOf(packet)
        textBuffer.position(1)
        assertEquals(3, textBuffer.int)
        assertArrayEquals(utf8, packet.copyOfRange(5, packet.size))
    }

    /**
     * ★ 回归测试：`INJECT_TOUCH_EVENT` 必须是 **32 字节**，且末尾两个 4 字节字段一个都不能少。
     *
     * 服务端 `parseInjectTouchEvent()` 的读取顺序是：
     * `action(1) + pointerId(8) + x(4) + y(4) + screenW(2) + screenH(2) + pressure(2)
     * + actionButton(4) + buttons(4)` = 32。
     *
     * 曾经这里只写 28 字节（漏了 `actionButton`），服务端就会把**下一条消息的前 4 字节**
     * 当成 `buttons` 吃掉，之后整条流错位 → 控制线程抛 `Unknown event type` 死掉 →
     * 「能看不能控」，连返回/主页/音量键也一起失灵。
     */
    @Test
    fun injectTouch_hasThirtyTwoBytesWithActionButtonAndButtons() {
        val packet: ByteArray = ControlProtocol.injectTouch(
            action = ControlProtocol.TOUCH_ACTION_DOWN,
            pointerId = ControlProtocol.DEFAULT_POINTER_ID,
            x = 540,
            y = 1180,
            screenW = 1080,
            screenH = 2400,
            pressure = ControlProtocol.PRESSURE_MAX
        )

        assertEquals("INJECT_TOUCH_EVENT 包长必须为 32 字节（1+1+8+4+4+2+2+2+4+4）", 32, packet.size)

        val buffer: ByteBuffer = bufferOf(packet)
        assertEquals("type 应为 2", 2.toByte(), buffer.get())
        assertEquals("action 应为 DOWN(0)", ControlProtocol.TOUCH_ACTION_DOWN.toByte(), buffer.get())
        assertEquals("pointerId 为 8 字节", ControlProtocol.DEFAULT_POINTER_ID, buffer.long)
        assertEquals("x 为 4 字节", 540, buffer.int)
        assertEquals("y 为 4 字节", 1180, buffer.int)
        assertEquals("screenW 为 2 字节无符号", 1080, buffer.u16())
        assertEquals("screenH 为 2 字节无符号", 2400, buffer.u16())
        assertEquals("pressure 为 2 字节无符号", 65535, buffer.u16())
        assertEquals("actionButton 为 4 字节（手指触摸为 0）", 0, buffer.int)
        assertEquals("buttons 为 4 字节（手指触摸为 0）", 0, buffer.int)
        assertEquals("读完后不应有剩余字节", 0, buffer.remaining())
    }

    /** 逐字节核对 32 字节布局的偏移量，避免「长度对了但字段位置错了」。 */
    @Test
    fun injectTouch_fieldOffsetsAreExact() {
        val packet: ByteArray = ControlProtocol.injectTouch(
            action = ControlProtocol.TOUCH_ACTION_MOVE,
            pointerId = 0x0102_0304_0506_0708L,
            x = -5,
            y = 7,
            screenW = 1080,
            screenH = 2400,
            pressure = 0x8000,
            actionButton = 0x0000_0001,
            buttons = 0x0000_0002
        )

        assertEquals(32, packet.size)

        assertEquals("type 在偏移 0", 2.toByte(), packet[0])
        assertEquals("action 在偏移 1", ControlProtocol.TOUCH_ACTION_MOVE.toByte(), packet[1])
        // pointerId：8 字节 big-endian，占偏移 2..9
        assertEquals(0x01.toByte(), packet[2])
        assertEquals(0x08.toByte(), packet[9])
        // x：偏移 10..13（有符号，-5 = 0xFFFFFFFB）
        assertEquals(0xFF.toByte(), packet[10])
        assertEquals(0xFB.toByte(), packet[13])
        // y：偏移 14..17
        assertEquals(0x00.toByte(), packet[14])
        assertEquals(0x07.toByte(), packet[17])
        // screenW/screenH：偏移 18..21
        assertEquals(1080, ((packet[18].toInt() and 0xFF) shl 8) or (packet[19].toInt() and 0xFF))
        assertEquals(2400, ((packet[20].toInt() and 0xFF) shl 8) or (packet[21].toInt() and 0xFF))
        // pressure：偏移 22..23
        assertEquals(0x80.toByte(), packet[22])
        assertEquals(0x00.toByte(), packet[23])
        // actionButton：偏移 24..27
        assertEquals(0x01.toByte(), packet[27])
        // buttons：偏移 28..31
        assertEquals(0x02.toByte(), packet[31])
    }

    @Test
    fun injectTouch_upUsesZeroPressure() {
        val packet: ByteArray = ControlProtocol.injectTouch(
            action = ControlProtocol.TOUCH_ACTION_UP,
            pointerId = ControlProtocol.DEFAULT_POINTER_ID,
            x = 10,
            y = 20,
            screenW = 1080,
            screenH = 2400,
            pressure = 0
        )
        val buffer: ByteBuffer = bufferOf(packet)

        assertEquals(32, packet.size)
        assertEquals(2.toByte(), buffer.get())
        assertEquals(ControlProtocol.TOUCH_ACTION_UP.toByte(), buffer.get())
        assertEquals(0L, buffer.long)
        assertEquals(10, buffer.int)
        assertEquals(20, buffer.int)
        assertEquals(1080, buffer.u16())
        assertEquals(2400, buffer.u16())
        assertEquals("抬起压力必须为 0", 0, buffer.u16())
        assertEquals(0, buffer.int)
        assertEquals(0, buffer.int)
        assertEquals(0, buffer.remaining())
    }

    /**
     * 滚动量在协议里是 **i16 定点数**（服务端 `i16FixedPointToFloat(v) * 16`），
     * 即 `v = 单位值 * 2048`。这里用 `-3` 验证：`-3 * 2048 = -6144`。
     */
    @Test
    fun injectScroll_hasTwentyOneBytesAndCorrectLayout() {
        val packet: ByteArray = ControlProtocol.injectScroll(
            x = 100,
            y = 200,
            screenW = 1080,
            screenH = 2400,
            hScroll = 0,
            vScroll = -3
        )

        assertEquals("INJECT_SCROLL_EVENT = 1+4+4+2+2+2+2+4", 21, packet.size)

        val buffer: ByteBuffer = bufferOf(packet)
        assertEquals(3.toByte(), buffer.get())
        assertEquals(100, buffer.int)
        assertEquals(200, buffer.int)
        assertEquals(1080, buffer.u16())
        assertEquals(2400, buffer.u16())
        assertEquals("hScroll 为 i16 定点", 0, buffer.short.toInt())
        assertEquals("vScroll = -3 单位 → -3 * 2048", -6144, buffer.short.toInt())
        assertEquals("buttons 为 4 字节", 0, buffer.int)
        assertEquals(0, buffer.remaining())
    }

    /**
     * 滚动量越界要钳到 `[-16, 16]`（与官方客户端 `CLAMP(..., -1, 1)` 语义一致）。
     *
     * ⚠️ 上下界的期望值**不对称**，别照抄公式：
     * `16 * 2048 = 32768` 已经超出 i16 上限，官方 `sc_float_to_i16fp(1.0f)` 的做法是
     * `i = 1.0 * 32768 = 32768`，再因为 `i >= 0x7fff` 被钳成 **0x7fff = 32767**；
     * 而服务端解码时 `i16FixedPointToFloat` 特意把 `0x7fff` 当作 **1.0** 特判。
     * 所以正向饱和值是 `Short.MAX_VALUE`（32767），不是 32768。
     * 负向 `-16 * 2048 = -32768` 恰好在 i16 范围内，就是 `Short.MIN_VALUE`。
     */
    @Test
    fun injectScroll_clampsToProtocolRange() {
        val packet: ByteArray = ControlProtocol.injectScroll(
            x = 0,
            y = 0,
            screenW = 1080,
            screenH = 2400,
            hScroll = 999,
            vScroll = -999
        )
        val buffer: ByteBuffer = bufferOf(packet)
        buffer.position(13)
        assertEquals("正向饱和是 0x7fff（32767），不是 32768", Short.MAX_VALUE.toInt(), buffer.short.toInt())
        assertEquals("负向饱和是 -32768", Short.MIN_VALUE.toInt(), buffer.short.toInt())
    }

    @Test
    fun simpleMessages_haveExpectedLengthAndType() {
        val backDown: ByteArray = ControlProtocol.injectBackOrScreenOn(
            ControlProtocol.BACK_OR_SCREEN_ON_ACTION_DOWN
        )
        assertEquals(2, backDown.size)
        assertEquals(4.toByte(), backDown[0])
        assertEquals(0.toByte(), backDown[1])

        // ★ 注意 type 是 10（SET_DISPLAY_POWER），v2.x 的 SET_SCREEN_POWER_MODE(9) 已废弃
        val off: ByteArray = ControlProtocol.setDisplayPower(false)
        assertEquals(2, off.size)
        assertEquals(10.toByte(), off[0])
        assertEquals(0.toByte(), off[1])

        val on: ByteArray = ControlProtocol.setDisplayPower(true)
        assertEquals(2, on.size)
        assertEquals(10.toByte(), on[0])
        assertEquals(1.toByte(), on[1])

        // ★ ROTATE_DEVICE 的 type 是 11（不是 10 —— 10 已被 SET_DISPLAY_POWER 占用）
        val rotate: ByteArray = ControlProtocol.rotateDevice()
        assertEquals(1, rotate.size)
        assertEquals(11.toByte(), rotate[0])

        val expand: ByteArray = ControlProtocol.expandNotificationPanel()
        assertEquals(1, expand.size)
        assertEquals(5.toByte(), expand[0])

        val expandSettings: ByteArray = ControlProtocol.expandSettingsPanel()
        assertEquals(1, expandSettings.size)
        assertEquals(6.toByte(), expandSettings[0])

        // ★ COLLAPSE_PANELS 的 type 是 7（6 是 EXPAND_SETTINGS_PANEL）
        val collapse: ByteArray = ControlProtocol.collapseNotificationPanel()
        assertEquals(1, collapse.size)
        assertEquals(7.toByte(), collapse[0])
    }

    @Test
    fun clipboardMessages_haveExpectedLayout() {
        // ★ type 是 8，copyKey 只占 1 字节 → 总共 2 字节
        val get: ByteArray = ControlProtocol.getClipboard(ControlProtocol.COPY_KEY_COPY)
        assertEquals(2, get.size)
        assertEquals(8.toByte(), get[0])
        assertEquals(1.toByte(), get[1])

        val sequence: Long = 0x0102_0304_0506_0708L
        val set: ByteArray = ControlProtocol.setClipboard(sequence, "hi")
        // ★ type(1) + sequence(8) + paste(1) + len(4) + text(N) = 14 + N
        assertEquals("SET_CLIPBOARD = 1 + 8 + 1 + 4 + N", 14 + 2, set.size)
        assertEquals(9.toByte(), set[0])

        val buffer: ByteBuffer = bufferOf(set)
        buffer.position(1)
        assertEquals(sequence, buffer.long)
        assertEquals("paste 标志必须是 1 个字节", 0, buffer.get().toInt())
        assertEquals(2, buffer.int)
        assertEquals('h'.code.toByte(), buffer.get())
        assertEquals('i'.code.toByte(), buffer.get())
        assertEquals(0, buffer.remaining())
    }

    @Test
    fun setClipboard_encodesPasteFlag() {
        val set: ByteArray = ControlProtocol.setClipboard(1L, "x", paste = true)
        assertEquals(14 + 1, set.size)
        assertEquals("paste=true 时该字节为 1", 1.toByte(), set[9])
    }

    @Test
    fun oversizedScreenSize_isClampedNotOverflowed() {
        val packet: ByteArray = ControlProtocol.injectTouch(
            action = ControlProtocol.TOUCH_ACTION_MOVE,
            pointerId = 0L,
            x = 1,
            y = 1,
            screenW = 100_000,
            screenH = 65_535,
            pressure = ControlProtocol.PRESSURE_MAX
        )
        val buffer: ByteBuffer = bufferOf(packet)

        assertEquals(32, packet.size)
        buffer.position(18) // 1B type + 1B action + 8B pointerId + 4B x + 4B y
        assertEquals("越界分辨率应被钳位到 65535", 65535, buffer.u16())
        assertEquals("边界值应原样保留", 65535, buffer.u16())
    }
}
