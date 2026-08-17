// @Time: 2026/08/16 02:14
// @Author: MrWu
// @File: BaiduCheckInPlugin.kt
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
 * 实现百度地图候选列表读取、页面导航、地点选择和结果验证。
 */
class BaiduCheckInPlugin : MapAutomationPlugin {

    override val provider = MapProvider.BAIDU
    override val profileVersion = PROFILE_VERSION
    private var pendingCandidate: ProviderPlaceCandidate? = null
    private var pendingBeforeFingerprint = ""
    private var pendingCommandId = ""
    private var lastObservedCheckInCount: Int? = null
    private var pendingBeforeCheckInCount: Int? = null

    // 按稳定语义识别百度打卡流程页面。
    override fun inspect(window: MapWindowSnapshot): PluginPageState {
        val labels = window.nodes.flatMap(PluginNodeSnapshot::labels)
        val joined = labels.joinToString("|")
        val page = when {
            isDailyQuotaEvidence(joined) ->
                PluginPage.DAILY_QUOTA_EXHAUSTED
            labels.any { label -> BLOCKED_LABELS.any(label::contains) } ->
                PluginPage.BLOCKED
            labels.any { label -> label.contains(SHARE_PANEL_LABEL) } ->
                PluginPage.SHARE_PANEL
            labels.any { label ->
                ALBUM_FOOTPRINT_LABELS.any(label::contains)
            } -> PluginPage.RECOVERY
            labels.any { label -> SUCCESS_LABELS.any(label::contains) } ->
                PluginPage.SUCCESS
            labels.any { label -> COMPLETED_LABELS.any(label::contains) } &&
                    labels.any { label -> DISMISS_LABELS.any(label::contains) } ->
                PluginPage.ALREADY_COMPLETED
            labels.any { label -> CANDIDATE_PAGE_LABELS.any(label::contains) } ->
                PluginPage.CANDIDATE_LIST
            labels.any(::isCheckInActionLabel) ->
                PluginPage.CHECK_IN_ENTRY
            labels.any { label -> label == "打卡点" } -> PluginPage.MINE
            labels.any(BAIDU_CHECK_IN_TAB_LABELS::contains) &&
                    labels.any(TRACK_HOME_LABELS::contains) ->
                PluginPage.FOOTPRINT
            labels.any(FOOTPRINT_LABELS::contains) ->
                PluginPage.FOOTPRINT
            labels.any { label -> label == "我的" } -> PluginPage.HOME
            else -> PluginPage.UNKNOWN
        }
        labels.extractCheckInCount()?.let { count ->
            lastObservedCheckInCount = count
        }
        return PluginPageState(
            page = page,
            profileVersion = profileVersion,
            fingerprint = window.fingerprint(),
            evidence = joined.take(MAX_EVIDENCE_LENGTH),
            blockedReason = when (page) {
                PluginPage.DAILY_QUOTA_EXHAUSTED ->
                    DAILY_QUOTA_LABELS.firstOrNull(joined::contains)
                        ?: DAILY_QUOTA_MESSAGE
                PluginPage.BLOCKED -> labels.firstOrNull { label ->
                    BLOCKED_LABELS.any(label::contains)
                }.orEmpty()
                else -> ""
            }
        )
    }

    // 从可见候选行读取地点名称、地址、距离和 UI 完成状态。
    override fun readCandidates(
        window: MapWindowSnapshot
    ): List<ProviderPlaceCandidate> {
        return window.nodes
            .filter { node -> node.labels().isNotEmpty() }
            .groupBy { node ->
                node.parentFingerprint.ifBlank {
                    "row-${node.top / ROW_BUCKET_HEIGHT}"
                }
            }
            .values
            .mapNotNull { row -> row.toCandidate(window.capturedAtMillis) }
            .distinctBy { candidate -> candidate.toPlaceKey() }
    }

