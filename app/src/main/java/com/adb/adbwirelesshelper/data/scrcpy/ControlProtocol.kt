package com.adb.adbwirelesshelper.data.scrcpy

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * scrcpy 控制通道二进制协议编码器。
 *
 * ⚠️ **布局必须以 scrcpy v3.3.2 官方源码为准**，权威依据两份文件：
 * - 服务端读：[`server/.../control/ControlMessageReader.java`]
 *   —— 决定「服务端到底吃多少字节、按什么顺序读」，是硬约束；
 * - 官方客户端写：[`app/src/control_msg.c`] 的 `sc_control_msg_serialize()`
 *   —— 决定「我们应该怎么写」。
 *
 * 设计约束：
 * - 纯 Kotlin + JDK，**禁止 import android.***，必须能在 JVM 上直接跑 JUnit 单测；
 * - 全部函数为纯函数：同样的入参产出同样的字节序列，无状态、无 IO；
 * - 所有多字节字段统一 **big-endian**（与官方 service 一致）。
 *
 * ## ★ 为什么这里写错一个字节就会「能看不能控」
 *
 * 控制通道是**无长度前缀的流式协议**：服务端先读 1 字节 type，再按 type 决定后面
 * 再读几个字节。所以**任何一条消息多写/少写一个字节，都会让后续所有消息整体错位** ——
 * 服务端会从下一条消息中间截取一段当 type，读到非法值时抛
 * `ControlProtocolException("Unknown event type")`，**整个控制线程就此死掉**。
 * 之后客户端再怎么写都不会有任何反应（socket 缓冲没满之前连异常都不会有）。
 *
 * 真机症状就是：**触控完全无效 + 工具条按钮（返回/主页/音量…）也全部无效**。
 * 因为只要用户先点了一下屏幕，那条写错的触控消息就已经把控制线程打死了。
 * ——「触控和按键一起失效」是本协议族最典型的「消息长度写错」特征，
 * 排查时优先核对长度，而不是去怀疑按键映射或坐标换算。
 *
 * ## 完整字节布局（对齐 v3.3.2，行尾为总长度）
 *
 * | 消息 | type | 布局 | 长度 |
 * |---|---|---|---|
 * | INJECT_KEYCODE | 0 | type(1) + action(1) + keycode(4) + repeat(4) + metaState(4) | 14B |
 * | INJECT_TEXT | 1 | type(1) + len(4) + UTF-8 bytes | 5B + N |
 * | INJECT_TOUCH_EVENT | 2 | type(1) + action(1) + pointerId(8) + x(4) + y(4) + screenW(2) + screenH(2) + pressure(2) + **actionButton(4)** + **buttons(4)** | **32B** |
 * | INJECT_SCROLL_EVENT | 3 | type(1) + x(4) + y(4) + screenW(2) + screenH(2) + hScroll(**2**, i16fp) + vScroll(**2**, i16fp) + buttons(4) | 21B |
 * | BACK_OR_SCREEN_ON | 4 | type(1) + action(1) | 2B |
 * | EXPAND_NOTIFICATION_PANEL | 5 | type(1) | 1B |
 * | EXPAND_SETTINGS_PANEL | 6 | type(1) | 1B |
 * | COLLAPSE_PANELS | 7 | type(1) | 1B |
 * | GET_CLIPBOARD | 8 | type(1) + copyKey(**1**) | **2B** |
 * | SET_CLIPBOARD | 9 | type(1) + sequence(8) + paste(**1**) + len(4) + UTF-8 bytes | 14B + N |
 * | SET_DISPLAY_POWER | 10 | type(1) + on(**1**, 0/1) | 2B |
 * | ROTATE_DEVICE | 11 | type(1) | 1B |
 * | UHID_CREATE / UHID_INPUT / UHID_DESTROY | 12/13/14 | （本工程不使用） | — |
 * | OPEN_HARD_KEYBOARD_SETTINGS | 15 | type(1) | 1B |
 * | START_APP | 16 | type(1) + len(1) + UTF-8 bytes | 2B + N |
 * | RESET_VIDEO | 17 | type(1) | 1B |
 *
 * 历史坑（**已修的严重 bug，勿回退**）：
 * 1. `INJECT_TOUCH_EVENT` 曾只写 28 字节（漏了 `actionButton(4)`）→ **每动一下手指就把
 *    控制线程打死**，表现就是「触控和所有按钮同时失灵」；
 * 2. type 表曾停留在旧版本（`COLLAPSE_PANELS` 记成 6、`GET_CLIPBOARD` 记成 7、
 *    `SET_CLIPBOARD` 记成 8、`SET_DISPLAY_POWER` 记成 9、`ROTATE_DEVICE` 记成 10）→
 *    这些消息会被服务端按**另一个消息的布局**去解析，同样造成错位与线程死亡。
 */
