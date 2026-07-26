// @Time: 2026/07/26 02:04
// @Author: MrWu
// @File: MapUiProfileModelsTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapUiProfileModelsTest {

    // 验证标签匹配同时支持文本、描述和可点击过滤。
    @Test
    fun matchesTextDescriptionAndClickableState() {
        val snapshot = MapUiSnapshot(
            packageName = "sample",
            appVersion = "1.0",
            nodes = listOf(
                MapUiNodeSnapshot(text = "我的", clickable = false),
                MapUiNodeSnapshot(
                    description = "打卡，按钮",
                    clickable = true
                )
            )
        )

        assertTrue(snapshot.hasLabel("我的"))
        assertTrue(snapshot.hasClickableLabel("打卡"))
        assertFalse(snapshot.hasClickableLabel("我的"))
    }

    // 验证手势目标属于可执行标签但不伪装成节点点击能力。
    @Test
    fun distinguishesNodeClickAndGestureTarget() {
        val snapshot = MapUiSnapshot(
            packageName = "sample",
            appVersion = "1.0",
            nodes = listOf(
                MapUiNodeSnapshot(
                    text = "我的",
                    clickable = false,
                    gestureTarget = true
                )
            )
        )

        assertFalse(snapshot.hasClickableLabel("我的"))
        assertTrue(snapshot.hasActionableLabel("我的"))
    }

    // 验证相同节点内容产生稳定页面指纹。
    @Test
    fun buildsStableFingerprintFromNormalizedNodes() {
        val first = MapUiSnapshot(
            packageName = "sample",
            appVersion = "1.0",
            nodes = listOf(
                MapUiNodeSnapshot(text = " 首页 "),
                MapUiNodeSnapshot(description = "我的")
            )
        )
        val second = first.copy(
            nodes = listOf(
                MapUiNodeSnapshot(text = "首页"),
                MapUiNodeSnapshot(description = "我的")
            )
        )

        assertEquals(first.fingerprint(), second.fingerprint())
    }
}
