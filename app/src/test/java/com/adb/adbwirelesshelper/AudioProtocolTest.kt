package com.adb.adbwirelesshelper

import com.adb.adbwirelesshelper.data.scrcpy.ScrcpyController
import com.adb.adbwirelesshelper.domain.model.ScrcpyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 音频转发链路（scrcpy audio socket）的回归测试。
 *
 * ## 断言来源（全部独立于实现，取自 scrcpy v3.3.2 服务端源码）
 * - `audio/AudioCodec.java`：`OPUS(0x6f_70_75_73, "opus")` / `AAC(0x00_61_61_63, "aac")` /
 *   `FLAC(0x66_6c_61_63, "flac")` / `RAW(0x00_72_61_77, "raw")`；源码原注释写明 id 就是
 *   "4-byte ASCII representation of the name"。
 * - `device/Streamer.java#writeAudioHeader()`：流头 = **4 字节大端** `codec.getId()`，
 *   受 `sendCodecMeta` 控制。
 * - `device/Streamer.java#writeDisableStream(boolean)`：禁用码也是 **4 字节**；
 *   `error=false` → 全 0；`error=true` → `code[3]=1`。
 * - `device/DesktopConnection.java#open()`：tunnel_forward 下按 **video → audio → control**
 *   顺序 `accept()`，`sendDummyByte` 只写给**第一条**被 accept 的 socket（即 video），
 *   写完即置 false —— 所以 audio socket 上**没有** dummy byte。
 * - `device/Streamer.java#writePacket`：音频包与视频帧共用 12 字节包头
 *   （8B `ptsAndFlags`：bit63=config、bit62=keyframe、低 62 位=pts；4B size），全部大端。
 *
 * ## 为什么要把这些十六进制值钉死
 * 整条音频链路都靠「audio socket 上读到的头 4 字节」选解码路径。常量写错一位不会崩，
 * 只会安静地降级成「无声投屏」——真机上极难定位。
 *
 * 本测试**不碰 MediaCodec / AudioTrack**（那些是 Android 平台类，JVM 单测里拿不到真实现），
 * 只覆盖协议层：常量、握手顺序、流头解析、音频包解析、降级语义。
 */
class AudioProtocolTest {

    // ------------------------------------------------------------------
    // 1. 协议常量
    // ------------------------------------------------------------------

    /**
     * 按服务端注释的定义**独立**算出「名字的 4 字节 ASCII 大端 u32」，
     * 不复用被测常量，所以能真正抓住常量里的笔误。
     */
    private fun asciiId(name: String): Int {
        require(name.length == 4) { "codec 名必须是 4 个字符（含前导 NUL）：$name" }
        var value = 0
        for (ch in name) {
            value = (value shl 8) or (ch.code and 0xFF)
        }
        return value
    }

    @Test
    fun `codec id 既等于上游字面量也等于名字的 4 字节 ASCII`() {
        // 上游 AudioCodec.java 里的原值
        assertEquals(0x6f707573, ScrcpyConfig.AUDIO_CODEC_ID_OPUS)
        assertEquals(0x00616163, ScrcpyConfig.AUDIO_CODEC_ID_AAC)
        assertEquals(0x666c6163, ScrcpyConfig.AUDIO_CODEC_ID_FLAC)
        assertEquals(0x00726177, ScrcpyConfig.AUDIO_CODEC_ID_RAW)

        // 再由名字独立推导一遍（AAC / RAW 的名字含前导 NUL，故长度仍是 4）
        assertEquals(asciiId("opus"), ScrcpyConfig.AUDIO_CODEC_ID_OPUS)
        assertEquals(asciiId("\u0000aac"), ScrcpyConfig.AUDIO_CODEC_ID_AAC)
        assertEquals(asciiId("flac"), ScrcpyConfig.AUDIO_CODEC_ID_FLAC)
        assertEquals(asciiId("\u0000raw"), ScrcpyConfig.AUDIO_CODEC_ID_RAW)
    }

