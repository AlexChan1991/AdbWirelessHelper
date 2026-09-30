package com.adb.adbwirelesshelper.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简日志封装：
 * 1) 转发到 android.util.Log；
 * 2) 内存环形缓冲（最近 500 条），供「日志页」即时展示；
 * 3) 写入 App 私有目录的滚动文件（≤ 5MB × 3），供设置页导出（需求 A6）；
 * 4) 配对码脱敏：命中 pair/配对/code 关键字时把 6 位连续数字替换为 ******。
 *
 * 使用前提：在 Application.onCreate 调用一次 [init]；未初始化时只写 Logcat 与内存缓冲。
 */
object Logx {

    private const val RING_CAPACITY = 500
    private const val MAX_FILE_BYTES = 5L * 1024L * 1024L
    private const val MAX_FILE_COUNT = 3
    private const val CURRENT_FILE = "adbwifi.log"
    private const val LOG_DIR = "logs"

    private val lock = Any()
    private val ring = ArrayList<String>(RING_CAPACITY + 8)
    private val codeRegex = Regex("(?<!\\d)\\d{6}(?!\\d)")
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private val fileFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)

    @Volatile
    private var logDir: File? = null

    /** 初始化日志文件目录。幂等，可重复调用。 */
    fun init(context: Context) {
        val dir = File(context.applicationContext.filesDir, LOG_DIR)
        try {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            logDir = dir
        } catch (t: Throwable) {
            Log.w("Logx", "日志目录创建失败，仅保留内存缓冲: ${t.message}")
        }
    }

    fun d(tag: String, msg: String) = write(Log.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = write(Log.INFO, tag, msg)
    fun w(tag: String, msg: String) = write(Log.WARN, tag, msg)
    fun e(tag: String, msg: String) = write(Log.ERROR, tag, msg)

    /** 返回内存环形缓冲的快照副本（线程安全）。 */
    fun snapshot(): List<String> = synchronized(lock) { ArrayList(ring) }

    /** 清空内存缓冲（不删文件）。 */
    fun clearMemory() = synchronized(lock) { ring.clear() }

    /**
     * 导出日志到目标文件：先写内存快照，再按时间顺序追加磁盘上的滚动日志。
     * 必须在协程中调用（内部切到 Dispatchers.IO）。
     */
    suspend fun exportTo(file: File) = withContext(Dispatchers.IO) {
        runCatching {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val sb = StringBuilder()
            sb.append("# AdbWirelessHelper 日志导出\n")
            sb.append("# 导出时间: ").append(fileFmt.format(Date())).append('\n')
            sb.append("# 说明: 全部日志仅存于本机，配对码已脱敏\n\n")
            sb.append("===== 内存缓冲（最近 ").append(RING_CAPACITY).append(" 条） =====\n")
            snapshot().forEach { sb.append(it).append('\n') }
            sb.append('\n').append("===== 磁盘日志 =====\n")
            val dir = logDir
            if (dir != null) {
                (MAX_FILE_COUNT - 1 downTo 1).forEach { idx ->
                    val old = File(dir, "$CURRENT_FILE.$idx")
                    if (old.exists()) {
                        sb.append(old.readText(Charsets.UTF_8))
                    }
                }
                val cur = File(dir, CURRENT_FILE)
                if (cur.exists()) {
                    sb.append(cur.readText(Charsets.UTF_8))
                }
            } else {
                sb.append("（磁盘日志未初始化）\n")
            }
            file.writeText(sb.toString(), Charsets.UTF_8)
            file
        }.onFailure {
            Log.e("Logx", "日志导出失败: ${it.message}")
        }
    }

    /** 删除磁盘上的滚动日志文件（设置页「清空日志」可用）。 */
    suspend fun clearFiles() = withContext(Dispatchers.IO) {
        val dir = logDir ?: return@withContext
        runCatching {
            File(dir, CURRENT_FILE).delete()
            for (idx in 1 until MAX_FILE_COUNT) {
                File(dir, "$CURRENT_FILE.$idx").delete()
            }
        }
    }

    private fun write(priority: Int, tag: String, rawMsg: String) {
        val msg = sanitize(tag, rawMsg)
        Log.println(priority, tag, msg)
        val now = timeFmt.format(Date())
        val level = when (priority) {
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            else -> "E"
        }
        val line = "$now $level/$tag: $msg"
        synchronized(lock) {
            ring.add(line)
            while (ring.size > RING_CAPACITY) {
                ring.removeAt(0)
            }
        }
        appendToFile(line)
    }

    /** 配对码脱敏：仅在疑似配对上下文里替换 6 位连续数字。 */
    internal fun sanitize(tag: String, msg: String): String {
        val lower = (tag + msg).lowercase(Locale.ROOT)
        val sensitive = lower.contains("pair") || lower.contains("配对") ||
            lower.contains("code") || lower.contains("配对码")
        return if (sensitive) msg.replace(codeRegex, "******") else msg
    }

    private fun appendToFile(line: String) {
        val dir = logDir ?: return
        try {
            rotateIfNeeded(dir)
            File(dir, CURRENT_FILE).appendText(line + "\n", Charsets.UTF_8)
        } catch (t: Throwable) {
            // 日志落盘失败绝不能影响业务流程
            Log.w("Logx", "日志写入失败: ${t.message}")
        }
    }

    /** 超过 MAX_FILE_BYTES 时做一次滚动：.log → .log.1 → .log.2（更早的丢弃）。 */
    private fun rotateIfNeeded(dir: File) {
        val cur = File(dir, CURRENT_FILE)
        if (!cur.exists() || cur.length() < MAX_FILE_BYTES) {
            return
        }
        for (idx in MAX_FILE_COUNT - 1 downTo 1) {
            val from = if (idx == 1) cur else File(dir, "$CURRENT_FILE.${idx - 1}")
            val to = File(dir, "$CURRENT_FILE.$idx")
            if (from.exists()) {
                if (to.exists()) {
                    to.delete()
                }
                from.renameTo(to)
            }
        }
        if (cur.exists()) {
            cur.delete()
        }
    }
}
