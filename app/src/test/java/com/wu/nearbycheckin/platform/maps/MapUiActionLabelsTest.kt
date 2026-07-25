package com.wu.nearbycheckin.platform.maps

import org.junit.Assert.assertEquals
import org.junit.Test

import com.wu.nearbycheckin.core.model.MapProvider

class MapUiActionLabelsTest {

    // 验证百度动作映射包含当前版本实际标签。
    @Test
    fun mapsBaiduActionsToVerifiedLabels() {
        assertEquals(
            listOf("首页"),
            MapUiActionLabels.forProvider(
                MapProvider.BAIDU,
                MapUiAction.CLICK_HOME
            )
        )
        assertEquals(
            listOf("立即打卡", "立刻打卡"),
            MapUiActionLabels.forProvider(
                MapProvider.BAIDU,
                MapUiAction.CLICK_CHECK_IN
            )
        )
    }

    // 验证高德动作映射允许直接打卡。
    @Test
    fun mapsAmapCheckInActionToVerifiedLabels() {
        assertEquals(
            listOf("打卡", "点击打卡"),
            MapUiActionLabels.forProvider(
                MapProvider.AMAP,
                MapUiAction.CLICK_CHECK_IN
            )
        )
    }
}