    // 根据当前页面生成下一条确定性导航命令。
    override fun buildNextAction(state: PluginPageState): PluginCommand? {
        val typeAndLabels = when (state.page) {
            PluginPage.HOME ->
                PluginCommandType.NAVIGATE_MINE to listOf("我的")
            PluginPage.MINE ->
                PluginCommandType.NAVIGATE_FOOTPRINT to listOf("打卡点")
            PluginPage.FOOTPRINT ->
                PluginCommandType.OPEN_CHECK_IN_ENTRY to
                        BAIDU_CHECK_IN_TAB_LABELS
            PluginPage.CHECK_IN_ENTRY ->
                PluginCommandType.OPEN_CHECK_IN_ENTRY to
                        CHECK_IN_ACTION_LABELS
            PluginPage.CANDIDATE_LIST ->
                PluginCommandType.READ_CANDIDATES to emptyList()
            PluginPage.SUCCESS,
            PluginPage.ALREADY_COMPLETED ->
                PluginCommandType.DISMISS_RESULT to DISMISS_LABELS
            PluginPage.SHARE_PANEL ->
                PluginCommandType.DISMISS_RESULT to emptyList()
            PluginPage.RECOVERY ->
                PluginCommandType.NAVIGATE_BACK to listOf("返回")
            PluginPage.DAILY_QUOTA_EXHAUSTED,
            PluginPage.BLOCKED ->
                PluginCommandType.BLOCK to emptyList()
            PluginPage.UNKNOWN -> return null
        }
        return newCommand(
            type = typeAndLabels.first,
            labels = typeAndLabels.second,
            viewId = when (state.page) {
                PluginPage.SHARE_PANEL -> SHARE_CLOSE_VIEW_ID
                PluginPage.RECOVERY -> BACK_VIEW_ID
                else -> ""
            },
            evidenceHint = state.evidence
        )
    }

    // 为候选行生成优先按节点指纹定位的选择命令。
    override fun buildSelectAction(
        candidate: ProviderPlaceCandidate
    ): PluginCommand {
        pendingCandidate = candidate
        pendingBeforeFingerprint = candidate.fingerprint
        pendingBeforeCheckInCount = lastObservedCheckInCount
        return newCommand(
            type = PluginCommandType.SELECT_CANDIDATE,
            labels = listOf(candidate.name),
            nodeFingerprint = candidate.fingerprint,
            evidenceHint = "选择候选：${candidate.name}"
        ).also { command ->
            pendingCommandId = command.commandId
        }
    }

    // 依据成功页、已完成标记、阻断提示和页面变化生成结果证据。
    override fun verifyResult(
        window: MapWindowSnapshot
    ): MapOperationEvidence {
        val labels = window.nodes.flatMap(PluginNodeSnapshot::labels)
        val beforeCheckInCount = pendingBeforeCheckInCount
        val afterCheckInCount = labels.extractCheckInCount()
        val state = inspect(window)
        val candidate = pendingCandidate
        val checkInCountIncreased =
            beforeCheckInCount != null &&
                    afterCheckInCount != null &&
                    afterCheckInCount > beforeCheckInCount
        val status = when {
            checkInCountIncreased -> MapOperationStatus.SUCCESS
            state.page == PluginPage.SUCCESS -> when {
                beforeCheckInCount != null &&
                        afterCheckInCount != null &&
                        afterCheckInCount <= beforeCheckInCount ->
                    MapOperationStatus.ALREADY_COMPLETED
                else -> MapOperationStatus.UNKNOWN
            }
            state.page == PluginPage.ALREADY_COMPLETED ->
                MapOperationStatus.ALREADY_COMPLETED
            state.page == PluginPage.DAILY_QUOTA_EXHAUSTED ->
                MapOperationStatus.BLOCKED
            state.page == PluginPage.BLOCKED -> MapOperationStatus.BLOCKED
            else -> MapOperationStatus.UNKNOWN
        }
        return MapOperationEvidence(
            provider = provider,
            taskId = "",
            commandId = pendingCommandId,
            profileVersion = profileVersion,
            page = state.page.name,
            matchedText = candidate?.name.orEmpty(),
            viewId = candidate?.fingerprint.orEmpty(),
            beforeFingerprint = pendingBeforeFingerprint,
            afterFingerprint = state.fingerprint,
            resultStatus = status,
            message = when (status) {
                MapOperationStatus.SUCCESS ->
                    "百度地图地点统计已从${beforeCheckInCount}增加到${afterCheckInCount}"
                MapOperationStatus.ALREADY_COMPLETED ->
                    if (
                        beforeCheckInCount != null &&
                        afterCheckInCount != null
                    ) {
                        "百度地图成功页统计未增加，按已完成地点处理"
                    } else {
                        "百度地图已标记该地点完成"
                    }
                MapOperationStatus.BLOCKED ->
                    if (state.page == PluginPage.DAILY_QUOTA_EXHAUSTED) {
                        "百度地图今日打卡额度已用尽：" +
                                state.blockedReason.ifBlank {
                                    DAILY_QUOTA_MESSAGE
                                }
                    } else {
                        state.blockedReason.ifBlank { "百度地图流程被阻断" }
                    }
                else -> "等待百度地图返回确定结果"
            },
            observedAtMillis = window.capturedAtMillis,
            beforeCheckInCount = beforeCheckInCount,
            afterCheckInCount = afterCheckInCount
        )
    }

