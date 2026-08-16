// @Time: 2026/08/16 02:26
// @Author: MrWu
// @File: BaiduCheckInPluginTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.maps


import com.wu.nearbycheckin.core.domain.plugin.MapWindowSnapshot
import com.wu.nearbycheckin.core.domain.plugin.PluginCommandType
import com.wu.nearbycheckin.core.domain.plugin.PluginNodeSnapshot
import com.wu.nearbycheckin.core.domain.plugin.PluginPage
import com.wu.nearbycheckin.core.model.MapOperationStatus
import com.wu.nearbycheckin.core.model.MapProvider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * 验证百度 V5 插件候选读取、完成标记和结果证据。
 */
class BaiduCheckInPluginTest {

    private val plugin = BaiduCheckInPlugin()

    // 验证候选列表行被转换为百度 Provider 候选。
    @Test
    fun readsVisibleCandidateRow() {
        val window = window(
            node("选择打卡地点", parent = "title"),
            node("人民公园", parent = "row-1", fingerprint = "place"),
            node("人民路 1 号", parent = "row-1"),
            node("120米", parent = "row-1")
        )

        val candidates = plugin.readCandidates(window)

        assertEquals(PluginPage.CANDIDATE_LIST, plugin.inspect(window).page)
        assertEquals(1, candidates.size)
        assertEquals("人民公园", candidates.single().name)
        assertEquals("120米", candidates.single().distanceText)
    }

    // 验证 UI 已打卡标记进入候选完成状态。
    @Test
    fun importsUiCompletedMarker() {
        val candidates = plugin.readCandidates(
            window(
                node("人民公园", parent = "row-1"),
                node("已打卡", parent = "row-1"),
                node("80米", parent = "row-1")
            )
        )

        assertTrue(candidates.single().completed)
    }

    // 验证成功页产生结构化成功证据。
    @Test
    fun verifiesSuccessPage() {
        plugin.inspect(
            window(
                node("打卡成功"),
                node("年已打卡了"),
                node("1704")
            )
        )
        val candidate = plugin.readCandidates(
            window(
                node("人民公园", parent = "row-1", fingerprint = "place"),
                node("80米", parent = "row-1")
            )
        ).single()
        plugin.buildSelectAction(candidate)

        val evidence = plugin.verifyResult(
            window(
                node("打卡成功"),
                node("年已打卡了"),
                node("1705")
            )
        )

        assertEquals(MapOperationStatus.SUCCESS, evidence.resultStatus)
        assertEquals("人民公园", evidence.matchedText)
    }

    // 验证成功文案出现但地点统计未增加时不会写成新增成功。
    @Test
    fun treatsUnchangedCountAsAlreadyCompleted() {
        plugin.inspect(
            window(
                node("打卡成功"),
                node("年已打卡了"),
                node("1704")
            )
        )
        val candidate = plugin.readCandidates(
            window(
                node("人民公园", parent = "row-1", fingerprint = "place"),
                node("80米", parent = "row-1")
            )
        ).single()
        plugin.buildSelectAction(candidate)

        val evidence = plugin.verifyResult(
            window(
                node("打卡成功"),
                node("年已打卡了"),
                node("1704")
            )
        )

        assertEquals(
            MapOperationStatus.ALREADY_COMPLETED,
            evidence.resultStatus
        )
    }

    // 验证未出现成功弹层时也能依据打卡首页地点总数增加完成事务。
    @Test
    fun verifiesCountIncreaseAfterReturningToCheckInEntry() {
        plugin.inspect(
            window(
                node("已打卡"),
                node("1704"),
                node("个地点"),
                node("立即打卡")
            )
        )
        val candidate = plugin.readCandidates(
            window(
                node("人民公园", parent = "row-1", fingerprint = "place"),
                node("80米", parent = "row-1")
            )
        ).single()
        plugin.buildSelectAction(candidate)

        val evidence = plugin.verifyResult(
            window(
                node("已打卡"),
                node("1705"),
                node("个地点"),
                node("立即打卡")
            )
        )

        assertEquals(MapOperationStatus.SUCCESS, evidence.resultStatus)
        assertEquals(PluginPage.CHECK_IN_ENTRY.name, evidence.page)
    }

