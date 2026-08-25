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
    private var pendingBeforeFingerprint = ""
    private var pendingCommandId = ""
    private var lastObservedCheckInCount: Int? = null
    private var pendingBeforeCheckInCount: Int? = null

    // 按页面语义识别高德流程且不绑定应用版本号。
    override fun inspect(window: MapWindowSnapshot): PluginPageState {
        val labels = window.nodes.flatMap(PluginNodeSnapshot::labels)
        val joined = labels.joinToString("|")
        val page = when {
            labels.any { value -> BLOCKED_LABELS.any(value::contains) } ->
                PluginPage.BLOCKED
            isSuccessPage(labels, joined) ->
                PluginPage.SUCCESS
            labels.any { value ->
                CANDIDATE_PAGE_LABELS.any(value::contains)
            } -> PluginPage.CANDIDATE_LIST
            labels.any(::isStartActionLabel) ->
                PluginPage.CHECK_IN_ENTRY
            isAlreadyCompletedPage(labels) ->
                PluginPage.ALREADY_COMPLETED
            labels.any { value ->
                CHECK_IN_ENTRY_LABELS.any(value::contains)
            } -> PluginPage.CHECK_IN_ENTRY
            isMineProfilePage(labels) -> PluginPage.MINE
            labels.any { value ->
                CHECK_IN_POINT_LABELS.any { label ->
                    value == label || value.startsWith("$label,") ||
                            value.startsWith("$label，")
                }
            } -> PluginPage.FOOTPRINT
            labels.any { value -> FOOTPRINT_LABELS.any(value::contains) } ->
                PluginPage.MINE
            labels.any { value -> value == "我的" } -> PluginPage.HOME
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
            blockedReason = if (page == PluginPage.BLOCKED) {
                labels.firstOrNull { value ->
                    BLOCKED_LABELS.any(value::contains)
                }.orEmpty()
            } else {
                ""
            }
        )
    }

    // 读取高德可见地点，旧式开始打卡页生成一个真实位置候选槽位。
    override fun readCandidates(
        window: MapWindowSnapshot
    ): List<ProviderPlaceCandidate> {
        val semanticNodes = window.nodes.filter { node ->
            node.labels().isNotEmpty()
        }
        val rows = semanticNodes
            .candidateRows()
            .mapNotNull { row ->
                row.toCandidate(window.capturedAtMillis)
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
        if (state.page == PluginPage.CHECK_IN_ENTRY) {
            return newCommand(
                type = PluginCommandType.OPEN_CHECK_IN_ENTRY,
                labels = START_LABELS,
                evidence = state.evidence,
                repeatAfterMillis = SECOND_TAP_DELAY_MILLIS
            )
        }
        val action = when (state.page) {
            PluginPage.HOME ->
                PluginCommandType.NAVIGATE_MINE to listOf("我的")
            PluginPage.MINE ->
                PluginCommandType.NAVIGATE_FOOTPRINT to FOOTPRINT_LABELS
            PluginPage.FOOTPRINT ->
                PluginCommandType.OPEN_CHECK_IN_ENTRY to
                        CHECK_IN_POINT_LABELS
            PluginPage.CHECK_IN_ENTRY -> return null
            PluginPage.CANDIDATE_LIST ->
                PluginCommandType.READ_CANDIDATES to emptyList()
            PluginPage.SUCCESS,
            PluginPage.ALREADY_COMPLETED ->
                PluginCommandType.DISMISS_RESULT to DISMISS_LABELS
            PluginPage.SHARE_PANEL -> return null
            PluginPage.RECOVERY -> return null
            PluginPage.DAILY_QUOTA_EXHAUSTED,
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
        pendingBeforeFingerprint = candidate.fingerprint
        pendingBeforeCheckInCount = lastObservedCheckInCount
        return newCommand(
            type = PluginCommandType.SELECT_CANDIDATE,
            labels = if (candidate.stableHint == CURRENT_LOCATION_HINT) {
                START_LABELS
            } else {
                listOf(candidate.name)
            },
            evidence = "选择高德候选：${candidate.name}",
            fingerprint = candidate.fingerprint,
            repeatAfterMillis = if (
                candidate.stableHint == CURRENT_LOCATION_HINT
            ) {
                SECOND_TAP_DELAY_MILLIS
            } else {
                0L
            }
        ).also { command -> pendingCommandId = command.commandId }
    }

    // 仅用地点数量增长或明确结果页确认高德成功，频率提示只保留为诊断信息。
    override fun verifyResult(
        window: MapWindowSnapshot
    ): MapOperationEvidence {
        val labels = window.nodes.flatMap(PluginNodeSnapshot::labels)
        val beforeCheckInCount = pendingBeforeCheckInCount
        val afterCheckInCount = labels.extractCheckInCount()
        val state = inspect(window)
        val rateLimited = RATE_LIMIT_LABELS.any(state.evidence::contains)
        val checkInCountIncreased = beforeCheckInCount != null &&
                afterCheckInCount != null &&
                afterCheckInCount > beforeCheckInCount
        val status = when {
            checkInCountIncreased -> MapOperationStatus.SUCCESS
            state.page == PluginPage.SUCCESS -> MapOperationStatus.SUCCESS
            state.page == PluginPage.ALREADY_COMPLETED ->
                MapOperationStatus.ALREADY_COMPLETED
            state.page == PluginPage.DAILY_QUOTA_EXHAUSTED ->
                MapOperationStatus.BLOCKED
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
            beforeFingerprint = pendingBeforeFingerprint,
            afterFingerprint = state.fingerprint,
            resultStatus = status,
            message = when {
                checkInCountIncreased ->
                    "高德地图地点统计已从${beforeCheckInCount}增加到${afterCheckInCount}"
                status == MapOperationStatus.SUCCESS ->
                    "高德地图返回明确打卡成功证据"
                status == MapOperationStatus.ALREADY_COMPLETED ->
                    "高德地图已标记当前地点完成"
                status == MapOperationStatus.BLOCKED ->
                    state.blockedReason.ifBlank { "高德地图流程被阻断" }
                rateLimited ->
                    "高德地图检测到频率提示，等待地点统计增长或明确成功页"
                else -> "等待高德地图返回确定结果"
            },
            observedAtMillis = window.capturedAtMillis,
            beforeCheckInCount = beforeCheckInCount,
            afterCheckInCount = afterCheckInCount
        )
    }

    // 将同一高德候选行的名称、地址、距离和完成标记归并为候选。
    private fun List<PluginNodeSnapshot>.toCandidate(
        observedAtMillis: Long
    ): ProviderPlaceCandidate? {
        val semanticNodes = filterNot { node ->
            node.labels().any(::isChromeLabel)
        }
        val texts = semanticNodes.flatMap { node ->
            listOf(node.text, node.description)
        }.map(String::trim).filter(String::isNotBlank).distinct()
        val distance = texts.firstOrNull(DISTANCE_PATTERN::containsMatchIn)
            .orEmpty()
        val completed = texts.any { text ->
            COMPLETED_LABELS.any(text::contains)
        }
        val content = texts.filterNot { text ->
            text == distance ||
                    COMPLETED_LABELS.any(text::contains) ||
                    isChromeLabel(text)
        }
        val name = content.firstOrNull(::isCandidateName).orEmpty()
        if (name.isBlank()) {
            return null
        }
        val address = content.dropWhile { text -> text != name }
            .drop(1)
            .firstOrNull(::isCandidateAddress)
            .orEmpty()
        if (distance.isBlank() && address.isBlank()) {
            return null
        }
        val target = semanticNodes.firstOrNull { node ->
            node.labels().any { label ->
                label == name || label.startsWith("$name,") ||
                        label.startsWith("$name，")
            } && node.fingerprint.isNotBlank()
        } ?: semanticNodes.firstOrNull { node ->
            node.clickable && node.fingerprint.isNotBlank()
        } ?: semanticNodes.firstOrNull { node ->
            node.fingerprint.isNotBlank()
        }
        return ProviderPlaceCandidate(
            provider = provider,
            name = name,
            address = address,
            distanceText = distance,
            completed = completed,
            fingerprint = target?.fingerprint.orEmpty(),
            observedAtMillis = observedAtMillis,
            stableHint = "$name|$address"
        )
    }

    // 优先按距离锚点和纵向邻近关系聚合真实列表行，再兼容具有共同父节点的旧布局。
    private fun List<PluginNodeSnapshot>.candidateRows():
            Collection<List<PluginNodeSnapshot>> {
        val distanceAnchors = filter { node ->
            node.labels().any { label ->
                DISTANCE_ONLY_PATTERN.matches(label.trim())
            }
        }.sortedBy { node -> node.verticalCenter() }
        if (distanceAnchors.isEmpty()) {
            return groupBy { node -> node.candidateRowKey() }.values
        }
        return distanceAnchors.map { anchor ->
            filter { node ->
                val nearestAnchor = distanceAnchors.minByOrNull { candidate ->
                    kotlin.math.abs(
                        node.verticalCenter() - candidate.verticalCenter()
                    )
                }
                nearestAnchor === anchor &&
                        kotlin.math.abs(
                            node.verticalCenter() - anchor.verticalCenter()
                        ) <= MAX_ROW_VERTICAL_DISTANCE
            }
        }
    }

    // 创建高德插件原子命令。
    private fun newCommand(
        type: PluginCommandType,
        labels: List<String>,
        evidence: String,
        fingerprint: String = "",
        repeatAfterMillis: Long = 0L
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
            repeatAfterMillis = repeatAfterMillis,
            evidenceHint = evidence
        )
    }

    // 判断标签是否为真实开始按钮并排除“打卡点”等入口文案。
    private fun isStartActionLabel(value: String): Boolean {
        return value == "打卡" || value.contains("开始打卡")
    }

    // 识别成功文本或具有关闭动作及经纬度日期证据的“添加回忆”结果页。
    private fun isSuccessPage(
        labels: List<String>,
        evidence: String
    ): Boolean {
        if (labels.any { label -> SUCCESS_LABELS.any(label::contains) }) {
            return true
        }
        return labels.any { label -> label.contains(ADD_MEMORY_LABEL) } &&
                (
                        labels.any { label ->
                            DISMISS_LABELS.any(label::contains)
                        } ||
                                AMAP_LATITUDE_PATTERN.containsMatchIn(evidence) &&
                                AMAP_LONGITUDE_PATTERN.containsMatchIn(evidence) &&
                                AMAP_DATE_PATTERN.containsMatchIn(evidence)
                        )
    }

    // 仅把当前地点完成结果识别为终态，避免累计“已点亮数量”遮蔽开始按钮。
    private fun isAlreadyCompletedPage(labels: List<String>): Boolean {
        return labels.any { label ->
            CURRENT_PLACE_COMPLETED_LABELS.any(label::contains)
        } || labels.any { label ->
            COMPLETED_LABELS.any(label::contains)
        } && labels.any { label ->
            DISMISS_LABELS.any(label::contains)
        }
    }

    // 同时出现“我的”和“足迹”时优先识别个人中心，避免主页统计中的“打卡点”被误判为足迹页。
    private fun isMineProfilePage(labels: List<String>): Boolean {
        return labels.any { label -> label == "我的" } &&
                labels.any { value ->
                    FOOTPRINT_LABELS.any { label ->
                        value == label || value.startsWith("$label,") ||
                                value.startsWith("$label，")
                    }
                }
    }

    // 判断标签是否属于候选页标题、导航栏或动作按钮而不是地点内容。
    private fun isChromeLabel(value: String): Boolean {
        return CHROME_LABELS.any { chrome ->
            value == chrome || value.startsWith("$chrome,") ||
                    value.startsWith("$chrome，")
        }
    }

    // 判断候选名称是否为可持久化地点名而不是数字或地址。
    private fun isCandidateName(value: String): Boolean {
        return value.length in MIN_NAME_LENGTH..MAX_NAME_LENGTH &&
                value.any(Char::isLetter) &&
                !DISTANCE_PATTERN.containsMatchIn(value) &&
                value.trim().trimEnd(':', '：') !in
                CURRENT_LOCATION_LABELS &&
                !isLikelyStandaloneAddress(value)
    }

    // 仅排除同时含地址标记和数字的独立地址，允许店名中出现道路名称。
    private fun isLikelyStandaloneAddress(value: String): Boolean {
        return value.any(Char::isDigit) && isCandidateAddress(value)
    }

    // 判断文本是否具备常见地址语义。
    private fun isCandidateAddress(value: String): Boolean {
        return ADDRESS_MARKERS.any(value::contains)
    }

    // 优先读取高德“地点”累计数量，再兼容旧版已打卡和角落文案。
    private fun List<String>.extractCheckInCount(): Int? {
        firstNotNullOfOrNull { label ->
            LOCATION_COUNT_INLINE_PATTERN.find(label)?.groupValues
                ?.drop(1)
                ?.firstNotNullOfOrNull(String::toIntOrNull)
        }?.let { count -> return count }
        for (marker in CHECK_IN_COUNT_MARKERS) {
            forEachIndexed { index, label ->
                if (label != marker) {
                    return@forEachIndexed
                }
                drop(index + 1).take(4)
                    .firstNotNullOfOrNull { following ->
                        following.trim().toIntOrNull()
                    }?.let { count ->
                        return count.takeIf { it >= 0 }
                    }
            }
        }
        return firstNotNullOfOrNull { label ->
            FALLBACK_COUNT_INLINE_PATTERN.find(label)?.groupValues
                ?.getOrNull(1)?.toIntOrNull()
        }
    }

    // 为缺少稳定父节点的快照生成有限高度的候选行键。
    private fun PluginNodeSnapshot.candidateRowKey(): String {
        return parentFingerprint.takeIf { parent ->
            parent.isNotBlank() && parent != "root"
        } ?: "row-${((top + bottom) / 2) / ROW_BUCKET_HEIGHT}"
    }

    // 返回节点纵向中心，兼容个别窗口变换后上下边界顺序异常。
    private fun PluginNodeSnapshot.verticalCenter(): Int {
        return (minOf(top, bottom) + maxOf(top, bottom)) / 2
    }

    private companion object {
        const val PROFILE_VERSION = "amap-continuous-v5.2.5"
        const val CURRENT_LOCATION_HINT = "device-real-location"
        const val MAX_EVIDENCE_LENGTH = 1_000
        const val SECOND_TAP_DELAY_MILLIS = 2_000L
        const val ROW_BUCKET_HEIGHT = 120
        const val MAX_ROW_VERTICAL_DISTANCE = 120
        const val MIN_NAME_LENGTH = 2
        const val MAX_NAME_LENGTH = 80
        const val ADD_MEMORY_LABEL = "添加回忆"
        val START_LABELS = listOf("开始打卡", "打卡")
        val CHECK_IN_POINT_LABELS = listOf("打卡点", "地点")
        val FOOTPRINT_LABELS = listOf("足迹", "我的足迹")
        val CHECK_IN_ENTRY_LABELS = listOf("地点打卡", "打卡位置")
        val CANDIDATE_PAGE_LABELS = listOf(
            "选择打卡地址",
            "选择打卡地点",
            "附近打卡地点"
        )
        val SUCCESS_LABELS = listOf("打卡成功", "签到成功")
        val COMPLETED_LABELS = listOf(
            "当前地点已打卡",
            "已打卡",
            "已完成",
            "已点亮"
        )
        val CURRENT_PLACE_COMPLETED_LABELS = listOf(
            "当前地点已打卡",
            "当前地点已完成",
            "这里已打卡"
        )
        val DISMISS_LABELS = listOf("收下", "完成", "关闭")
        val RATE_LIMIT_LABELS = listOf(
            "打卡太频繁",
            "操作太频繁",
            "操作频繁"
        )
        val BLOCKED_LABELS = listOf(
            "请登录后继续",
            "验证码",
            "安全验证",
            "风险验证",
            "需要授权",
            "打卡太频繁",
            "操作太频繁",
            "操作频繁"
        )
        val CURRENT_LOCATION_LABELS = setOf(
            "当前位置",
            "当前位置："
        )
        val CHROME_LABELS = setOf(
            "选择打卡地址",
            "选择打卡地点",
            "附近打卡地点",
            "开始打卡",
            "打卡",
            "打卡点",
            "地点",
            "足迹",
            "我的足迹",
            "我的",
            "完成",
            "关闭",
            "返回",
            "收下"
        ) + CURRENT_LOCATION_LABELS
        val ADDRESS_MARKERS = listOf(
            "省",
            "市",
            "区",
            "县",
            "镇",
            "街",
            "路",
            "道",
            "号",
            "村"
        )
        val CHECK_IN_COUNT_MARKERS = listOf(
            "已打卡",
            "打卡点",
            "地点",
            "已点亮",
            "角落"
        )
        val DISTANCE_PATTERN = Regex(
            "(?i)(\\d+(?:\\.\\d+)?\\s*(?:m|米|km|公里))"
        )
        val DISTANCE_ONLY_PATTERN = Regex(
            "(?i)^\\d+(?:\\.\\d+)?\\s*(?:m|米|km|公里)$"
        )
        val LOCATION_COUNT_INLINE_PATTERN = Regex(
            "(?:已打卡|打卡点|地点)\\D{0,8}(\\d+)\\s*(?:个)?(?:地点|打卡点)?|" +
                    "走过\\s*(\\d+)\\s*个(?:地方|地点)"
        )
        val FALLBACK_COUNT_INLINE_PATTERN = Regex(
            "(?:已点亮|角落)\\D{0,8}(\\d+)\\s*(?:个)?(?:角落)?"
        )
        val AMAP_LATITUDE_PATTERN = Regex("\\d{1,2}(?:\\.\\d+)?°N")
        val AMAP_LONGITUDE_PATTERN = Regex("\\d{1,3}(?:\\.\\d+)?°E")
        val AMAP_DATE_PATTERN = Regex("\\d{4}[./-]\\d{1,2}[./-]\\d{1,2}")
    }
}
