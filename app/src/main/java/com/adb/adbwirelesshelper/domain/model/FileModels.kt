package com.adb.adbwirelesshelper.domain.model

/**
 * 文件管理页的数据模型（规格 §4.6）。
 *
 * 管理的是**被控端设备**的文件系统，不是控制端本机。
 *
 * ⚠️ 关于日期（规格 §4.6.1 规则 2）：
 * `adb shell ls -la` 返回的是**被控端本地时区、精度只到分钟**的字符串。
 * 这里**原样保存该字符串用于显示**（[FileEntry.modifiedText]），
 * 同时解析出一个 epoch 秒仅供排序（[FileEntry.modifiedEpochSec]）。
 * 不做任何时区换算 —— 否则「按修改时间排序」的结果会和肉眼看到的日期对不上。
 */

/** 文件类型：决定图标与点击后走哪个预览器 */
enum class FileKind {
    DIR,
    IMAGE,
    VIDEO,
    AUDIO,
    TEXT,
    APK,
    ARCHIVE,
    OTHER,
}

/** 排序键（规格 §4.6.1：名称 / 大小 / 修改时间） */
enum class FileSortKey { NAME, SIZE, DATE }

/** 排序方向 */
enum class FileSortDir { ASC, DESC }

/**
 * 一个目录项。
 *
 * @param name              文件名（不含路径）
 * @param path              绝对路径，如 `/storage/emulated/0/DCIM/Camera`
 * @param isDir             是否目录
 * @param sizeBytes         字节数；目录为该目录的逻辑大小（ls 给出的值）
 * @param modifiedEpochSec  修改时间的 epoch 秒，**仅供排序**；解析不出时为 0
 * @param modifiedText      被控端返回的原始日期文本，**原样显示，不做时区转换**
 * @param kind              类型
 */
data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val sizeBytes: Long,
    val modifiedEpochSec: Long,
    val modifiedText: String,
    val kind: FileKind,
)

/** 存储概览（内部存储 / SD 卡）—— 仅在存储根目录显示（规格 §4.6.1） */
data class StorageInfo(
    val label: String,
    val path: String,
    val usedBytes: Long,
    val totalBytes: Long,
) {
    /** 已用百分比，0–100；总量为 0 时返回 0，避免除零 */
    val usedPercent: Int
        get() = if (totalBytes <= 0L) 0 else (usedBytes * 100 / totalBytes).toInt()
}

/** 按扩展名判定类型；目录一律 [FileKind.DIR] */
internal fun fileKindOf(name: String, isDir: Boolean): FileKind {
    if (isDir) return FileKind.DIR
    val ext: String = name.substringAfterLast('.', "").lowercase()
    if (name.equals(ext, ignoreCase = true)) return FileKind.OTHER // 没有扩展名
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "avif" -> FileKind.IMAGE
        "mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "flv", "ts" -> FileKind.VIDEO
        "mp3", "flac", "wav", "ogg", "m4a", "aac", "opus" -> FileKind.AUDIO
        "txt", "log", "json", "xml", "csv", "md", "ini", "prop", "sh", "yml", "yaml" ->
            FileKind.TEXT
        "apk", "apks", "xapk" -> FileKind.APK
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> FileKind.ARCHIVE
        else -> FileKind.OTHER
    }
}

/**
 * 是否可以用内置查看器打开（图片 / 视频 / 音乐）。
 *
 * 音乐原本不在列：早期视频靠 `adb pull` 落盘再播，音频没有对应通路。
 * 现在视频与音乐共用 [RemoteFileStream] 流式读（不落盘），
 * 音频也能直接喂给 MediaPlayer，所以一并列为可预览。
 */
internal fun FileEntry.isPreviewable(): Boolean =
    kind == FileKind.IMAGE || kind == FileKind.VIDEO || kind == FileKind.AUDIO