    @Test
    fun `默认编码是 raw 而不是 scrcpy 自己的 opus`() {
        // Opus 的**解码**要控制端 API 29+，而本工程 minSdk = 26。
        // 一旦这里被改回 opus，Android 8/9 控制端会「开了音频却完全没声音」。
        assertEquals(ScrcpyConfig.AUDIO_CODEC_RAW, ScrcpyConfig().audioCodec)
    }

    @Test
    fun `高字节为 0 的 codec 不能被误判成禁用码`() {
        // AAC / RAW 的最高字节本来就是 0x00。若按「首字节为 0 即已禁用」实现，
        // 这两个最常用的编码会被整条跳过。禁用码必须比**整个 4 字节**。
        assertEquals(0, ScrcpyConfig.AUDIO_CODEC_ID_AAC ushr 24)
        assertEquals(0, ScrcpyConfig.AUDIO_CODEC_ID_RAW ushr 24)

        assertTrue(ScrcpyConfig.AUDIO_CODEC_ID_AAC in ScrcpyConfig.AUDIO_CODEC_IDS)
        assertTrue(ScrcpyConfig.AUDIO_CODEC_ID_RAW in ScrcpyConfig.AUDIO_CODEC_IDS)
    }

    @Test
    fun `两个禁用码都不在已知 codec 集合里`() {
        assertEquals(4, ScrcpyConfig.AUDIO_CODEC_IDS.size)
        assertFalse(0 in ScrcpyConfig.AUDIO_CODEC_IDS)   // 设备采集不到音频
        assertFalse(1 in ScrcpyConfig.AUDIO_CODEC_IDS)   // 设备端音频配置错误
    }

    // ------------------------------------------------------------------
    // 2. 真实 socket 上的握手与音频读取
    // ------------------------------------------------------------------

    /** 一次伪造服务端握手的产物。 */
    private class Outcome(val connected: Boolean, val events: List<ScrcpyController.Event>) {
        fun <T : ScrcpyController.Event> first(type: Class<T>): T =
            events.filterIsInstance(type).firstOrNull()
                ?: error(
                    "没有收到 ${type.simpleName} 事件，实收：" +
                        events.map { it.javaClass.simpleName }
                )
    }

    /**
     * 起一个只实现「accept 顺序 + 流头 + 音频包」的假服务端，跑完一次真实握手。
     *
     * ⚠️ 事件收集器**必须跑在别的线程**（这里是 Dispatchers.IO）：`connect()` 是阻塞式握手，
     * 会把 runBlocking 那唯一的事件循环线程占住，收集器若在同一线程就永远拿不到事件。
     */
    private fun handshake(
        audioEnabled: Boolean,
        audioHeader: ByteArray = RAW_HEADER,
        audioPackets: List<Pair<Boolean, ByteArray>> = emptyList()
    ): Outcome = runBlocking {
        val events = CopyOnWriteArrayList<ScrcpyController.Event>()
        ServerSocket(0).use { server ->
            val port = server.localPort
            val controller = ScrcpyController(
                ScrcpyConfig(audioEnabled = audioEnabled, controlEnabled = true, localPort = port)
            )

            val collector = launch(Dispatchers.IO) { controller.frames().collect { events.add(it) } }
            // SharedFlow 是 replay=0 的：订阅者未就位时事件会被直接丢弃，故先让收集器起来
            delay(150)

            val serverJob = launch(Dispatchers.IO) {
                serveOnce(server, audioEnabled, audioHeader, audioPackets)
            }

            val connected = controller.connect(port = port)

            if (audioEnabled) {
                withTimeoutOrNull(AWAIT_TIMEOUT_MS) {
                    while (!settled(events, audioPackets.size)) delay(20)
                }
            } else {
                withTimeoutOrNull(AWAIT_TIMEOUT_MS) {
                    while (events.none { it is ScrcpyController.Event.Ready }) delay(20)
                }
            }
            // 再静默一小段，给「不该出现的事件」留出暴露的机会
            delay(QUIET_MS)

            runCatching { controller.stop() }
            serverJob.cancel()
            collector.cancel()

            Outcome(connected, ArrayList(events))
        }
    }

