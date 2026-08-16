// @Time: 2026/08/16 02:24
// @Author: MrWu
// @File: AmapCheckInPlugin.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.maps


import java.util.UUID

import com.wu.nearbycheckin.core.domain.plugin.MapAutomationPlugin
import com.wu.nearbycheckin.core.domain.plugin.MapWindowSnapshot
import com.wu.nearbycheckin.core.domain.plugin.PluginCommand
import com.wu.nearbycheckin.core.domain.plugin.PluginCommandTarget
import com.wu.nearbycheckin.core.domain.plugin.PluginCommandType
import com.wu.nearbycheckin.core.domain.plugin.PluginNodeSnapshot
import com.wu.nearbycheckin.core.domain.plugin.PluginPage
import com.wu.nearbycheckin.core.domain.plugin.PluginPageState
import com.wu.nearbycheckin.core.model.MapOperationEvidence
import com.wu.nearbycheckin.core.model.MapOperationStatus
import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.core.model.ProviderPlaceCandidate


/**
 * 实现高德“我的-足迹-打卡点-开始打卡”插件闭环。
 */
class AmapCheckInPlugin : MapAutomationPlugin {

    override val provider = MapProvider.AMAP
    override val profileVersion = PROFILE_VERSION
    private var pendingCandidate: ProviderPlaceCandidate? = null
    private var pendingCommandId = ""

    // 按页面语义识别高德流程且不绑定应用版本号。
    override fun inspect(window: MapWindowSnapshot): PluginPageState {
        val labels = window.nodes.flatMap(PluginNodeSnapshot::labels)
        val joined = labels.joinToString("|")
        val page = when {
            labels.any { value -> BLOCKED_LABELS.any(value::contains) } ->
                PluginPage.BLOCKED
            labels.any { value -> SUCCESS_LABELS.any(value::contains) } ->
                PluginPage.SUCCESS
            labels.any { value -> COMPLETED_LABELS.any(value::contains) } ->
                PluginPage.ALREADY_COMPLETED
            labels.any(::isStartActionLabel) ->
                PluginPage.CANDIDATE_LIST
            labels.any { value -> value == "打卡点" } ->
                PluginPage.MINE
            labels.any { value -> value == "足迹" } -> PluginPage.MINE
            labels.any { value -> value == "我的" } -> PluginPage.HOME
            else -> PluginPage.UNKNOWN
        }
        return PluginPageState(
            page = page,
            profileVersion = profileVersion,
            fingerprint = window.fingerprint(),
            evidence = joined.take(MAX_EVIDENCE_LENGTH),
            blockedReason = if (page == PluginPage.BLOCKED) {
                labels.firstOrNull { value ->
                    BLOCKED_LABELS.any(value::contains)
                }.orEmpty()
            } else {
                ""
            }
        )
    }

    // 读取高德可见地点，旧式当前地点页生成一个真实位置候选槽位。
    override fun readCandidates(
        window: MapWindowSnapshot
    ): List<ProviderPlaceCandidate> {
        val rows = window.nodes.groupBy { node ->
            node.parentFingerprint.ifBlank { "row-${node.top / 120}" }
        }.values.mapNotNull { row ->
            val texts = row.flatMap { node ->
                listOf(node.text, node.description)
            }.map(String::trim).filter(String::isNotBlank).distinct()
            val distance = texts.firstOrNull(DISTANCE_PATTERN::containsMatchIn)
                ?: return@mapNotNull null
            val name = texts.firstOrNull { text ->
                text != distance && text.length in 2..80
            } ?: return@mapNotNull null
            val address = texts.firstOrNull { text ->
                text != name && text != distance
            }.orEmpty()
            val target = row.firstOrNull { node ->
                node.clickable && node.fingerprint.isNotBlank()
            }
            ProviderPlaceCandidate(
                provider = provider,
                name = name,
                address = address,
                distanceText = distance,
                completed = texts.any { text ->
                    COMPLETED_LABELS.any(text::contains)
                },
                fingerprint = target?.fingerprint.orEmpty(),
                observedAtMillis = window.capturedAtMillis,
                stableHint = "$name|$address"
            )
        }
        if (rows.isNotEmpty()) {
            return rows.distinctBy { candidate -> candidate.toPlaceKey() }
        }
        val startNode = window.nodes.firstOrNull { node ->
            node.labels().any(::isStartActionLabel)
        } ?: return emptyList()
        return listOf(
            ProviderPlaceCandidate(
                provider = provider,
                name = "当前高德打卡位置",
                address = "由设备真实位置生成",
                distanceText = "0米",
                completed = false,
                fingerprint = startNode.fingerprint,
                observedAtMillis = window.capturedAtMillis,
                stableHint = CURRENT_LOCATION_HINT
            )
        )
    }

