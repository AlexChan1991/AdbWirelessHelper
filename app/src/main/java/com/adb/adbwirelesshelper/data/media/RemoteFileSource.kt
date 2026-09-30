package com.adb.adbwirelesshelper.data.media

import android.media.MediaDataSource
import com.adb.adbwirelesshelper.util.Logx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.Closeable

/**
 * 远端文件的**随机读**源（规格 §4.6 流式播放地基）。
 *
 * 存在的理由：视频 / 音乐要能 seek，而
 * - `adb exec-out cat` 只能顺序读，拖一下进度条就得从头重读；
 * - 每次 `readAt` 起一个 adb 进程更不行 —— MediaPlayer 单次只要 32–64 KB，
 *   500 MB 视频要起几千个进程，吞吐会掉到几百 KB/s，**彻底不可用**。
 *
 * 所以这里的模型是「**大块拉、小块给**」：以 2 MB 为单位向被控端取数据，
 * 缓存在内存里，MediaPlayer 的零散 `readAt` 全部由缓存满足。
 * 一次 adb 命令摊到 2 MB 上，进程开销可以忽略。
 *
 * UI 层只认 [RemoteFileStream]，不接触 adb。
 */
interface RemoteFileStream : Closeable {

    /**
     * 文件总字节数；未知时为 -1。
     *
     * 与 `MediaDataSource.getSize()` 同语义 —— 但本实现的 [RemoteFileSource]
     * 必须拿到一个确定的正数，否则无法分块。
     */
    val sizeBytes: Long

    /**
     * 阻塞式随机读。
     *
     * @param offset       文件内绝对偏移
     * @param buffer       目标缓冲
     * @param bufferOffset 写入 buffer 的起始下标
     * @param size         期望读取的字节数
     * @return 实际读到的字节数；EOF 或出错返回 -1
     */
    fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, size: Int): Int

    /** 后台预取指定偏移所在的块（不阻塞）。播放器 seek 后调用，摊掉 adb 进程开销。 */
    fun prefetch(fromOffset: Long)

    /** 已缓存区间，供进度条画缓冲条。 */
    fun cachedRanges(): List<LongRange>

    /** 最后一次失败原因；非 null 表示源已不可用。 */
    val failure: String?
}

/**
 * [RemoteFileStream] 的默认实现：块缓存 + LRU + 顺序预取。
 *
 * @param path           远端绝对路径（**只用于日志**，读操作全走 [readRange]）
 * @param sizeBytes      文件总字节数，必须 > 0。运行期可能被 [RemoteFileSource.readOnce]
 *                       向下校准（末块短读时把「偏大的 size」修正为真实 EOF，见 [fetchOnce]）
 * @param readRange      远端随机读闭包；返回空 ByteArray 表示 EOF
 * @param blockBytes     单块大小，默认 2 MB
 * @param maxCachedBlocks LRU 上限，默认 6 块（峰值 ~12 MB）
 */
