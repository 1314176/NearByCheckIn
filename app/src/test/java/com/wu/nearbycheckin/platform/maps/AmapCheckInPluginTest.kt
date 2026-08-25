// @Time: 2026/08/16 02:26
// @Author: MrWu
// @File: AmapCheckInPluginTest.kt
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
 * 验证高德 V5 插件页面导航、候选读取、选择和成功证据。
 */
class AmapCheckInPluginTest {

    private val plugin = AmapCheckInPlugin()

    // 验证开始打卡页生成当前真实位置候选。
    @Test
    fun createsCurrentLocationCandidateFromStartPage() {
        val window = window(
            PluginNodeSnapshot(text = "已点亮 163 个角落"),
            PluginNodeSnapshot(
                text = "开始打卡",
                clickable = true,
                fingerprint = "start"
            )
        )

        val candidate = plugin.readCandidates(window).single()

        assertEquals(PluginPage.CHECK_IN_ENTRY, plugin.inspect(window).page)
        assertEquals("当前高德打卡位置", candidate.name)
        assertEquals(
            PluginCommandType.SELECT_CANDIDATE,
            plugin.buildSelectAction(candidate).type
        )
        assertEquals(
            2_000L,
            plugin.buildSelectAction(candidate).repeatAfterMillis
        )
    }

