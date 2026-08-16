// @Time: 2026/08/16 08:30
// @Author: MrWu
// @File: AutomationRuntimeLogFileStore.kt
// @Software: Android Studio


package com.wu.nearbycheckin.data.repository


import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

import com.wu.nearbycheckin.core.model.AutomationLogEntry


/**
 * 以不覆盖旧分段的 JSONL 文件持续保存完整运行日志。
 */
internal class AutomationRuntimeLogFileStore(
    private val directory: File,
    private val maxSegmentBytes: Long = DEFAULT_MAX_SEGMENT_BYTES
) {

    init {
        require(maxSegmentBytes > 0L) {
            "日志分段大小必须大于零"
        }
    }

    // 同步追加一条日志并在当前分段达到上限时创建新文件。
    @Synchronized
    fun append(entry: AutomationLogEntry) {
        val encoded = entry.toJsonLine()
            .toByteArray(StandardCharsets.UTF_8)
        val target = resolveWritableSegment(encoded.size.toLong())
        FileOutputStream(target, true).use { output ->
            output.write(encoded)
            output.flush()
        }
    }

    // 删除全部运行日志分段且不影响目录中的其他文件。
    @Synchronized
    fun clear() {
        segmentFiles().forEach { file ->
            if (!file.delete()) {
                throw IOException("无法删除运行日志分段：${file.name}")
            }
        }
    }

    // 返回按分段编号排序的现有日志文件。
    @Synchronized
    internal fun segmentFiles(): List<File> {
        return directory.listFiles().orEmpty()
            .filter { file ->
                file.isFile &&
                        file.name.startsWith(FILE_PREFIX) &&
                        file.name.endsWith(FILE_SUFFIX)
            }
            .sortedBy(File::getName)
    }

    // 复用未满分段或创建不会覆盖历史文件的新分段。
    private fun resolveWritableSegment(entryBytes: Long): File {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("无法创建运行日志目录")
        }
        val existing = segmentFiles()
        existing.lastOrNull()?.takeIf { file ->
            file.length() + entryBytes <= maxSegmentBytes
        }?.let { file ->
            return file
        }
        val nextIndex = existing.maxOfOrNull { file ->
            file.name.removePrefix(FILE_PREFIX)
                .removeSuffix(FILE_SUFFIX)
                .toIntOrNull() ?: 0
        }?.plus(1) ?: 1
        val nextFile = File(
            directory,
            "$FILE_PREFIX${nextIndex.toString().padStart(INDEX_WIDTH, '0')}" +
                    FILE_SUFFIX
        )
        if (!nextFile.createNewFile()) {
            throw IOException("运行日志分段已经存在：${nextFile.name}")
        }
        return nextFile
    }

    // 将日志条目编码为单行 UTF-8 JSON 并保留控制字符语义。
    private fun AutomationLogEntry.toJsonLine(): String {
        return buildString {
            append("{\"timestampMillis\":")
            append(timestampMillis)
            append(",\"level\":\"")
            append(level.name.escapeJson())
            append("\",\"source\":\"")
            append(source.escapeJson())
            append("\",\"message\":\"")
            append(message.escapeJson())
            append("\"}\n")
        }
    }

    // 转义 JSON 字符串中的引号、反斜线和控制字符。
    private fun String.escapeJson(): String {
        return buildString(length) {
            this@escapeJson.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.code < MIN_PRINTABLE_CODE) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
    }

    private companion object {
        const val FILE_PREFIX = "nearbycheckin-runtime-"
        const val FILE_SUFFIX = ".jsonl"
        const val INDEX_WIDTH = 6
        const val MIN_PRINTABLE_CODE = 0x20
        const val DEFAULT_MAX_SEGMENT_BYTES = 25L * 1024L * 1024L
    }
}