class RemoteFileSource(
    private val path: String,
    sizeBytes: Long,
    private val readRange: suspend (offset: Long, length: Int) -> Result<ByteArray>,
    blockBytes: Int = DEFAULT_BLOCK_BYTES,
    maxCachedBlocks: Int = DEFAULT_MAX_CACHED_BLOCKS,
) : RemoteFileStream {

    /**
     * 文件总字节数的**可变**后备字段。
     *
     * ⚠️ 必须是 `@Volatile`：末块短读时 [fetchOnce] 会把它向下校准（自愈），
     * 而读它的是 MediaPlayer 的读线程（[readAt]），不是写它的那个协程线程 ——
     * 不加 volatile，那条线程可能一直读到旧值，于是「校准」等于白做，
     * 表现就是文件末尾永远读不到。
     */
    @Volatile
    private var sizeValue: Long = sizeBytes

    /**
     * 对外暴露的 [RemoteFileStream.sizeBytes]：代理到可自愈的 [sizeValue]。
     *
     * ⚠️ 这里**必须**是 `override val`，不能写成 `private val`：
     * 接口 [RemoteFileStream] 已经声明了 `val sizeBytes`，实现类要用 `private`
     * 去覆盖它会直接编译失败（`Modifier 'private' is not applicable to 'override' member`）。
     * 早期设计契约里把它写成了 `private val`，那是笔误，**不要**照着契约"修正"回来。
     */
    override val sizeBytes: Long
        get() = sizeValue

    private val blockSize: Int = blockBytes.coerceAtLeast(MIN_BLOCK_BYTES)
    private val blockSizeLong: Long = blockSize.toLong()
    private val maxBlocks: Int = maxCachedBlocks.coerceAtLeast(1)

    /** 预取协程的宿主；[close] 时整体取消 */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 保护 [cache] 与 [inflight]；[readAt] 来自 MediaPlayer 线程，预取来自 [scope] */
    private val lock = Any()

    /** 块索引 → 块内容。LinkedHashMap 插入序 = LRU 序（命中时 remove 后重新 put 挪到末尾） */
    private val cache = LinkedHashMap<Long, ByteArray>()

    /** 块索引 → 正在进行的取块请求。用于**去重**：预取和主读撞到同一块时不重复发命令 */
    private val inflight = HashMap<Long, CompletableDeferred<ByteArray?>>()

    @Volatile
    private var failedReason: String? = null

    override val failure: String?
        get() = failedReason

    init {
        // ★ 预热首尾两块。
        //
        // 很多 MP4 的 `moov` 原子在文件**尾部**，MediaPlayer 开局会先跳到末尾读元数据
        // 再跳回头部开始播 —— 如果这两块都是冷启动，用户会先卡两三次 adb 往返。
        // 构造时就把它们塞进缓存，这个抖动就吃掉了。
        requestPrefetch(0L)
        if (sizeBytes > 0L) {
            val lastBlockStart: Long = ((sizeBytes - 1L) / blockSizeLong) * blockSizeLong
            if (lastBlockStart > 0L) requestPrefetch(lastBlockStart)
        }
    }

    // ---------------------------------------------------------------- 读

    /**
     * 阻塞读。
     *
     * ⚠️ 这里是**故意阻塞**的：调用方是 MediaPlayer 自己的读取线程，允许阻塞。
     * 桥接挂起的 [readRange] 用 `runBlocking` 且**不指定 dispatcher** ——
     * 指定 `Dispatchers.IO` 会占住一个 IO 线程去等另一个 IO 线程，并发读一多
     * （主读 + 预取 + seek）理论上能把 IO 池（默认 64）堵死；不指定时
     * `runBlocking` 在当前线程起一个单线程事件循环，实际 IO 由 [readRange]
     * 内部的 `withContext` 切走，不额外占用池线程。
     */
    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, size: Int): Int {
        if (size <= 0) return 0
        val reason: String? = failedReason
        if (reason != null) {
            Logx.w(TAG, "readAt 放弃：源已不可用（$reason）")
            return -1
        }
        if (sizeBytes <= 0L) {
            Logx.w(TAG, "readAt 放弃：文件大小未知（sizeBytes=$sizeBytes）")
            return -1
        }
        if (offset >= sizeBytes) return -1 // EOF
        if (offset < 0L || bufferOffset < 0 || bufferOffset >= buffer.size) return -1

        // 三个上限取最小：调用方要的、文件剩下的、buffer 装得下的。
        // 全程用 Long 比较再转 Int —— 文件可能 > 2 GB，先转 Int 会溢出成负数
        val want: Int = minOf(
            size.toLong(),
            sizeBytes - offset,
            (buffer.size - bufferOffset).toLong(),
        ).toInt()
        if (want <= 0) return -1
        var done = 0
        while (done < want) {
            val pos: Long = offset + done
            val block: ByteArray = blockFor(pos) ?: return if (done > 0) done else -1
            val inBlock: Int = (pos % blockSizeLong).toInt()
            val available: Int = block.size - inBlock
            if (available <= 0) return if (done > 0) done else -1
            val n: Int = minOf(want - done, available)
            System.arraycopy(block, inBlock, buffer, bufferOffset + done, n)
            done += n
        }

        // 顺序播放：当前块被读到时，把它**后面那块**提前拉起来。
        // requestPrefetch 内部会命中缓存 / 在飞去重，重复调用不会重复起协程。
        val nextBlockStart: Long = (offset / blockSizeLong + 1L) * blockSizeLong
        if (nextBlockStart < sizeBytes) requestPrefetch(nextBlockStart)
        return done
    }

    /** 取 [pos] 所在块；命中缓存直接返回，否则阻塞拉取。 */
    private fun blockFor(pos: Long): ByteArray? {
        val index: Long = pos / blockSizeLong
        synchronized(lock) {
            val hit: ByteArray? = cache[index]
            if (hit != null) {
                // LRU：挪到链表末尾
                cache.remove(index)
                cache[index] = hit
                return hit
            }
        }
        return runBlocking { readBlockSuspend(index) }
    }

    /**
     * 拉一块并写入缓存。
     *
     * 三步：**查缓存 + 查在飞 + 登记自己在飞**必须在**同一个**临界区里做完，
     * 然后才挂起等别人的结果 / 自己发命令。
     *
     * ⚠️ 历史缺陷：这三者曾经是三个独立的 `synchronized(lock)` 块。
     *    并发下（主读线程 vs 预取协程）两个调用方可以都看到「缓存没有、也没有在飞」，
     *    于是各自发一条 2 MB 的 adb 命令 —— 去重完全失效，而且两条命令还会互相抢
     *    ShellExecutor 的 Mutex，把本来一次就能拿到的块变成两次往返。
     */
    private suspend fun readBlockSuspend(index: Long): ByteArray? {
        val gate = CompletableDeferred<ByteArray?>()
        val existing: CompletableDeferred<ByteArray?>? = synchronized(lock) {
            val hit: ByteArray? = cache[index]
            if (hit != null) {
                // LRU：挪到链表末尾
                cache.remove(index)
                cache[index] = hit
                return hit
            }
            val current: CompletableDeferred<ByteArray?>? = inflight[index]
            if (current != null) {
                current // 已经有人在取了：挂起等它，不要重复发命令
            } else {
                inflight[index] = gate // ★ 就地登记，退出临界区前别的线程一定看得见
                null
            }
        }

        if (existing != null) {
            return try {
                existing.await()
            } catch (error: CancellationException) {
                // ★ 取消必须原样向上传播。这里在协程内部被调用，[scope] 的取消
                //   （[close] 会 cancel）一旦被吞掉，协程就变成「取消不掉的僵尸」：
                //   它会继续往下走、继续发 adb 命令，close() 之后的清理时序彻底不可控。
                throw error
            } catch (error: Exception) {
                // 发起方被取消（close 时会把 gate 补成 null）。
                // 这里返回 null 让 readAt 报 EOF，不要把源标成失败——源已经关了，不是坏了
                null
            }
        }

        val bytes: ByteArray? = fetchOnce(index)
        if (bytes != null) put(index, bytes)
        synchronized(lock) { inflight.remove(index) }
        gate.complete(bytes)
        return bytes
    }

    /**
     * 真正发一次远端读；失败重试 1 次（同一级命令，规避偶发的 adb 抖动）。
     *
     * ⚠️ **必须校验实际拿到的字节数**，不能「非空即成功」（历史缺陷）：
     * [readRange] 走的是 `adb exec-out`，超时强杀 / 管道被截断 / 远端只吐了半截时，
     * 可能返回一个**短块**却带着成功的结果。短块一旦被写进 [cache]，
     * [readAt] 里 `available = block.size - inBlock` 会算出错误的可用长度，
     * `available <= 0` 时直接返回 -1（EOF）—— 播放中途静默结束，且上层拿不到任何报错。
     * 长度判定全部下沉到 [readOnce]（它才知道本块的期望长度）。
     *
     * @return 取到合法字节返回之；两次都失败返回 null 并置 [failedReason]
     */
    private suspend fun fetchOnce(index: Long): ByteArray? {
        val start: Long = index * blockSizeLong
        if (start >= sizeValue) return null
        val expected: Int = minOf(blockSizeLong, sizeValue - start).toInt()
        val isLastBlock: Boolean = start + blockSizeLong >= sizeValue

        val first: Result<ByteArray> = readOnce(start, expected, isLastBlock)
        if (first.isSuccess) return first.getOrThrow()

        Logx.w(
            TAG,
            "取块失败（path=$path offset=$start），重试一次：${first.exceptionOrNull()?.message}"
        )
        val second: Result<ByteArray> = readOnce(start, expected, isLastBlock)
        if (second.isSuccess) return second.getOrThrow()

        val reason: String = second.exceptionOrNull()?.message
            ?: "未知原因"
        failedReason = "读取远端文件失败：$path（$reason）"
        Logx.e(TAG, "取块最终失败：path=$path offset=$start length=$expected | $reason")
        return null
    }

    /**
     * 发一次 [readRange] 并**按返回长度分叉判定**（分叉的每一条理由都写在下面）。
     *
     * 为什么不能简单判「非空即成功」、也不能简单判「必须等于 [expected]」，都写在注释里了。
     *
     * @param start       本块在文件内的起始偏移
     * @param expected    本块的期望字节数（末块会小于 [blockSize]）
     * @param isLastBlock 本块是否是最后一块
     * @return 通过判定返回字节数组；命令失败或长度不合法返回 Result.failure
     */
    private suspend fun readOnce(
        start: Long,
        expected: Int,
        isLastBlock: Boolean,
    ): Result<ByteArray> {
        val result: Result<ByteArray> = try {
            readRange(start, expected)
        } catch (error: Exception) {
            Result.failure(error)
        }
        val bytes: ByteArray = result.getOrNull()
            ?: return Result.failure(
                result.exceptionOrNull() ?: IllegalStateException("未知原因")
            )

        return when {
            // 1) 长度正好：唯一「毫无疑问成功」的情况
            bytes.size == expected -> Result.success(bytes)

            // 2) 多给了字节：不可能合法。下一条命令只被要求读 [expected] 个，
            //    多出来只可能是两次输出被拼到了一起（adb 复用 / 流未清空），
            //    写进缓存会让后面所有块的偏移整体错位 —— 判失败。
            bytes.size > expected -> Result.failure(
                IllegalStateException(
                    "远端返回字节数超出期望：期望 ${expected}，实际 ${bytes.size}（数据疑似被拼接）"
                )
            )

            // 3) 短块 + 不是最后一块：**判失败**，绝不写入缓存。
            //    中间块少一个字节都只可能是截断（unmerged 的半截数据），绝不是 EOF。
            !isLastBlock -> Result.failure(
                IllegalStateException(
                    "远端返回短块：期望 ${expected}，实际 ${bytes.size}（数据被截断）"
                )
            )

            // 3-bis) ★ 我对规格做的一处**收紧**：末块短读里，「首块（start == 0）一个字节都没读到」
            //    单独判失败。理由：第 4 条的自愈前提是「文件真实长度比 [sizeValue] 短」，
            //    而一个大小 > 0 的文件在第 0 字节就读不到东西，**只可能是命令失败**
            //    （无权限 / 路径失效 / stderr 被 2>/dev/null 吞掉后表现为 0 字节）。
            //    按第 4 条无条件接受的话，单块文件（≤ 2 MB 的音乐）会被静默校准成 0 字节，
            //    上层看到的是「播放器报莫名错误」而不是我们自己的 [failedReason] ——
            //    诊断信息反而丢了。这里判失败后仍会走一次重试，不是一棍子打死。
            bytes.isEmpty() && start == 0L -> Result.failure(
                IllegalStateException(
                    "远端在文件起始处返回 0 字节：期望 ${expected}（文件不可读或路径失效）"
                )
            )

            // 4) 短块 + 是最后一块：**接受**，同时把文件大小向下校准。
            //    ★ 这里是整个修复的关键分叉：
            //    - 无条件要求「必须等于 expected」，则 [sizeOf] 偶发返回一个偏大的大小时
            //      （某些 ROM 的 stat / ls 回退会给错），最后一块**永远**达不到期望长度，
            //      整条流直接不可用；
            //    - 无条件接受短块，就是原来那个「短块被当成文件结尾、播放静默提前结束」的 bug。
            //    所以只在「本块确实应该是最后一块」时，把这一次短读当成一次**真实的 EOF**，
            //    并把 sizeValue 校准成 `start + bytes.size`，让后续所有读（含 [readAt]、
            //    [cachedRanges]）都按真实长度走 —— 顺手把错误的大小自愈回来。
            else -> {
                val realSize: Long = start + bytes.size.toLong()
                if (realSize < sizeValue) {
                    Logx.w(
                        TAG,
                        "末块短读，把文件大小从 ${sizeValue} 校准为 ${realSize}（path=$path）"
                    )
                    sizeValue = realSize
                }
                Result.success(bytes)
            }
        }
    }

    private fun put(index: Long, bytes: ByteArray) {
        synchronized(lock) {
            cache[index] = bytes
            while (cache.size > maxBlocks) {
                val oldest: Long = cache.keys.firstOrNull() ?: break
                cache.remove(oldest)
            }
        }
    }

    // ---------------------------------------------------------------- 预取

    override fun prefetch(fromOffset: Long) {
        requestPrefetch(fromOffset)
    }

    /** [prefetch] 的实现。抽成 private 是为了能在 `init` 里安全调用（override 成员是 open 的）。 */
    private fun requestPrefetch(fromOffset: Long) {
        if (failedReason != null) return
        if (sizeBytes <= 0L) return
        if (fromOffset < 0L || fromOffset >= sizeBytes) return
        val index: Long = fromOffset / blockSizeLong
        synchronized(lock) {
            if (cache.containsKey(index) || inflight.containsKey(index)) return
        }
        scope.launch {
            runCatching { readBlockSuspend(index) }
                .onFailure { error -> Logx.w(TAG, "预取失败 path=$path block=$index：${error.message}") }
        }
    }

    // ---------------------------------------------------------------- 状态 / 关闭

    override fun cachedRanges(): List<LongRange> {
        val indices: List<Long> = synchronized(lock) { cache.keys.sorted() }
        if (indices.isEmpty()) return emptyList()
        val out = ArrayList<LongRange>(indices.size)
        var segmentStart: Long = indices.first()
        var segmentEnd: Long = indices.first()
        for (i in 1 until indices.size) {
            val idx: Long = indices[i]
            if (idx == segmentEnd + 1L) {
                segmentEnd = idx
            } else {
                rangeOf(segmentStart, segmentEnd)?.let { out.add(it) }
                segmentStart = idx
                segmentEnd = idx
            }
        }
        rangeOf(segmentStart, segmentEnd)?.let { out.add(it) }
        return out
    }

    /** 把连续块索引区间换算成字节区间；块内被远端截断的部分不谎报 */
    private fun rangeOf(fromIdx: Long, toIdx: Long): LongRange? {
        val start: Long = fromIdx * blockSizeLong
        val end: Long = minOf(sizeBytes, (toIdx + 1L) * blockSizeLong)
        return if (end > start) start until end else null
    }

    override fun close() {
        synchronized(lock) {
            cache.clear()
            // 把在飞的请求补成 null，避免等待方永久挂起（见 readBlockSuspend 的 catch）
            for (gate in inflight.values) {
                if (!gate.isCompleted) gate.complete(null)
            }
            inflight.clear()
        }
        scope.cancel()
        Logx.d(TAG, "流已关闭：path=$path")
    }

    companion object {
        private const val TAG = "RemoteFileSource"

        /** 默认块大小 2 MB：一次 adb 命令摊到 2 MB 上，进程开销可忽略 */
        const val DEFAULT_BLOCK_BYTES: Int = 2 * 1024 * 1024

        /** 默认 LRU 上限 6 块 ≈ 12 MB；再加预取中的 1 块，峰值约 14 MB */
        const val DEFAULT_MAX_CACHED_BLOCKS: Int = 6

        /** 块大小下限：再小就退化成「每次读都发命令」，失去分块的意义 */
        const val MIN_BLOCK_BYTES: Int = 64 * 1024
    }
}

/**
 * 把 [RemoteFileStream] 适配成 [MediaDataSource]，直接喂给
 * `MediaPlayer.setDataSource(...)`（API 23+，本工程 minSdk 26，无需降级分支）。
 *
 * 这样视频 / 音乐**不需要落盘**：播放器要哪段就回调 [RemoteFileStream.readAt] 拉哪段。
 */
class RemoteFileMediaDataSource(
    private val stream: RemoteFileStream,
) : MediaDataSource() {

    override fun getSize(): Long = stream.sizeBytes

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        stream.readAt(position, buffer, offset, size)

    override fun close() {
        stream.close()
    }
}
