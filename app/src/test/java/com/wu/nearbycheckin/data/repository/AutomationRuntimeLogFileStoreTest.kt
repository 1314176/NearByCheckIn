// @Time: 2026/08/16 08:30
// @Author: MrWu
// @File: AutomationRuntimeLogFileStoreTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.data.repository


import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

import com.wu.nearbycheckin.core.model.AutomationLogEntry
import com.wu.nearbycheckin.core.model.AutomationLogLevel


/**
 * 验证完整日志的追加、跨实例续写、分段和手动清空行为。
 */
class AutomationRuntimeLogFileStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // 验证重新创建存储对象后继续追加且不会覆盖旧日志。
    @Test
    fun appendsAcrossStoreRestartWithoutOverwrite() {
        val directory = temporaryFolder.newFolder("logs")
        AutomationRuntimeLogFileStore(directory).append(entry(1L, "第一条"))

        AutomationRuntimeLogFileStore(directory).append(entry(2L, "第二条"))

        val files = AutomationRuntimeLogFileStore(directory).segmentFiles()
        assertEquals(1, files.size)
        assertEquals(2, files.single().readLines().size)
        assertTrue(files.single().readText().contains("第一条"))
        assertTrue(files.single().readText().contains("第二条"))
    }

    // 验证达到分段上限后创建新编号文件并完整保留全部日志。
    @Test
    fun rollsToNewSegmentWithoutDeletingOldFiles() {
        val directory = temporaryFolder.newFolder("segments")
        val store = AutomationRuntimeLogFileStore(
            directory = directory,
            maxSegmentBytes = 150L
        )

        repeat(10) { index ->
            store.append(entry(index.toLong(), "日志-$index-xxxxxxxxxx"))
        }

        val files = store.segmentFiles()
        assertTrue(files.size > 1)
        assertEquals(10, files.sumOf { file -> file.readLines().size })
        assertEquals(
            files.size,
            files.map { file -> file.name }.distinct().size
        )
    }

    // 验证手动清空只删除日志分段并保留同目录其他文件。
    @Test
    fun clearDeletesOnlyRuntimeLogSegments() {
        val directory = temporaryFolder.newFolder("clear")
        val unrelated = directory.resolve("keep.txt").apply {
            writeText("保留")
        }
        val store = AutomationRuntimeLogFileStore(directory)
        store.append(entry(1L, "待清空"))

        store.clear()

        assertTrue(store.segmentFiles().isEmpty())
        assertTrue(unrelated.exists())
    }

    // 验证消息控制字符会被编码在单行 JSON 中。
    @Test
    fun escapesMessageAsSingleJsonLine() {
        val directory = temporaryFolder.newFolder("escape")
        val store = AutomationRuntimeLogFileStore(directory)

        store.append(entry(1L, "引号\"和换行\n内容"))

        val lines = store.segmentFiles().single().readLines()
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("\\\""))
        assertTrue(lines.single().contains("\\n"))
        assertFalse(lines.single().contains("\n"))
    }

    // 创建测试日志条目。
    private fun entry(timestampMillis: Long, message: String): AutomationLogEntry {
        return AutomationLogEntry(
            timestampMillis = timestampMillis,
            level = AutomationLogLevel.INFO,
            source = "测试",
            message = message
        )
    }
}
