package com.wu.nearbycheckin.platform.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class MapOperationResultProbeTest {

    // 验证普通“成功”文本不会跨页面误判为打卡成功。
    @Test
    fun ignoresGenericSuccessText() {
        assertEquals(
            MapOperationProbeStatus.UNKNOWN,
            resolveMapOperationProbeStatus("操作成功")
        )
    }

    // 验证历史统计中的“已打卡”不会跨页面误判为已完成。
    @Test
    fun ignoresGenericAlreadyCompletedText() {
        assertEquals(
            MapOperationProbeStatus.UNKNOWN,
            resolveMapOperationProbeStatus("今年已打卡 256 个地点")
        )
    }

    // 验证精确成功标记仍可作为兼容证据识别。
    @Test
    fun recognizesExactSuccessMarkers() {
        assertEquals(
            MapOperationProbeStatus.SUCCESS,
            resolveMapOperationProbeStatus("当前位置 签到成功")
        )
    }

    // 验证精确已完成标记仍可作为兼容证据识别。
    @Test
    fun recognizesExactAlreadyCompletedMarkers() {
        assertEquals(
            MapOperationProbeStatus.ALREADY_COMPLETED,
            resolveMapOperationProbeStatus("当前地点已打卡")
        )
    }

    // 验证 Probe 证据会清理换行和内部字段分隔符。
    @Test
    fun sanitizesProbeEvidenceSeparatorsAndLineBreaks() {
        assertEquals(
            "第一行 第二行 内部 字段",
            formatMapOperationProbeEvidence(
                listOf(" 第一行\r\n第二行/内部；字段 ")
            )
        )
    }

    // 验证 Probe 证据的单条文本最多保留八十个字符。
    @Test
    fun limitsEachProbeEvidenceEntry() {
        assertEquals(
            "a".repeat(80),
            formatMapOperationProbeEvidence(
                listOf("a".repeat(120))
            )
        )
    }

    // 验证 Probe 证据总长度不超过五百一十二个字符。
    @Test
    fun limitsTotalProbeEvidenceLength() {
        assertEquals(
            512,
            formatMapOperationProbeEvidence(
                List(10) { "a".repeat(80) }
            ).length
        )
    }
}
