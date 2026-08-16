// @Time: 2026/08/16 02:26
// @Author: MrWu
// @File: AmapCheckInPluginTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.maps


import com.wu.nearbycheckin.core.domain.plugin.MapWindowSnapshot
import com.wu.nearbycheckin.core.domain.plugin.PluginCommandType
import com.wu.nearbycheckin.core.domain.plugin.PluginNodeSnapshot
import com.wu.nearbycheckin.core.domain.plugin.PluginPage
import com.wu.nearbycheckin.core.model.MapProvider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * 验证高德 V5 插件真实流程和当前位置候选。
 */
class AmapCheckInPluginTest {

    private val plugin = AmapCheckInPlugin()

    // 验证开始打卡页生成当前真实位置候选。
    @Test
    fun createsCurrentLocationCandidateFromStartPage() {
        val window = window(
            PluginNodeSnapshot(
                text = "开始打卡",
                clickable = true,
                fingerprint = "start"
            )
        )

        val candidate = plugin.readCandidates(window).single()

        assertEquals(PluginPage.CANDIDATE_LIST, plugin.inspect(window).page)
        assertEquals("当前高德打卡位置", candidate.name)
        assertEquals(
            PluginCommandType.SELECT_CANDIDATE,
            plugin.buildSelectAction(candidate).type
        )
    }

    // 验证高德我的页进入足迹动作。
    @Test
    fun navigatesFromMineToFootprint() {
        val state = plugin.inspect(
            window(PluginNodeSnapshot(text = "我的"))
        )

        assertEquals(PluginPage.HOME, state.page)
        assertEquals(
            PluginCommandType.NAVIGATE_MINE,
            plugin.buildNextAction(state)?.type
        )
    }

    // 验证高德足迹页优先点击真实的打卡点入口。
    @Test
    fun opensCheckInPointFromFootprintPage() {
        val state = plugin.inspect(
            window(
                PluginNodeSnapshot(text = "足迹"),
                PluginNodeSnapshot(text = "打卡点")
            )
        )

        val command = plugin.buildNextAction(state)

        assertEquals(PluginPage.MINE, state.page)
        assertEquals(PluginCommandType.OPEN_CHECK_IN_ENTRY, command?.type)
        assertTrue(command?.target?.labels?.contains("打卡点") == true)
    }

    // 创建高德插件窗口快照。
    private fun window(
        vararg nodes: PluginNodeSnapshot
    ): MapWindowSnapshot {
        return MapWindowSnapshot(
            provider = MapProvider.AMAP,
            packageName = "com.autonavi.minimap",
            appVersion = "current",
            profileVersion = plugin.profileVersion,
            nodes = nodes.toList(),
            capturedAtMillis = 100L
        )
    }
}