    // 根据高德页面生成导航、读取或关闭命令。
    override fun buildNextAction(state: PluginPageState): PluginCommand? {
        val action = when (state.page) {
            PluginPage.HOME ->
                PluginCommandType.NAVIGATE_MINE to listOf("我的")
            PluginPage.MINE ->
                PluginCommandType.OPEN_CHECK_IN_ENTRY to
                        CHECK_IN_POINT_LABELS
            PluginPage.FOOTPRINT ->
                PluginCommandType.OPEN_CHECK_IN_ENTRY to
                        CHECK_IN_POINT_LABELS
            PluginPage.CHECK_IN_ENTRY ->
                PluginCommandType.OPEN_CHECK_IN_ENTRY to START_LABELS
            PluginPage.CANDIDATE_LIST ->
                PluginCommandType.READ_CANDIDATES to emptyList()
            PluginPage.SUCCESS,
            PluginPage.ALREADY_COMPLETED ->
                PluginCommandType.DISMISS_RESULT to DISMISS_LABELS
            PluginPage.SHARE_PANEL -> return null
            PluginPage.RECOVERY -> return null
            PluginPage.BLOCKED -> PluginCommandType.BLOCK to emptyList()
            PluginPage.UNKNOWN -> return null
        }
        return newCommand(action.first, action.second, state.evidence)
    }

    // 为高德候选或当前地点生成开始打卡命令。
    override fun buildSelectAction(
        candidate: ProviderPlaceCandidate
    ): PluginCommand {
        pendingCandidate = candidate
        return newCommand(
            type = PluginCommandType.SELECT_CANDIDATE,
            labels = if (candidate.stableHint == CURRENT_LOCATION_HINT) {
                START_LABELS
            } else {
                listOf(candidate.name)
            },
            evidence = "选择高德候选：${candidate.name}",
            fingerprint = candidate.fingerprint
        ).also { command -> pendingCommandId = command.commandId }
    }

    // 验证高德成功、已完成、频率提示或阻断结果。
    override fun verifyResult(
        window: MapWindowSnapshot
    ): MapOperationEvidence {
        val state = inspect(window)
        val rateLimited = state.evidence.contains("打卡太频繁") ||
                state.evidence.contains("操作频繁")
        val status = when {
            state.page == PluginPage.SUCCESS -> MapOperationStatus.SUCCESS
            state.page == PluginPage.ALREADY_COMPLETED ->
                MapOperationStatus.ALREADY_COMPLETED
            state.page == PluginPage.BLOCKED && !rateLimited ->
                MapOperationStatus.BLOCKED
            else -> MapOperationStatus.UNKNOWN
        }
        return MapOperationEvidence(
            provider = provider,
            taskId = "",
            commandId = pendingCommandId,
            profileVersion = profileVersion,
            page = state.page.name,
            matchedText = pendingCandidate?.name.orEmpty(),
            viewId = pendingCandidate?.fingerprint.orEmpty(),
            beforeFingerprint = pendingCandidate?.fingerprint.orEmpty(),
            afterFingerprint = state.fingerprint,
            resultStatus = status,
            message = when {
                rateLimited -> "高德等待两秒后执行第二次打卡"
                status == MapOperationStatus.SUCCESS -> "高德地图打卡成功"
                status == MapOperationStatus.ALREADY_COMPLETED ->
                    "高德地图已标记当前地点完成"
                status == MapOperationStatus.BLOCKED ->
                    state.blockedReason.ifBlank { "高德地图流程被阻断" }
                else -> "等待高德地图返回确定结果"
            },
            observedAtMillis = window.capturedAtMillis
        )
    }

    // 创建高德插件原子命令。
    private fun newCommand(
        type: PluginCommandType,
        labels: List<String>,
        evidence: String,
        fingerprint: String = ""
    ): PluginCommand {
        return PluginCommand(
            commandId = UUID.randomUUID().toString(),
            taskId = "",
            provider = provider,
            type = type,
            target = PluginCommandTarget(
                labels = labels,
                nodeFingerprint = fingerprint
            ),
            createdAtMillis = System.currentTimeMillis(),
            evidenceHint = evidence
        )
    }

    // 判断标签是否为真实开始按钮并排除“打卡点”等入口文案。
    private fun isStartActionLabel(value: String): Boolean {
        return value == "打卡" || value.contains("开始打卡")
    }

    private companion object {
        const val PROFILE_VERSION = "amap-continuous-v5.2"
        const val CURRENT_LOCATION_HINT = "device-real-location"
        const val MAX_EVIDENCE_LENGTH = 1_000
        val START_LABELS = listOf("开始打卡", "打卡")
        val CHECK_IN_POINT_LABELS = listOf("打卡点", "地点")
        val SUCCESS_LABELS = listOf("打卡成功", "添加回忆")
        val COMPLETED_LABELS = listOf("当前地点已打卡", "已打卡")
        val DISMISS_LABELS = listOf("收下", "完成", "关闭")
        val BLOCKED_LABELS = listOf(
            "请登录后继续",
            "验证码",
            "安全验证",
            "风险验证",
            "需要授权",
            "打卡太频繁",
            "操作频繁"
        )
        val DISTANCE_PATTERN = Regex(
            "(?i)(\\d+(?:\\.\\d+)?\\s*(?:m|米|km|公里))"
        )
    }
}
