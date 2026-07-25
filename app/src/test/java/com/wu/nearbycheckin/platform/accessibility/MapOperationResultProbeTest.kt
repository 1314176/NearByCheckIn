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
}