    /**
     * 假服务端：**严格照 `DesktopConnection.open()` 的顺序** accept，
     * video（写 1B dummy）→ audio（不写 dummy）→ control（不写 dummy）。
     *
     * 实现若把 audio 与 control 的顺序搞反，两边会同时卡在 accept/read 上，
     * 测试以超时失败 —— 这正是真机上「控制包静默失效」的等价症状。
     */
    private suspend fun serveOnce(
        server: ServerSocket,
        audioEnabled: Boolean,
        audioHeader: ByteArray,
        audioPackets: List<Pair<Boolean, ByteArray>>
    ) {
        val video: Socket = server.accept()
        // dummy byte 只写给第一条被 accept 的 socket
        video.getOutputStream().apply { write(0); flush() }

        val audio: Socket? = if (audioEnabled) server.accept() else null
        val control: Socket = server.accept()

        // 64B device meta（NUL 填充，服务端写在 getFirstSocket() 即 video 上）
        // + 12B 视频流头：codecId=1 / width=1920 / height=1080
        video.getOutputStream().apply {
            write(ByteArray(64))
            write(VIDEO_STREAM_META)
            flush()
        }

        if (audio != null) {
            audio.getOutputStream().apply {
                // 音频流头**先**单独写出（Streamer.writeAudioHeader）
                write(audioHeader)
                for ((config, payload) in audioPackets) {
                    write(packetHeader(pts = 1_000L, size = payload.size, config = config))
                    write(payload)
                }
                flush()
            }
        }

        // 保持连接，让客户端把事件读出来；随后由测试显式 cancel
        delay(SERVE_HOLD_MS)
        listOfNotNull(video, audio, control).forEach { runCatching { it.close() } }
    }

    /** 等「音频结论事件」到齐，且音频包数量达到预期。 */
    private fun settled(events: List<ScrcpyController.Event>, expectedFrames: Int): Boolean {
        val hasVerdict = events.any {
            it is ScrcpyController.Event.AudioReady || it is ScrcpyController.Event.AudioDisabled
        }
        val frames = events.count { it is ScrcpyController.Event.AudioFrame }
        return hasVerdict && frames >= expectedFrames
    }

    /** 独立构造 12B 音频包包头：8B 大端 ptsAndFlags（bit63=config）+ 4B 大端 size。 */
    private fun packetHeader(pts: Long, size: Int, config: Boolean): ByteArray {
        val header = ByteArray(12)
        val ptsAndFlags: Long = if (config) (1L shl 63) else pts
        for (i in 0 until 8) {
            header[i] = ((ptsAndFlags ushr ((7 - i) * 8)) and 0xFF).toByte()
        }
        for (i in 0 until 4) {
            header[8 + i] = ((size ushr ((3 - i) * 8)) and 0xFF).toByte()
        }
        return header
    }

    @Test
    fun `握手按 video-audio-control 顺序完成并读出视频流头`() {
        val outcome = handshake(audioEnabled = true)

        assertTrue("握手应当成功（超时通常意味着 accept 顺序被改动）", outcome.connected)

        val ready = outcome.first(ScrcpyController.Event.Ready::class.java)
        assertEquals(1920, ready.w)
        assertEquals(1080, ready.h)
        assertEquals(1, ready.codecId)
    }

    @Test
    fun `audio socket 上没有 dummy byte，4 字节流头被原样读出`() {
        val outcome = handshake(audioEnabled = true)

        // 若实现错误地在 audio socket 上先读了一个 dummy byte，codecId 会整体左移一字节
        // 变成 0x72617700，这条断言立刻失败。
        val audioReady = outcome.first(ScrcpyController.Event.AudioReady::class.java)
        assertEquals(ScrcpyConfig.AUDIO_CODEC_ID_RAW, audioReady.codecId)
    }