    // 将同一候选行的语义节点归并为一个候选。
    private fun List<PluginNodeSnapshot>.toCandidate(
        observedAtMillis: Long
    ): ProviderPlaceCandidate? {
        val semanticNodes = filterNot { node ->
            node.labels().any { label ->
                CHROME_LABELS.any { chrome -> label == chrome }
            }
        }
        val texts = semanticNodes.flatMap { node ->
            listOf(node.text, node.description)
        }.map(String::trim).filter(String::isNotBlank).distinct()
        val distance = texts.firstOrNull { text ->
            DISTANCE_PATTERN.containsMatchIn(text)
        }.orEmpty()
        val completed = texts.any { text ->
            COMPLETED_LABELS.any(text::contains)
        }
        val content = texts.filterNot { text ->
            text == distance || COMPLETED_LABELS.any(text::contains)
        }
        val name = content.firstOrNull { text ->
            text.length in MIN_NAME_LENGTH..MAX_NAME_LENGTH
        }.orEmpty()
        if (name.isBlank() || distance.isBlank()) {
            return null
        }
        val address = content.dropWhile { it != name }.drop(1)
            .firstOrNull().orEmpty()
        val target = semanticNodes.firstOrNull { node ->
            node.labels().any { label ->
                label == name || label.startsWith("$name,") ||
                        label.startsWith("$name，")
            } && node.fingerprint.isNotBlank()
        } ?: semanticNodes.firstOrNull { node ->
            node.clickable && node.fingerprint.isNotBlank() &&
                    node.labels().none { label -> label == distance }
        } ?: semanticNodes.firstOrNull { node ->
            node.fingerprint.isNotBlank()
        }
        val fingerprint = target?.fingerprint.orEmpty()
        return ProviderPlaceCandidate(
            provider = provider,
            name = name,
            address = address,
            distanceText = distance,
            completed = completed,
            fingerprint = fingerprint,
            observedAtMillis = observedAtMillis,
            stableHint = "$name|$address"
        )
    }

    // 创建不绑定 Android 类型的插件命令。
    private fun newCommand(
        type: PluginCommandType,
        labels: List<String>,
        viewId: String = "",
        nodeFingerprint: String = "",
        evidenceHint: String = ""
    ): PluginCommand {
        return PluginCommand(
            commandId = UUID.randomUUID().toString(),
            taskId = "",
            provider = provider,
            type = type,
            target = PluginCommandTarget(
                labels = labels,
                viewId = viewId,
                nodeFingerprint = nodeFingerprint
            ),
            createdAtMillis = System.currentTimeMillis(),
            evidenceHint = evidenceHint
        )
    }

    // 判断标签是否为百度真实打卡动作并排除统计与入口文案。
    private fun isCheckInActionLabel(label: String): Boolean {
        return CHECK_IN_ACTION_LABELS.any { action ->
            label == action || label.startsWith("$action,") ||
                    label.startsWith("$action，")
        }
    }

    // 只把明确包含百度打卡数量语义的次日提示识别为每日额度耗尽。
    private fun isDailyQuotaEvidence(evidence: String): Boolean {
        return DAILY_QUOTA_LABELS.any(evidence::contains) ||
                evidence.contains("打卡") && evidence.contains("明天再试")
    }

