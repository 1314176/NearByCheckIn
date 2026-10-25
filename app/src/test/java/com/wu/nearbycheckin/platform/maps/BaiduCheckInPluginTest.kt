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

    // 验证当前地点的明确完成提示仍可关闭，不受累计统计防误判影响。
    @Test
    fun recognizesExplicitCompletedPlace() {
        val state = plugin.inspect(window(node("该地点已打卡"), node("完成")))
        assertEquals(PluginPage.ALREADY_COMPLETED, state.page)
        assertEquals(PluginCommandType.DISMISS_RESULT, plugin.buildNextAction(state)?.type)
    }

    // 验证真实窗口外的残留成功页不会遮蔽当前打卡入口。
    @Test
    fun ignoresOffscreenResultPage() {
        val state = plugin.inspect(window(
            PluginNodeSnapshot(viewId = "android:id/content", right = 1080, bottom = 1188),
            PluginNodeSnapshot(text = "打卡成功", left = -200, right = 0, bottom = 300),
            PluginNodeSnapshot(text = "立即打卡", top = 500, right = 400, bottom = 600)
        ))
        assertEquals(PluginPage.CHECK_IN_ENTRY, state.page)
    }

    // 验证累计统计和页面返回按钮不会误触发关闭结果动作。
    @Test
    fun keepsSummaryWithBackButtonOnEntryPage() {
        val state = plugin.inspect(window(
            node("返回"), node("已打卡"), node("2820"),
            node("个地点"), node("立即打卡")
        ))
        assertEquals(PluginPage.CHECK_IN_ENTRY, state.page)
        assertEquals(PluginCommandType.OPEN_CHECK_IN_ENTRY,
            plugin.buildNextAction(state)?.type)
    }

    // 验证列表中的已打卡行和返回按钮不会把整个列表误识别为结果页。
    @Test
    fun keepsCompletedRowsOnCandidatePage() {
        val state = plugin.inspect(window(
            node("选择打卡地点"), node("返回"),
            node("人民公园"), node("已打卡"), node("80米")
        ))
        assertEquals(PluginPage.CANDIDATE_LIST, state.page)
    }

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
        assertEquals(1704, evidence.beforeCheckInCount)
        assertEquals(1705, evidence.afterCheckInCount)
    }

    // 验证成功页统计延迟刷新时继续等待，不提前永久跳过该地点。
    @Test
    fun waitsForDelayedCountOnSuccessPage() {
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
            MapOperationStatus.UNKNOWN,
            evidence.resultStatus
        )
        val returned = plugin.verifyResult(window(
            node("已打卡"), node("1705"), node("个地点"), node("立即打卡")
        ))
        assertEquals(MapOperationStatus.SUCCESS, returned.resultStatus)
        assertEquals(1704, returned.beforeCheckInCount)
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

    // 验证旧足迹页同时出现相机模板入口时只绑定与轨迹同排的底部打卡页签。
    @Test
    fun avoidsTemplateToolbarWhenOpeningFootprintTab() {
        val state = plugin.inspect(window(
            PluginNodeSnapshot(text = "打卡", viewId = "com.baidu.BaiduMap:id/tv_entry",
                left = 968, top = 664, right = 1020, bottom = 700, fingerprint = "camera"),
            PluginNodeSnapshot(text = "角落", top = 1527, right = 593, bottom = 1588),
            PluginNodeSnapshot(text = "轨迹", top = 1527, right = 772, bottom = 1588),
            PluginNodeSnapshot(text = "打卡", top = 1527, right = 950, bottom = 1588,
                fingerprint = "check-in-tab")
        ))

        assertEquals(PluginPage.FOOTPRINT, state.page)
        assertEquals("check-in-tab", plugin.buildNextAction(state)?.target?.nodeFingerprint)
    }

    // 验证“打卡当前位置”后的立即打卡优先于模板入口，并使用真实节点指纹。
    @Test
    fun selectsImmediateCheckInBesideCurrentPlace() {
        val state = plugin.inspect(window(
            node("打卡", fingerprint = "camera"), node("角落"), node("轨迹"),
            node("打卡当前位置："), node("浦联佳苑"),
            node("立即打卡", fingerprint = "real-check-in")
        ))
        val command = plugin.buildNextAction(state)

        assertEquals(PluginPage.CHECK_IN_ENTRY, state.page)
        assertEquals(listOf("立即打卡"), command?.target?.labels)
        assertEquals("real-check-in", command?.target?.nodeFingerprint)
    }

    // 验证新版模板主页可自动返回足迹，不点击相机打卡或挑选任何模板。
    @Test
    fun returnsFromNewTemplatePage() {
        val state = plugin.inspect(window(
            node("搜索打卡点、地点"), node("打卡"),
            node("打卡模版抢先试用"), node("手帐贴纸风"),
            node("专属冰箱贴"), node("街景推荐")
        ))
        val command = plugin.buildNextAction(state)

        assertEquals(PluginPage.RECOVERY, state.page)
        assertEquals(PluginCommandType.NAVIGATE_BACK, command?.type)
        assertEquals("com.baidu.BaiduMap:id/btn_back", command?.target?.viewId)
        assertTrue(plugin.readCandidates(window(node("打卡模板"))).isEmpty())
    }

    // 验证中文“模板”变体仍可恢复，且隐藏的立即打卡不被沿用到另一页面。
    @Test
    fun recoversTemplateVariantWithoutUsingOffscreenCheckIn() {
        val state = plugin.inspect(window(
            PluginNodeSnapshot(viewId = "android:id/content", right = 1080, bottom = 1188),
            PluginNodeSnapshot(text = "打卡模板抢先试用", right = 600, bottom = 700),
            PluginNodeSnapshot(text = "立即打卡", top = 1300, right = 600, bottom = 1400)
        ))
        assertEquals(PluginPage.RECOVERY, state.page)
        assertEquals(PluginCommandType.NAVIGATE_BACK, plugin.buildNextAction(state)?.type)
    }

    // 验证分屏裁切隐藏当前位置按钮时滚动面板，不重复打开模板入口。
    @Test
    fun scrollsToRevealCurrentPlaceButtonWhenSummaryIsVisible() {
        val state = plugin.inspect(window(
            node("角落"), node("轨迹"), node("打卡"),
            node("已打卡"), node("3123"), node("个地点")
        ))
        assertEquals(PluginCommandType.SCROLL_CANDIDATES,
            plugin.buildNextAction(state)?.type)
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

    // 验证百度每日额度弹窗进入独立阻断页并生成可恢复证据。
    @Test
    fun recognizesDailyQuotaExhaustedDialog() {
        val window = window(
            node("今天打卡太多地方啦"),
            node("明天再试")
        )

        val state = plugin.inspect(window)
        val command = plugin.buildNextAction(state)
        val evidence = plugin.verifyResult(window)

        assertEquals(PluginPage.DAILY_QUOTA_EXHAUSTED, state.page)
        assertEquals(PluginCommandType.BLOCK, command?.type)
        assertEquals(MapOperationStatus.BLOCKED, evidence.resultStatus)
        assertEquals(
            PluginPage.DAILY_QUOTA_EXHAUSTED.name,
            evidence.page
        )
        assertTrue(evidence.message.contains("今日打卡额度已用尽"))
    }

    // 验证缺少打卡数量语义的普通次日提示不会误停百度队列。
    @Test
    fun ignoresUnrelatedTomorrowHint() {
        val state = plugin.inspect(window(node("活动明天再试")))

        assertEquals(PluginPage.UNKNOWN, state.page)
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
