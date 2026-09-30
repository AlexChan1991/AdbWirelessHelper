package com.adb.adbwirelesshelper.data.file

import com.adb.adbwirelesshelper.data.adb.AdbClient
import com.adb.adbwirelesshelper.data.adb.ShellExecutor
import com.adb.adbwirelesshelper.data.media.RemoteFileSource
import com.adb.adbwirelesshelper.data.media.RemoteFileStream
import com.adb.adbwirelesshelper.domain.model.FileEntry
import com.adb.adbwirelesshelper.domain.model.StorageInfo
import com.adb.adbwirelesshelper.domain.model.ShellResult
import com.adb.adbwirelesshelper.domain.model.fileKindOf
import com.adb.adbwirelesshelper.util.Logx
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 被控端文件系统的仓储（规格 §4.6）。
 *
 * 全部操作都是在**被控端设备上**执行 shell 命令，管理的是被控端的文件，不是控制端本机。
 *
 * 三条必须守住的规则：
 * 1. **路径一律单引号包裹并转义**（[q]）—— 文件名含空格 / 引号 / `;` 等字符是常态，
 *    不转义轻则命令失败，重则被当成多条命令执行；
 * 2. **删除走「已确认」通道**：[ShellExecutor] 把 `rm` 列为高危命令，
 *    未经确认会被拦截。文件管理页的删除会先弹确认框，用户确认后才调 [delete]，
 *    所以这里传 `confirmed = true`，语义是「UI 已确认」而非绕过；
 * 3. **命令串行**：[ShellExecutor] 内部有 Mutex，同一时刻只有一条命令在跑。
 *    列目录、删除等操作不会互相穿插。
 */