    // 验证开始按钮页保持为入口状态且为提交动作保留两秒确认间隔。
    @Test
    fun treatsStartPageAsCheckInEntry() {
        val state = plugin.inspect(
            window(node("开始打卡", fingerprint = "start"))
        )
        val command = plugin.buildNextAction(state)

        assertEquals(PluginPage.CHECK_IN_ENTRY, state.page)
        assertEquals(PluginCommandType.OPEN_CHECK_IN_ENTRY, command?.type)
        assertEquals(2_000L, command?.repeatAfterMillis)
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

    // 验证高德个人中心通过足迹入口进入足迹页。
    @Test
    fun opensFootprintFromProfilePage() {
        val state = plugin.inspect(
            window(
                node("我的"),
                node("足迹"),
                node("点亮城市"),
                node("走过上海市"),
                node("打卡点"),
                node("220")
            )
        )

        val command = plugin.buildNextAction(state)

        assertEquals(PluginPage.MINE, state.page)
        assertEquals(PluginCommandType.NAVIGATE_FOOTPRINT, command?.type)
        assertTrue(command?.target?.labels?.contains("足迹") == true)
    }

    // 验证高德足迹页优先点击真实的打卡点入口。
    @Test
    fun opensCheckInPointFromFootprintPage() {
        val state = plugin.inspect(
            window(
                PluginNodeSnapshot(text = "足迹"),
                PluginNodeSnapshot(text = "角落"),
                PluginNodeSnapshot(text = "轨迹"),
                PluginNodeSnapshot(text = "打卡点")
            )
        )

        val command = plugin.buildNextAction(state)

        assertEquals(PluginPage.FOOTPRINT, state.page)
        assertEquals(PluginCommandType.OPEN_CHECK_IN_ENTRY, command?.type)
        assertTrue(command?.target?.labels?.contains("打卡点") == true)
    }

    // 验证“选择打卡地址”列表转换为正式高德 Provider 候选。
    @Test
    fun readsVisibleCandidateFromAddressList() {
        val candidate = plugin.readCandidates(
            window(
                node("选择打卡地址", parent = "title"),
                node(
                    "人民公园",
                    parent = "row-1",
                    fingerprint = "place-name"
                ),
                node("人民路 1 号", parent = "row-1"),
                node("120米", parent = "row-1")
            )
        ).single()

        assertEquals("人民公园", candidate.name)
        assertEquals("人民路 1 号", candidate.address)
        assertEquals("120米", candidate.distanceText)
        assertEquals("place-name", candidate.fingerprint)
        assertEquals(
            0L,
            plugin.buildSelectAction(candidate).repeatAfterMillis
        )
    }

    // 验证真实高德节点父级不同也能按距离锚点和纵向位置聚合成候选行。
    @Test
    fun readsCandidatesAcrossDifferentParentsByScreenRow() {
        val candidates = plugin.readCandidates(
            window(
                node("选择打卡地址", parent = "title", top = 120),
                node(
                    "IN健身工作坊(沪太路店)",
                    parent = "name-parent-1",
                    fingerprint = "place-1",
                    top = 300
                ),
                node("199 m", parent = "distance-parent-1", top = 300),
                node(
                    "沪太路1353号龙盛佳苑底商",
                    parent = "address-parent-1",
                    top = 350
                ),
                node(
                    "德洋装潢",
                    parent = "name-parent-2",
                    fingerprint = "place-2",
                    top = 470
                ),
                node("198 m", parent = "distance-parent-2", top = 470),
                node(
                    "沪太支路180号2层海友酒店隔壁",
                    parent = "address-parent-2",
                    top = 520
                )
            )
        )

        assertEquals(candidates.toString(), 2, candidates.size)
        assertEquals("IN健身工作坊(沪太路店)", candidates[0].name)
        assertEquals("沪太路1353号龙盛佳苑底商", candidates[0].address)
        assertEquals("199 m", candidates[0].distanceText)
        assertEquals("德洋装潢", candidates[1].name)
        assertEquals("place-2", candidates[1].fingerprint)
    }

    // 验证“当前位置”提示不会再次被误建为真实地点任务。
    @Test
    fun ignoresCurrentLocationPromptAsCandidate() {
        val candidates = plugin.readCandidates(
            window(
                node("选择打卡地址", parent = "title"),
                node("当前位置：", parent = "location"),
                node("江南门窗厂(阳城路店)", parent = "location")
            )
        )

        assertTrue(candidates.isEmpty())
    }

    // 验证高德列表中的已完成标记被导入永久跳过候选。
    @Test
    fun importsCompletedCandidateMarker() {
        val window = window(
            node("选择打卡地址", parent = "title"),
            node("人民公园", parent = "row-1"),
            node("人民路 1 号", parent = "row-1"),
            node("80米", parent = "row-1"),
            node("已打卡", parent = "row-1")
        )

        val candidate = plugin.readCandidates(window).single()

        assertEquals(PluginPage.CANDIDATE_LIST, plugin.inspect(window).page)
        assertTrue(candidate.completed)
    }

    // 验证高德明确成功结果页生成最终成功证据。
    @Test
    fun verifiesExplicitSuccessPage() {
        val candidate = currentLocationCandidate()
        plugin.buildSelectAction(candidate)

        val evidence = plugin.verifyResult(
            window(
                node("添加回忆"),
                node("31.2304°N"),
                node("121.4737°E"),
                node("2026-08-17"),
                node("收下")
            )
        )

        assertEquals(MapOperationStatus.SUCCESS, evidence.resultStatus)
        assertEquals(PluginPage.SUCCESS.name, evidence.page)
    }

    // 验证现场“地点 220 个”增加可作为可追踪的最终成功证据。
    @Test
    fun verifiesCheckInCountIncrease() {
        plugin.inspect(
            window(
                node("角落"),
                node("164"),
                node("个"),
                node("地点"),
                node("220"),
                node("个"),
                node("打卡", fingerprint = "start")
            )
        )
        plugin.buildSelectAction(currentLocationCandidate())

        val evidence = plugin.verifyResult(
            window(
                node("角落"),
                node("164"),
                node("个"),
                node("地点"),
                node("221"),
                node("个")
            )
        )

        assertEquals(MapOperationStatus.SUCCESS, evidence.resultStatus)
        assertEquals(220, evidence.beforeCheckInCount)
        assertEquals(221, evidence.afterCheckInCount)
    }

    // 验证频率提示在地点数量未增长时不能生成最终成功证据。
    @Test
    fun keepsRateLimitUnknownWithoutCountIncrease() {
        plugin.inspect(
            window(
                node("地点 221个"),
                node("打卡", fingerprint = "start")
            )
        )
        plugin.buildSelectAction(currentLocationCandidate())

        val evidence = plugin.verifyResult(
            window(
                node("地点 221个"),
                node("操作太频繁"),
                node("打卡", fingerprint = "start")
            )
        )

        assertEquals(MapOperationStatus.UNKNOWN, evidence.resultStatus)
        assertEquals(221, evidence.beforeCheckInCount)
        assertEquals(221, evidence.afterCheckInCount)
        assertTrue(evidence.message.contains("等待地点统计增长"))
    }

    // 返回旧式开始打卡页的当前位置候选。
    private fun currentLocationCandidate() = plugin.readCandidates(
        window(node("开始打卡", fingerprint = "start"))
    ).single()

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

    // 创建带父行和稳定指纹的高德测试节点。
    private fun node(
        text: String,
        parent: String = "root",
        fingerprint: String = text,
        top: Int = 0
    ): PluginNodeSnapshot {
        return PluginNodeSnapshot(
            text = text,
            clickable = fingerprint == "place-name" ||
                    fingerprint == "start" ||
                    fingerprint.startsWith("place-"),
            top = top,
            bottom = top + 40,
            parentFingerprint = parent,
            fingerprint = fingerprint
        )
    }
}
