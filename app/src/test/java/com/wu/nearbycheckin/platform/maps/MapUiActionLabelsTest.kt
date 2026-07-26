// @Time: 2026/07/26 04:19
// @Author: MrWu
// @File: MapUiActionLabelsTest.kt
// @Software: Android Studio


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

    // 验证百度全部动作标签按首次出现顺序去重。
    @Test
    fun listsAllBaiduActionLabels() {
        assertEquals(
            listOf(
                "我的",
                "打卡点",
                "立即打卡",
                "立刻打卡",
                "首页",
                "知道了",
                "完成"
            ),
            MapUiActionLabels.allForProvider(MapProvider.BAIDU)
        )
    }

    // 验证高德动作映射使用当前真机真实路径标签。
    @Test
    fun mapsAmapActionsToVerifiedLabels() {
        assertEquals(
            listOf("打卡点"),
            MapUiActionLabels.forProvider(
                MapProvider.AMAP,
                MapUiAction.CLICK_CHECK_IN_POINT
            )
        )
        assertEquals(
            listOf("开始打卡", "打卡"),
            MapUiActionLabels.forProvider(
                MapProvider.AMAP,
                MapUiAction.CLICK_CHECK_IN
            )
        )
    }

    // 验证“完成”只属于百度成功页清理动作。
    @Test
    fun mapsDoneOnlyToBaiduCleanup() {
        assertEquals(
            listOf("完成"),
            MapUiActionLabels.forProvider(
                MapProvider.BAIDU,
                MapUiAction.CLICK_DONE
            )
        )
        assertEquals(
            emptyList<String>(),
            MapUiActionLabels.forProvider(
                MapProvider.AMAP,
                MapUiAction.CLICK_DONE
            )
        )
    }
}