    // 验证分享主题遮罩可被识别并通过稳定视图标识关闭。
    @Test
    fun dismissesSharePanelByStableViewId() {
        val state = plugin.inspect(window(node("请选择分享主题")))

        val command = plugin.buildNextAction(state)

        assertEquals(PluginPage.SHARE_PANEL, state.page)
        assertEquals(PluginCommandType.DISMISS_RESULT, command?.type)
        assertEquals(
            "com.baidu.BaiduMap:id/iv_close_share",
            command?.target?.viewId
        )
    }

    // 验证百度足迹首页通过“打卡”页签进入打卡流程。
    @Test
    fun opensCheckInTabFromFootprintHome() {
        val state = plugin.inspect(
            window(
                node("角落"),
                node("轨迹"),
                node("打卡")
            )
        )

        val command = plugin.buildNextAction(state)

        assertEquals(PluginPage.FOOTPRINT, state.page)
        assertEquals(PluginCommandType.OPEN_CHECK_IN_ENTRY, command?.type)
        assertEquals(listOf("打卡"), command?.target?.labels)
    }

    // 验证“点亮更多地点”不会再次被当成自动打卡入口。
    @Test
    fun ignoresAlbumFootprintEntry() {
        val state = plugin.inspect(window(node("点亮更多地点")))

        assertEquals(PluginPage.UNKNOWN, state.page)
        assertEquals(null, plugin.buildNextAction(state))
    }

    // 验证立即打卡只负责进入地点列表而不生成虚假的当前地点任务。
    @Test
    fun opensImmediateCheckInAsNavigation() {
        val window = window(
            node("立即打卡", fingerprint = "immediate-check-in")
        )

        val state = plugin.inspect(window)
        val command = plugin.buildNextAction(state)

        assertEquals(PluginPage.CHECK_IN_ENTRY, state.page)
        assertEquals(PluginCommandType.OPEN_CHECK_IN_ENTRY, command?.type)
        assertTrue(plugin.readCandidates(window).isEmpty())
    }

    // 验证候选目标绑定名称节点而不是动态距离节点。
    @Test
    fun bindsCandidateToNameNodeInsteadOfDistanceNode() {
        val candidate = plugin.readCandidates(
            window(
                node("选择打卡地点", parent = "title"),
                node("人民公园", parent = "row-1", fingerprint = "name"),
                node("120米", parent = "row-1", fingerprint = "place")
            )
        ).single()

        assertEquals("name", candidate.fingerprint)
    }

    // 验证相册足迹不会冒充打卡足迹并通过稳定返回按钮恢复。
    @Test
    fun recoversFromAlbumFootprintPage() {
        val state = plugin.inspect(
            window(
                node("相册足迹"),
                node("相册足迹读取中")
            )
        )

        val command = plugin.buildNextAction(state)

        assertEquals(PluginPage.RECOVERY, state.page)
        assertEquals(PluginCommandType.NAVIGATE_BACK, command?.type)
        assertEquals(
            "com.baidu.BaiduMap:id/btn_back",
            command?.target?.viewId
        )
    }

    // 创建百度插件窗口快照。
    private fun window(
        vararg nodes: PluginNodeSnapshot
    ): MapWindowSnapshot {
        return MapWindowSnapshot(
            provider = MapProvider.BAIDU,
            packageName = "com.baidu.BaiduMap",
            appVersion = "current",
            profileVersion = plugin.profileVersion,
            nodes = nodes.toList(),
            capturedAtMillis = 100L
        )
    }

    // 创建候选测试节点。
    private fun node(
        text: String,
        parent: String = "root",
        fingerprint: String = text
    ): PluginNodeSnapshot {
        return PluginNodeSnapshot(
            text = text,
            clickable = fingerprint == "place",
            parentFingerprint = parent,
            fingerprint = fingerprint
        )
    }
}