object ControlProtocol {

    // ---------- 消息类型（严格对齐 v3.3.2 ControlMessage.java） ----------
    private const val TYPE_INJECT_KEYCODE: Int = 0
    private const val TYPE_INJECT_TEXT: Int = 1
    private const val TYPE_INJECT_TOUCH_EVENT: Int = 2
    private const val TYPE_INJECT_SCROLL_EVENT: Int = 3
    private const val TYPE_BACK_OR_SCREEN_ON: Int = 4
    private const val TYPE_EXPAND_NOTIFICATION_PANEL: Int = 5
    private const val TYPE_EXPAND_SETTINGS_PANEL: Int = 6
    private const val TYPE_COLLAPSE_PANELS: Int = 7
    private const val TYPE_GET_CLIPBOARD: Int = 8
    private const val TYPE_SET_CLIPBOARD: Int = 9
    private const val TYPE_SET_DISPLAY_POWER: Int = 10
    private const val TYPE_ROTATE_DEVICE: Int = 11

    // ---------- 按键动作（对齐 android.view.KeyEvent） ----------
    const val KEY_ACTION_DOWN: Int = 0
    const val KEY_ACTION_UP: Int = 1

    // ---------- 触摸动作（对齐 android.view.MotionEvent.ACTION_*） ----------
    const val TOUCH_ACTION_DOWN: Int = 0
    const val TOUCH_ACTION_UP: Int = 1
    const val TOUCH_ACTION_MOVE: Int = 2

    /** 触摸压力：u16 定点（`u16FixedPointToFloat` 即 value/65536），按下/移动用最大值。 */
    const val PRESSURE_MAX: Int = 0xFFFF

    /** 触摸消息里的 actionButton 字段：手指触摸恒为 0（服务端会自行推导 buttonState）。 */
    const val ACTION_BUTTON_NONE: Int = 0

    /** 鼠标按键掩码（INJECT_SCROLL_EVENT 用）；手指滚动恒为 0。 */
    const val BUTTONS_NONE: Int = 0

    // ---------- 常用 Android 键码（对齐 android.view.KeyEvent） ----------
    const val KEYCODE_HOME: Int = 3
    const val KEYCODE_BACK: Int = 4
    const val KEYCODE_POWER: Int = 26
    const val KEYCODE_VOLUME_UP: Int = 24
    const val KEYCODE_VOLUME_DOWN: Int = 25
    const val KEYCODE_APP_SWITCH: Int = 187
    const val KEYCODE_WAKEUP: Int = 224
    const val KEYCODE_SLEEP: Int = 223

    /** 第一个手指的 pointerId，与 Android 惯例一致。 */
    const val DEFAULT_POINTER_ID: Long = 0L

    /** BACK_OR_SCREEN_ON 的动作取值：按下=0，抬起=1（与 KeyEvent 对齐）。 */
    const val BACK_OR_SCREEN_ON_ACTION_DOWN: Int = 0
    const val BACK_OR_SCREEN_ON_ACTION_UP: Int = 1

    /** 剪贴板 `copyKey` 取值（对齐官方 COPY_KEY_*）。 */
    const val COPY_KEY_NONE: Int = 0
    const val COPY_KEY_COPY: Int = 1
    const val COPY_KEY_CUT: Int = 2