    // 从成功页或打卡首页的“已打卡 + 数量 + 个地点”语义序列读取统计数。
    private fun List<String>.extractCheckInCount(): Int? {
        firstNotNullOfOrNull { label ->
            CHECK_IN_COUNT_INLINE_PATTERN.find(label)?.groupValues
                ?.getOrNull(1)?.toIntOrNull()
        }?.let { count ->
            return count
        }
        forEachIndexed { markerIndex, label ->
            val marker = CHECK_IN_COUNT_MARKERS.firstOrNull(label::contains)
                ?: return@forEachIndexed
            val followingLabels = drop(markerIndex + 1).take(4)
            val countIndex = followingLabels.indexOfFirst { following ->
                following.trim().toIntOrNull() != null
            }
            if (countIndex < 0) {
                return@forEachIndexed
            }
            val count = followingLabels[countIndex].trim().toIntOrNull()
                ?: return@forEachIndexed
            val hasUnit = followingLabels.drop(countIndex + 1).take(2)
                .any { following -> following.contains(CHECK_IN_COUNT_UNIT) }
            if (marker == SUCCESS_COUNT_MARKER || hasUnit) {
                return count.takeIf { it >= 0 }
            }
        }
        return null
    }

    private companion object {
        const val PROFILE_VERSION = "baidu-continuous-v5.2.5"
        const val MAX_EVIDENCE_LENGTH = 1_000
        const val ROW_BUCKET_HEIGHT = 120
        const val MIN_NAME_LENGTH = 2
        const val MAX_NAME_LENGTH = 80
        const val SUCCESS_COUNT_MARKER = "年已打卡了"
        const val CHECK_IN_SUMMARY_MARKER = "已打卡"
        const val CHECK_IN_COUNT_UNIT = "个地点"
        const val SHARE_PANEL_LABEL = "请选择分享主题"
        const val SHARE_CLOSE_VIEW_ID =
            "com.baidu.BaiduMap:id/iv_close_share"
        const val BACK_VIEW_ID = "com.baidu.BaiduMap:id/btn_back"
        const val DAILY_QUOTA_MESSAGE = "今天打卡太多地方啦，明天再试"
        val ALBUM_FOOTPRINT_LABELS = listOf(
            "相册足迹",
            "相册足迹读取中"
        )
        val CANDIDATE_PAGE_LABELS = listOf(
            "选择打卡地点",
            "附近打卡点",
            "选择地点"
        )
        val CHECK_IN_ACTION_LABELS = listOf(
            "立即打卡",
            "立刻打卡",
            "当前打卡位置",
            "开始打卡"
        )
        val BAIDU_CHECK_IN_TAB_LABELS = listOf("打卡")
        val TRACK_HOME_LABELS = listOf("角落", "轨迹")
        val FOOTPRINT_LABELS = listOf("足迹", "我的足迹")
        val SUCCESS_LABELS = listOf("打卡成功", "已成功打卡")
        val COMPLETED_LABELS = listOf("已打卡", "已经打卡")
        val DISMISS_LABELS = listOf("完成", "关闭", "返回")
        val CHECK_IN_COUNT_MARKERS = listOf(
            SUCCESS_COUNT_MARKER,
            CHECK_IN_SUMMARY_MARKER
        )
        val CHECK_IN_COUNT_INLINE_PATTERN = Regex(
            "(?:年已打卡了|已打卡)\\s*(\\d+)\\s*个地点"
        )
        val DAILY_QUOTA_LABELS = listOf(
            DAILY_QUOTA_MESSAGE,
            "今天打卡太多地方啦",
            "今日打卡太多地方",
            "今天已打卡太多"
        )
        val BLOCKED_LABELS = listOf(
            "登录后",
            "请先登录",
            "网络异常",
            "服务异常",
            "操作频繁",
            "打卡太频繁"
        )
        val CHROME_LABELS = setOf(
            "选择打卡地点",
            "附近打卡点",
            "完成",
            "关闭",
            "返回",
            "我的",
            "首页"
        )
        val DISTANCE_PATTERN = Regex(
            "(?i)(\\d+(?:\\.\\d+)?\\s*(?:m|米|km|公里))"
        )
    }
}