    @Test
    fun `音频包按 12B 包头解析出 pts 与 config`() {
        val body = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val csd = byteArrayOf(0x4F, 0x70, 0x75, 0x73) // "Opus"

        val outcome = handshake(
            audioEnabled = true,
            audioPackets = listOf(
                true to csd,      // 编码器配置包（bit63）
                false to body     // 普通包
            )
        )

        val frames = outcome.events.filterIsInstance<ScrcpyController.Event.AudioFrame>()
        assertEquals("配置包 + 普通包应各投递一次", 2, frames.size)

        val configFrame = frames[0]
        assertTrue("bit63 应被识别为 config", configFrame.config)
        assertArrayEquals(csd, configFrame.data)

        val mediaFrame = frames[1]
        assertFalse(mediaFrame.config)
        assertEquals(1_000L, mediaFrame.pts)
        assertArrayEquals(body, mediaFrame.data)
    }

    @Test
    fun `禁用码 0 降级为 AudioDisabled 而不是投屏失败`() {
        // 被控端 Android 11 以下（或采集失败）时服务端写的就是 4 个 0 字节
        val outcome = handshake(audioEnabled = true, audioHeader = ByteArray(4))

        assertTrue("禁用音频不能导致握手失败", outcome.connected)
        assertTrue(
            "必须给出带原因的 AudioDisabled",
            outcome.first(ScrcpyController.Event.AudioDisabled::class.java).reason.isNotBlank()
        )
        assertFalse(
            "禁用音频绝不能升级成 Error —— 上层会因此把整场投屏判为失败",
            outcome.events.any { it is ScrcpyController.Event.Error }
        )
        assertFalse(
            "已降级为 AudioDisabled 就不该再发 AudioReady",
            outcome.events.any { it is ScrcpyController.Event.AudioReady }
        )
    }

    @Test
    fun `禁用码 1 只关音频不杀投屏`() {
        val outcome = handshake(
            audioEnabled = true,
            audioHeader = byteArrayOf(0, 0, 0, 1)
        )

        assertTrue("音频配置错误不应中断视频会话", outcome.connected)
        assertTrue(outcome.events.any { it is ScrcpyController.Event.AudioDisabled })
        assertFalse(outcome.events.any { it is ScrcpyController.Event.Error })
    }

    @Test
    fun `未开启音频时不建立 audio socket`() {
        // 假服务端只会 accept 两条（video + control）。若实现多连一条，
        // 客户端会先拿到连接、随后在 control 的 accept 上永久阻塞 → 握手超时。
        val outcome = handshake(audioEnabled = false)

        assertTrue(outcome.connected)
        assertTrue(
            "不该出现任何音频事件",
            outcome.events.none {
                it is ScrcpyController.Event.AudioReady ||
                    it is ScrcpyController.Event.AudioDisabled ||
                    it is ScrcpyController.Event.AudioFrame
            }
        )
    }

    private companion object {
        /**
         * RAW 流头 = `AudioCodec.RAW.getId()` 的 4 字节大端表示（0x00726177）。
         * 这里写成字节序列而不是复用常量，是为了让「读到的前 4 字节」在测试里可见。
         */
        val RAW_HEADER: ByteArray = byteArrayOf(0x00, 0x72, 0x61, 0x77)

        val VIDEO_STREAM_META: ByteArray = byteArrayOf(
            0, 0, 0, 1,                            // codecId
            0, 0, 0x07, 0x80.toByte(),             // width  = 1920
            0, 0, 0x04, 0x38                       // height = 1080
        )

        const val AWAIT_TIMEOUT_MS: Long = 4_000L
        const val QUIET_MS: Long = 250L
        const val SERVE_HOLD_MS: Long = 5_000L
    }
}