    /**
     * INJECT_KEYCODE：按键注入。
     *
     * @param action    [KEY_ACTION_DOWN] 或 [KEY_ACTION_UP]
     * @param keycode   Android 键码，如 [KEYCODE_BACK]
     * @param repeat    重复次数，普通按键为 0
     * @param metaState Meta 修饰键位掩码，无则 0
     * @return 固定 14 字节
     */
    fun injectKeycode(action: Int, keycode: Int, repeat: Int, metaState: Int): ByteArray {
        return ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(TYPE_INJECT_KEYCODE.toByte())
                put(action.toByte())
                putInt(keycode)
                putInt(repeat)
                putInt(metaState)
            }
            .array()
    }

    /**
     * INJECT_TEXT：直接注入文本（不经过软键盘，支持中文）。
     *
     * 官方客户端把长度上限设为 300 字节（`SC_CONTROL_MSG_INJECT_TEXT_MAX_LENGTH`），
     * 服务端读取时无硬上限，因此超长文本不会被拒绝，只是不保证与官方行为一致。
     *
     * @param text 待注入文本，按 UTF-8 编码
     * @return 5 + N 字节
     */
    fun injectText(text: String): ByteArray {
        val payload: ByteArray = text.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(5 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(TYPE_INJECT_TEXT.toByte())
                putInt(payload.size)
                put(payload)
            }
            .array()
    }

    /**
     * INJECT_TOUCH_EVENT：触摸注入。**固定 32 字节**。
     *
     * 字段顺序严格对齐 `ControlMessageReader.parseInjectTouchEvent()`：
     * `action(1) + pointerId(8) + x(4) + y(4) + screenW(2) + screenH(2) + pressure(2)
     * + actionButton(4) + buttons(4)`。
     *
     * ⚠️ 两个不可省略的 4 字节字段 —— 它们的约束是**「必须占位」而不是「必须传值」**：
     * - `actionButton`：服务端只在 `SOURCE_MOUSE` 分支里使用它；手指触摸走的是
     *   `Controller.injectTouch()` 的触摸分支，**该字段永远不被应用**。所以传 0 是安全的。
     * - `buttons`：手指触摸时服务端会**强制置 0**（`pointerId` 不是 `POINTER_ID_MOUSE` 时）。
     *
     * 换句话说：这两个字段即使传 0 也**必须各占满 4 字节** —— 省略任何一个都会让整条流错位
     * （见类注释里的「为什么错一个字节就全废」）。这是本文件最容易被「优化掉」的地方：
     * 看上去字段值是 0 就以为可以不写，恰恰是那个不写导致触控与按键同时全废。
     *
     * ⚠️ `screenW/screenH` 必须**等于服务端当前的 videoSize**：服务端
     * `PositionMapper.map()` 拿它与自身尺寸做严格相等比较，不等就 `return null`
     * **整条事件被丢弃**（不是坐标偏移，是完全没反应）。所以调用方必须传
     * 「与视频一致的分辨率」，而不是设备物理屏幕尺寸。
     *
     * @param action    [TOUCH_ACTION_DOWN] / [TOUCH_ACTION_UP] / [TOUCH_ACTION_MOVE]
     * @param pointerId 指针 id，单指固定用 [DEFAULT_POINTER_ID]
     * @param x,y       视频坐标系下的触点位置（像素）
     * @param screenW,H 与视频一致的远端分辨率
     * @param pressure  压力：[PRESSURE_MAX] 或抬起时的 0
     * @param actionButton 触发动作的按键，手指触摸传 [ACTION_BUTTON_NONE]
     * @param buttons   按键状态掩码，触摸恒为 [BUTTONS_NONE]
     * @return 固定 32 字节
     */
    fun injectTouch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        screenW: Int,
        screenH: Int,
        pressure: Int,
        actionButton: Int = ACTION_BUTTON_NONE,
        buttons: Int = BUTTONS_NONE
    ): ByteArray {
        return ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(TYPE_INJECT_TOUCH_EVENT.toByte())
                put(action.toByte())
                putLong(pointerId)
                putInt(x)
                putInt(y)
                putShort(safeSize(screenW).toShort())
                putShort(safeSize(screenH).toShort())
                putShort(safeU16(pressure).toShort())
                putInt(actionButton)
                putInt(buttons)
            }
            .array()
    }

    /**
     * INJECT_SCROLL_EVENT：滚轮/惯性滚动注入。**固定 21 字节**。
     *
     * 滚动量在协议里是 **i16 定点数**（`i16FixedPointToFloat` 即 value/32768），
     * 服务端再乘 16 还原成 `[-16, 16]` 的滚动单位（见 `parseInjectScrollEvent`）。
     * 所以这里传「滚动单位」整数，内部按 `value / 16 → i16fp` 换算，即 `value * 2048`。
     *
     * @param x,y        触点位置
     * @param screenW,H  与视频一致的远端分辨率
     * @param hScroll    水平滚动量（正=右），单位 [-16, 16]
     * @param vScroll    垂直滚动量（正=下），单位 [-16, 16]
     * @param buttons    按键状态掩码，手指滚动传 [BUTTONS_NONE]
     * @return 固定 21 字节
     */
    fun injectScroll(
        x: Int,
        y: Int,
        screenW: Int,
        screenH: Int,
        hScroll: Int,
        vScroll: Int,
        buttons: Int = BUTTONS_NONE
    ): ByteArray {
        return ByteBuffer.allocate(21).order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(TYPE_INJECT_SCROLL_EVENT.toByte())
                putInt(x)
                putInt(y)
                putShort(safeSize(screenW).toShort())
                putShort(safeSize(screenH).toShort())
                putShort(scrollToI16FixedPoint(hScroll).toShort())
                putShort(scrollToI16FixedPoint(vScroll).toShort())
                putInt(buttons)
            }
            .array()
    }

    /**
     * BACK_OR_SCREEN_ON：息屏时等价于「亮屏」，亮屏时等价于「返回」。
     *
     * @param action [BACK_OR_SCREEN_ON_ACTION_DOWN] 或 [BACK_OR_SCREEN_ON_ACTION_UP]
     * @return 固定 2 字节
     */
    fun injectBackOrScreenOn(action: Int): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(TYPE_BACK_OR_SCREEN_ON.toByte())
                put(action.toByte())
            }
            .array()
    }

    /**
     * SET_CLIPBOARD：把本机剪贴板写入远端。
     *
     * 注意 `paste` 那 1 个字节**不能省**：服务端解析顺序是
     * `sequence(8) → paste(1) → len(4) → text`，漏了它会让后面全部错位。
     *
     * @param sequence 单调递增序号，用于去重
     * @param text     剪贴板文本
     * @param paste    是否顺带触发一次「粘贴」动作
     * @return 14 + N 字节
     */
    fun setClipboard(sequence: Long, text: String, paste: Boolean = false): ByteArray {
        val payload: ByteArray = text.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(14 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(TYPE_SET_CLIPBOARD.toByte())
                putLong(sequence)
                put(if (paste) 1 else 0)
                putInt(payload.size)
                put(payload)
            }
            .array()
    }

    /**
     * GET_CLIPBOARD：请求远端剪贴板。
     *
     * `copyKey` 只占 **1 字节**（服务端 `readUnsignedByte()`），写成 4 字节会错位。
     *
     * @param copyKey [COPY_KEY_NONE] / [COPY_KEY_COPY] / [COPY_KEY_CUT]
     * @return 固定 2 字节
     */
    fun getClipboard(copyKey: Int): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(TYPE_GET_CLIPBOARD.toByte())
                put(copyKey.coerceIn(0, 0xFF).toByte())
            }
            .array()
    }

    /**
     * SET_DISPLAY_POWER：点亮 / 息屏远端屏幕（v2.x 里叫 SET_SCREEN_POWER_MODE，
     * v3.x 已改名且 type 从 9 变成 10）。
     *
     * @param on true = 正常亮屏，false = 息屏
     * @return 固定 2 字节
     */
    fun setDisplayPower(on: Boolean): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(TYPE_SET_DISPLAY_POWER.toByte())
                put(if (on) 1 else 0)
            }
            .array()
    }

    /** ROTATE_DEVICE：旋转远端设备屏幕。固定 1 字节（type=11）。 */
    fun rotateDevice(): ByteArray {
        return byteArrayOf(TYPE_ROTATE_DEVICE.toByte())
    }

    /** EXPAND_NOTIFICATION_PANEL：展开通知栏。固定 1 字节（type=5）。 */
    fun expandNotificationPanel(): ByteArray {
        return byteArrayOf(TYPE_EXPAND_NOTIFICATION_PANEL.toByte())
    }

    /** EXPAND_SETTINGS_PANEL：展开快捷设置面板。固定 1 字节（type=6）。 */
    fun expandSettingsPanel(): ByteArray {
        return byteArrayOf(TYPE_EXPAND_SETTINGS_PANEL.toByte())
    }

    /**
     * COLLAPSE_PANELS：收起通知栏 / 设置面板。固定 1 字节。
     * ⚠️ type 是 **7**（不是 6 —— 6 是 EXPAND_SETTINGS_PANEL）。
     */
    fun collapseNotificationPanel(): ByteArray {
        return byteArrayOf(TYPE_COLLAPSE_PANELS.toByte())
    }

    /**
     * 分辨率字段为 2 字节无符号（0..65535），越界时钳位而不是让 Short 静默溢出。
     * 负数一律按 0 处理，避免 Short 转换后变成一个巨大的无符号值。
     */
    private fun safeSize(value: Int): Int = value.coerceIn(0, 65535)

    /** 压力是 u16 无符号，同样钳位而不是让它溢出成负数。 */
    private fun safeU16(value: Int): Int = value.coerceIn(0, 65535)

    /**
     * 滚动单位（[-16, 16]）→ i16 定点。
     *
     * 服务端：`i16FixedPointToFloat(v) * 16`，其中 `i16FixedPointToFloat(v) = v / 32768`，
     * 所以 `v = 单位值 / 16 * 32768 = 单位值 * 2048`。钳位到 [-16, 16] 与官方客户端一致。
     */
    private fun scrollToI16FixedPoint(unit: Int): Int {
        val clamped: Int = unit.coerceIn(-16, 16)
        return (clamped * 2048).coerceIn(-32768, 32767)
    }
}