class FileRepository(
    private val adb: AdbClient,
    private val shell: ShellExecutor,
) {

    /** `tail`/`head` 可用性按设备缓存（见 [probeReadTool]），避免每读一块探一次 */
    private val readToolSupported: ConcurrentHashMap<String, Boolean> = ConcurrentHashMap()

    /**
     * 列出目录内容。
     *
     * 用 `ls -la` 一次拿到全部信息（比逐条 `stat` 快一个数量级）。
     * 返回**包含隐藏文件**的完整列表，是否显示由 UI 的「显示隐藏文件」开关决定
     * —— 这样切换开关不必重新发命令。
     *
     * @return 成功时为该目录的条目列表（已按名称升序，目录在前由 UI 层再排）
     */
    suspend fun listDir(serial: String, path: String): Result<List<FileEntry>> {
        val result: ShellResult = shell.runOnce(
            serial = serial,
            cmd = "ls -la ${q(path)}",
            timeoutMs = LIST_TIMEOUT_MS,
        )
        val text: String = result.stdout
        if (text.isBlank()) {
            val reason: String = result.stderr.ifBlank { "目录为空或无法读取（退出码 ${result.exitCode}）" }
            Logx.w(TAG, "列目录失败：$path | $reason")
            return Result.failure(IllegalStateException(reason))
        }
        val entries: List<FileEntry> = parseLs(text, path)
        Logx.d(TAG, "列目录 $path -> ${entries.size} 项")
        return Result.success(entries)
    }

    /** 新建目录（`mkdir -p`：父目录不存在时一并创建，已存在时不报错） */
    suspend fun mkdir(serial: String, path: String): Result<Unit> {
        val result: ShellResult = shell.runOnce(
            serial = serial,
            cmd = "mkdir -p ${q(path)}",
            timeoutMs = CMD_TIMEOUT_MS,
        )
        return if (result.exitCode == 0) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(result.stderr.ifBlank { "新建目录失败" }))
        }
    }

    /**
     * 删除条目（危险操作）。
     *
     * ⚠️ 调用方**必须**已经弹过确认框并得到用户确认 —— 这里的 `confirmed = true`
     * 表达的是「UI 已确认」，不是绕过安全机制。
     *
     * @param paths 待删除的绝对路径，可以是文件或目录
     */
    suspend fun delete(serial: String, paths: List<String>): Result<Unit> {
        if (paths.isEmpty()) return Result.success(Unit)
        val joined: String = paths.joinToString(" ") { p -> q(p) }
        val result: ShellResult = shell.runOnce(
            serial = serial,
            cmd = "rm -rf $joined",
            timeoutMs = DELETE_TIMEOUT_MS,
            confirmed = true,
        )
        return if (result.exitCode == 0) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(result.stderr.ifBlank { "删除失败" }))
        }
    }

    /** 重命名 / 移动（`mv`）。跨存储分区可能失败，由调用方向用户提示。 */
    suspend fun move(serial: String, from: String, to: String): Result<Unit> {
        val result: ShellResult = shell.runOnce(
            serial = serial,
            cmd = "mv ${q(from)} ${q(to)}",
            timeoutMs = MOVE_TIMEOUT_MS,
        )
        return if (result.exitCode == 0) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(result.stderr.ifBlank { "移动失败（跨存储分区可能不被支持）" })
            )
        }
    }

    /** 复制文件 / 目录树（`cp -r`） */
    suspend fun copy(serial: String, from: String, to: String): Result<Unit> {
        val result: ShellResult = shell.runOnce(
            serial = serial,
            cmd = "cp -r ${q(from)} ${q(to)}",
            timeoutMs = MOVE_TIMEOUT_MS,
        )
        return if (result.exitCode == 0) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(result.stderr.ifBlank { "复制失败" }))
        }
    }

    /**
     * 读取文件内容为字节（供图片预览）。
     *
     * 走 `adb exec-out cat <path>`，**不落盘、不用 pull**，直接拿原始字节。
     * 图片通常几 MB，一次性读入内存可接受；视频太大，走 [pull] 落盘后再播。
     *
     * ⚠️ 命令必须带 `2>/dev/null`：`adb exec-out` 把子进程的 stderr 并到**同一个 socket**
     *    上（adbd 不区分 stdout / stderr），`cat` 打不开文件（无权限 / 路径不存在 / 是目录）时，
     *    错误文本会被当成文件字节原样返回 —— 真机实测：`adb exec-out "tail -c +1 '/system/build.prop' | head -c 64"`
     *    返回的是 43 字节的 `tail: /system/build.prop: Permission denied`，而不是文件字节。
     *    丢掉 stderr 之后，这类情况表现为「0 字节 + 退出码非 0」，
     *    由 [AdbClient.execOut] 的退出码判定变成明确的失败，上层才拿得到诊断信息。
     */
    suspend fun readBytes(serial: String, path: String): Result<ByteArray> =
        adb.execOut(serial, "cat ${q(path)} 2>/dev/null", READ_TIMEOUT_MS)

    /**
     * 把远端文件拉到控制端本地。
     *
     * ⚠️ 视频 / 音乐**已经不再走它**了 —— 流式播放改走 [openStream] +
     * `MediaPlayer.setDataSource(MediaDataSource)`，不落盘（用户明确要求不缓存到本地）。
     * 保留仅为将来的「导出到本机」功能，以及超大文件（> 64 MB 且被控端缺 tail/head/dd）
     * 实在流不动时的最后退路。
     */
    suspend fun pull(serial: String, remotePath: String, localFile: File): Result<String> =
        adb.pull(serial, remotePath, localFile)

    // ---------------------------------------------------------------- 流式读（规格 §4.6）

    /**
     * 探测被控端是否支持「按偏移读一段」。
     *
     * 判据刻意**不用** `command -v tail`：有些 ROM 的 toybox 有 `tail` 却不支持
     * POSIX 的 `-c +N`，也有把 `head` 裁掉的。唯一可靠的方式是真的按我们将要用的
     * 语法跑一次看退出码。
     *
     * 结果按 serial 缓存在本对象里，**同一台设备只探一次** ——
     * 否则每读一块都要多一条探测命令，等于把分块带来的收益还回去。
     *
     * @return 支持返回 true；设备不可达等硬错误返回 Result.failure
     */
    suspend fun probeReadTool(serial: String): Result<Boolean> {
        readToolSupported[serial]?.let { cached -> return Result.success(cached) }

        // ★ 刻意**不用管道**探测：`tail … | head …` 的退出码是 `head` 的（0），
        //   tail 根本不存在时也会被判成「支持」，于是所有读都走一条注定失败的命令。
        //   所以两个工具各自单独跑一次、各自看自己的退出码。
        //
        //   用 /dev/null 做样本：它一定存在，且读到 0 字节也不算错，
        //   这样测出来的纯粹是「工具在不在、支不支持我们要的那两个参数」。
        val cmd: String = "tail -c +1 ${q("/dev/null")} >/dev/null 2>&1; echo tail=\$?; " +
            "head -c 1 ${q("/dev/null")} >/dev/null 2>&1; echo head=\$?"
        val result: ShellResult = shell.runOnce(
            serial = serial,
            cmd = cmd,
            timeoutMs = CMD_TIMEOUT_MS,
        )
        val ok: Boolean = result.stdout.contains(PROBE_TAIL_OK) && result.stdout.contains(PROBE_HEAD_OK)
        readToolSupported[serial] = ok
        Logx.d(TAG, "读工具探测 $serial -> $ok（${result.stdout.trim()}）")
        return Result.success(ok)
    }

    /**
     * 取远端文件大小（字节）。
     *
     * 首选 `stat -c %s`；拿不到时回退解析 `ls -l` 的输出
     * —— 部分定制 ROM 的 toybox 裁掉了 `stat`，但 `ls` 一定在。
     *
     * ⚠️ 回退解析**必须复用 [sizeFromLsLine]**（与 [parseLs] 同一套取法）：
     * 这里曾经按下标硬取第 5 列（`getOrNull(4)`），而 `parseLs` 用的是「日期前一列」，
     * 于是同一台设备上会出现「列目录显示 1 MB、流式读却按另一个 size 分块」的两套策略打架。
     */
    suspend fun sizeOf(serial: String, path: String): Result<Long> {
        val stat: ShellResult = shell.runOnce(
            serial = serial,
            cmd = "stat -c %s ${q(path)}",
            timeoutMs = CMD_TIMEOUT_MS,
        )
        val direct: Long? = stat.stdout.trim().toLongOrNull()
        if (stat.exitCode == 0 && direct != null && direct >= 0L) {
            return Result.success(direct)
        }

        val ls: ShellResult = shell.runOnce(
            serial = serial,
            cmd = "ls -l ${q(path)}",
            timeoutMs = CMD_TIMEOUT_MS,
        )
        // ls -l 形如：-rw-rw---- 1 u0_a123 sdcard_rw 1048576 2026-09-20 10:30 foo.mp4
        // ⚠️ 不按下标取 size：列数随 ROM 而变（有的省掉 group 列），
        //    取法与 parseLs 完全一致（见 [sizeFromLsLine]）。
        val fromLs: Long? = ls.stdout.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.let { line -> if (line.startsWith("ls:", ignoreCase = true)) null else sizeFromLsLine(line) }
        if (fromLs != null && fromLs >= 0L) {
            return Result.success(fromLs)
        }
        val reason: String = stat.stderr.ifBlank { ls.stderr }.ifBlank { "无法获取文件大小" }
        return Result.failure(IllegalStateException("$reason：$path"))
    }

    /**
     * 远端**随机读**一段：从 [offset] 起读 [length] 字节。
     *
     * ⚠️ **空 ByteArray 是 EOF，不是失败**：`tail -c +N` 越过文件尾时输出 0 字节
     * 且退出码为 0。若在这里判成失败，视频播到结尾会误报「播放错误」。
     *
     * 三级 fallback，一级比一级弱：
     * 1. `tail -c +<offset+1> | head -c <length>` —— 首选；
     * 2. `dd bs=8192 skip=… count=…` —— ⚠️ 必须走 [AdbClient.execOut] 而不是
     *    [ShellExecutor.runOnce]：后者把 `dd` 列在 `DANGEROUS` 里，走它会被
     *    拦截并回一句「危险命令已被拦截」，读出来的是警告文本而不是文件字节；
     * 3. 整文件 `exec-out cat` 读进内存 —— **仅当 ≤ 64 MB**，更大宁可失败也不要 OOM。
     *
     * 每级失败即换下一级（这就是「重试 1 次」的落点：换一条不同的命令，
     * 而不是把同一条注定失败的命令再发一遍）。
     *
     * ⚠️ 所有「取原始字节」的命令都必须 **`2>/dev/null`**：
     * `adb exec-out` 下 adbd 把子进程的 stderr 并进同一条字节流，
     * 命令打不开文件时那句 `Permission denied` 会被当成文件字节返回（真机实测 43 字节），
     * 上层拿到的是错位的数据而不是错误。管道里**每个阶段都要单独加** ——
     * 写在 `| head …` 后面的重定向作用不到前一个 `tail`。
     * （例外：[probeReadTool] 这种要看输出 / 退出码的探测命令**不能加**，否则探测语义被破坏。）
     */
    suspend fun readRange(
        serial: String,
        path: String,
        offset: Long,
        length: Int,
    ): Result<ByteArray> {
        if (length <= 0 || offset < 0L) return Result.success(ByteArray(0))

        // 一级：tail + head（两个阶段的 stderr 各丢各的，见本函数 KDoc）
        if (probeReadTool(serial).getOrDefault(false)) {
            val cmd: String =
                "tail -c +${offset + 1} ${q(path)} 2>/dev/null | head -c $length 2>/dev/null"
            val out: Result<ByteArray> = adb.execOut(serial, cmd, READ_RANGE_TIMEOUT_MS)
            if (out.isSuccess) {
                return Result.success(out.getOrThrow()) // 空 = EOF
            }
            Logx.w(TAG, "tail/head 读片段失败，降级到 dd：${out.exceptionOrNull()?.message}")
        }

        // 二级：dd（走 execOut 绕开 ShellExecutor 的危险命令拦截）
        val dd: ByteArray? = ddRead(serial, path, offset, length)
        if (dd != null) return Result.success(dd)

        // 三级：整文件读进内存，只在文件不大时做
        val size: Long = sizeOf(serial, path).getOrDefault(-1L)
        if (size in 1..MAX_MEMORY_READ_BYTES) {
            val whole: ByteArray? = readBytes(serial, path).getOrNull()
            if (whole != null) {
                val from: Int = offset.coerceAtMost(whole.size.toLong()).toInt()
                val to: Int = (offset + length.toLong()).coerceAtMost(whole.size.toLong()).toInt()
                return Result.success(
                    if (to > from) whole.copyOfRange(from, to) else ByteArray(0)
                )
            }
        }

        val detail: String = if (size > MAX_MEMORY_READ_BYTES) {
            "文件过大（${size} 字节），无法整读兜底"
        } else {
            TXT_NO_READ_TOOL
        }
        return Result.failure(IllegalStateException(detail))
    }

    /**
     * `dd` 兜底读。
     *
     * ⚠️ 必须走 `adb.execOut`：`ShellExecutor.DANGEROUS` 含 `dd`，
     * 走 `runOnce` 会被拦截。
     *
     * `skip` 只能按 `bs` 跳，所以偏移不对齐时先往前对齐到 bs 的整数倍，
     * 多读出来的头部再切掉 —— 这样任意 offset 都能字节精确地读到。
     *
     * ⚠️ `2>/dev/null` 是**刚需**而不是可选：dd 每次读完都会往 stderr 打一行
     * 「N+0 records in / N+0 records out」的统计，`exec-out` 会把这段文字并进
     * 返回的字节流里，污染文件数据；dd 不可用时那句 `dd: not found` 同理。
     * 这条命令是单阶段的（没有管道），所以一个重定向就覆盖全部。
     *
     * @return 成功返回字节数组；dd 不可用 / 失败返回 null
     */
    private suspend fun ddRead(
        serial: String,
        path: String,
        offset: Long,
        length: Int,
    ): ByteArray? {
        val skip: Long = offset / DD_BLOCK_SIZE
        val head: Int = (offset % DD_BLOCK_SIZE).toInt()
        val need: Long = length.toLong() + head.toLong()
        val count: Long = (need + DD_BLOCK_SIZE - 1L) / DD_BLOCK_SIZE // 向上取整
        if (count <= 0L) return ByteArray(0)

        val cmd: String =
            "dd if=${q(path)} bs=$DD_BLOCK_SIZE skip=$skip count=$count 2>/dev/null"
        val raw: ByteArray = adb.execOut(serial, cmd, READ_RANGE_TIMEOUT_MS).getOrNull()
            ?: run {
                Logx.w(TAG, "dd 读片段失败：$cmd")
                return null
            }
        val from: Int = head.coerceAtMost(raw.size)
        val to: Int = (head.toLong() + length.toLong()).coerceAtMost(raw.size.toLong()).toInt()
        return raw.copyOfRange(from, to.coerceAtLeast(from))
    }

    /**
     * 打开远端文件的流式读源（视频 / 音乐不落盘播放的地基）。
     *
     * 内部先 [sizeOf]，拿不到就退回调用方给的 [fallbackSize]（列目录时 `ls -l`
     * 已经给出过一次大小，通常够用，能省一条命令）。
     *
     * ⚠️ 探测读工具**不作为硬门槛**：探到 false 只记一条警告。因为 [readRange]
     * 还有 `dd` 和「整文件读」两级兜底，直接失败会把本来还能播的设备拦在门外。
     *
     * @param fallbackSize [sizeOf] 失败时使用的大小（传 [FileEntry.sizeBytes]）
     */
    suspend fun openStream(
        serial: String,
        path: String,
        fallbackSize: Long,
    ): Result<RemoteFileStream> {
        val size: Long = sizeOf(serial, path).getOrDefault(-1L).takeIf { it > 0L }
            ?: fallbackSize
        if (size <= 0L) {
            return Result.failure(IllegalStateException("无法获取文件大小，无法流式读取：$path"))
        }
        if (!probeReadTool(serial).getOrDefault(false)) {
            Logx.w(TAG, "$serial 缺少 tail/head，流式读将尝试 dd / 整文件兜底：$path")
        }
        return Result.success(
            RemoteFileSource(
                path = path,
                sizeBytes = size,
                readRange = { offset, length -> readRange(serial, path, offset, length) },
            )
        )
    }

    /**
     * 探测可用的存储根：内部存储 + SD 卡。
     *
     * 不同机型路径差异很大（`/storage/emulated/0`、`/storage/XXXX-XXXX`、`/sdcard`），
     * 这里统一扫 `/storage` 下一级再逐个判定。
     *
     * ⚠️ **内部存储必须在返回的列表里**（[DEFAULT_ROOT]，且排在首位）：
     * 它是文件管理页的默认起始目录，上层把本函数的返回集合当作「可返回的最顶层」。
     * 若只在无 SD 卡时才把它兜底加进来，带 SD 卡的机型上用户就能从
     * `/storage/emulated/0` 继续往上退到 `/storage/emulated` 这种挂载点层。
     *
     * @return 内部存储 + SD 卡；任一项 `df` 失败则跳过该项（**不会**因单个失败而整体失败）
     */
    suspend fun storages(serial: String): Result<List<StorageInfo>> {
        val raw: ShellResult = shell.runOnce(
            serial = serial,
            cmd = "ls /storage",
            timeoutMs = CMD_TIMEOUT_MS,
        )
        val names: List<String> = raw.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "emulated" && it != "self" }
            .toList()

        val out = ArrayList<StorageInfo>(4)
        // ★ 内部存储**恒定在列表首位**：它既是默认起始目录 [DEFAULT_ROOT]，也是一个合法存储根。
        //   ⚠️ 原先只把它当「ls /storage 什么都拿不到」时的兜底，于是带了 SD 卡的机型上
        //   默认根目录不被认作存储根 —— 后果是用户能从 /storage/emulated/0 继续往上退到
        //   /storage/emulated（一堆挂载点数字目录）。用户要求 /storage/emulated/0 就是最顶层。
        val internal: StorageInfo? = storageInfo(serial, DEFAULT_ROOT, TXT_INTERNAL)
        if (internal != null) out.add(internal)
        for (name in names) {
            val path = "/storage/$name"
            // 已经是内部存储根就跳过，别出重复卡
            if (internal != null && path == internal.path) continue
            val info: StorageInfo? = storageInfo(serial, path, labelOf(name, path))
            if (info != null) out.add(info)
        }
        return Result.success(out)
    }

    /** 用 `df -k` 读单个存储的容量 */
    private suspend fun storageInfo(
        serial: String,
        path: String,
        label: String,
    ): StorageInfo? {
        val result: ShellResult = shell.runOnce(
            serial = serial,
            cmd = "df -k ${q(path)}",
            timeoutMs = CMD_TIMEOUT_MS,
        )
        // 形如：
        // Filesystem 1K-blocks    Used Available Use% Mounted on
        // /data/media 131072000 80280000 50792000  61% /storage/emulated/0
        val line: String? = result.stdout.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() && !it.startsWith("Filesystem") }
        if (line == null) return null
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 3) return null
        val totalKb: Long = parts[1].toLongOrNull() ?: return null
        val usedKb: Long = parts[2].toLongOrNull() ?: return null
        return StorageInfo(
            label = label,
            path = path,
            usedBytes = usedKb * 1024L,
            totalBytes = totalKb * 1024L,
        )
    }

    private fun labelOf(name: String, path: String): String =
        if (path == DEFAULT_ROOT || name == "emulated") TXT_INTERNAL else TXT_SD_CARD

    companion object {
        private const val TAG = "FileRepository"
        private const val LIST_TIMEOUT_MS = 15_000L
        private const val CMD_TIMEOUT_MS = 10_000L
        private const val DELETE_TIMEOUT_MS = 20_000L
        private const val MOVE_TIMEOUT_MS = 30_000L
        private const val READ_TIMEOUT_MS = 30_000L

        /** 单次 range 读的超时：2 MB 走 Wi-Fi 最坏情况也要给足 30 秒 */
        private const val READ_RANGE_TIMEOUT_MS = 30_000L

        /**
         * 整文件读进内存的兜底上限。
         *
         * 超过这个大小就**宁可失败也不 OOM** —— 控制端自己可能也只有几百 MB 可用堆。
         * 正常路径（tail/dd）根本走不到这一级。
         */
        private const val MAX_MEMORY_READ_BYTES = 64L * 1024L * 1024L

        /** `dd` 的块大小：8 KB，兼顾 syscall 次数与 skip 精度 */
        private const val DD_BLOCK_SIZE = 8192L

        /** 读工具探测的输出标记（探测命令里 `echo tail=$?` / `echo head=$?` 的期望值） */
        private const val PROBE_TAIL_OK = "tail=0"
        private const val PROBE_HEAD_OK = "head=0"

        private const val TXT_NO_READ_TOOL = "被控端缺少 `tail`/`head`，无法流式读取该文件"

        /** 内部存储的默认路径 */
        const val DEFAULT_ROOT = "/storage/emulated/0"

        private const val TXT_INTERNAL = "内部存储"
        private const val TXT_SD_CARD = "SD 卡"

        /**
         * 单引号包裹 + 转义内部的单引号。
         *
         * `it's a file.txt` → `'it'\''s a file.txt'`
         * 这样文件名里的空格、`$`、`` ` ``、`;` 都不会被 shell 重新解释。
         */
        internal fun q(path: String): String = "'${path.replace("'", "'\\''")}'"

        /**
         * `ls -la` 输出的日期形态：
         * - `2026-09-20 10:30`（toybox 较新版本）
         * - `Sep 20 10:30` / `Sep 20  2025`（传统 coreutils 风格）
         */
        private val DATE_REGEX = Regex(
            """(\d{4}-\d{2}-\d{2}\s+\d{1,2}:\d{2})|([A-Z][a-z]{2}\s+\d{1,2}\s+(?:\d{1,2}:\d{2}|\d{4}))"""
        )

        private val DATE_PARSE_ISO = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        private val DATE_PARSE_TIME = SimpleDateFormat("MMM dd HH:mm", Locale.US)
        private val DATE_PARSE_YEAR = SimpleDateFormat("MMM dd yyyy", Locale.US)

        /**
         * 从一行 `ls -l` 输出里取文件大小（字节）。
         *
         * ⚠️ 刻意**不按下标硬取第 5 列**：各 ROM 的 toybox 版本列数不一致
         * （有的省掉 group 列，变成 `-rw-rw---- 1 u0_a123 1048576 2026-09-20 …`），
         * 按下标取会把 owner / group 当成 size，解析出一个「看着像合法值」的错误结果。
         * 稳定的是**相对关系**：日期前最后一个 token 一定是 size（理由同 [parseLs]）。
         *
         * [parseLs] 与 [sizeOf] 的 ls 回退**必须共用这一个函数** —— 否则会出现
         * 「列目录里显示 12 MB、流式读按 0 字节处理」这类两套策略打架的 bug。
         *
         * @param line 单行 `ls -l` 输出（可含前后空白）
         * @return 解析出的大小；该行没有可识别的日期 / size 不是数字时返回 null
         */
        private fun sizeFromLsLine(line: String): Long? {
            val trimmed: String = line.trim()
            val dateMatch: MatchResult = DATE_REGEX.find(trimmed) ?: return null
            val before: String = trimmed.substring(0, dateMatch.range.first).trim()
            if (before.isEmpty()) return null
            return before.split(Regex("\\s+")).lastOrNull()?.toLongOrNull()
        }

        /**
         * 解析 `ls -la` 输出。
         *
         * 解析策略刻意做成「先定位日期，再反推 size 和文件名」，而不是按下标硬取：
         * 各 ROM 的 toybox 版本字段数不一致（有的省掉 group 列），
         * 但**日期前一定是 size、日期后一定是文件名**这个相对关系是稳定的。
         */
        internal fun parseLs(stdout: String, parent: String): List<FileEntry> {
            val out = ArrayList<FileEntry>()
            for (raw in stdout.lineSequence()) {
                val line = raw.trimEnd()
                if (line.isBlank()) continue
                if (line.startsWith("total ", ignoreCase = true)) continue
                if (line.startsWith("ls:", ignoreCase = true)) continue

                // 权限位列必须是 d/-/l/b/c/p/s 之一，否则这行不是 ls -l 的输出
                val perms: String = line.substringBefore(' ')
                if (perms.isEmpty() || perms[0] !in "d-lbcps") continue

                val dateMatch = DATE_REGEX.find(line) ?: continue
                val isDir = perms.startsWith("d")

                // size = 日期之前最后一个 token（取法统一走 [sizeFromLsLine]，
                // 与 [sizeOf] 的 ls 回退共用，避免两套解析策略打架）
                val sizeBytes: Long = sizeFromLsLine(line) ?: 0L

                // 文件名 = 日期之后全部内容（可能含空格）
                val name: String = line.substring(dateMatch.range.last + 1).trim()
                if (name.isEmpty() || name == "." || name == "..") continue

                val modifiedText: String = dateMatch.value
                val epochSec: Long = parseEpoch(modifiedText)

                val path: String = if (parent.endsWith("/")) parent + name else "$parent/$name"
                out.add(
                    FileEntry(
                        name = name,
                        path = path,
                        isDir = isDir,
                        sizeBytes = sizeBytes,
                        modifiedEpochSec = epochSec,
                        modifiedText = modifiedText,
                        kind = fileKindOf(name, isDir),
                    )
                )
            }
            out.sortBy { it.name.lowercase() }
            return out
        }

        /**
         * 把日期文本解析成 epoch 秒，**仅供排序**。
         *
         * 按被控端返回的原始文本解析（不做时区换算），保证排序与显示一致。
         * 解析失败返回 0 —— 排序时退到末尾，但显示仍是原始文本，不影响正确性。
         */
        internal fun parseEpoch(text: String): Long {
            val trimmed: String = text.trim()
            return try {
                when {
                    trimmed.contains("-") -> DATE_PARSE_ISO.parse(trimmed)?.time
                    trimmed.contains(":") -> DATE_PARSE_TIME.parse(trimmed)?.time
                    else -> DATE_PARSE_YEAR.parse(trimmed)?.time
                }?.div(1000L) ?: 0L
            } catch (error: Exception) {
                0L
            }
        }
    }
}
