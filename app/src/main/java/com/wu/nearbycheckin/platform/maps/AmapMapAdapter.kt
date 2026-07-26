// @Time: 2026/07/20 18:32
// @Author: MrWu
// @File: AmapMapAdapter.kt
// @Software: Android Studio


@file:Suppress("SpellCheckingInspection")


package com.wu.nearbycheckin.platform.maps


import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

import com.wu.nearbycheckin.core.model.MapProvider


/**
 * 保存高德地图可访问节点的只读摘要。
 *
 * @property text 节点文本。
 * @property description 节点内容描述。
 * @property viewId 节点资源ID。
 * @property bounds 节点屏幕边界。
 */
data class AmapNodeSummary(
    val text: String,
    val description: String,
    val viewId: String,
    val bounds: String
)

/**
 * 保存一次高德地图窗口只读识别结果。
 *
 * @property totalNodeCount 扫描到的节点总数。
 * @property clickableNodeCount 可点击节点数量。
 * @property candidateNodes 可用于后续规则分析的可点击节点摘要。
 */
data class AmapMapUiProfile(
    val totalNodeCount: Int,
    val clickableNodeCount: Int,
    val candidateNodes: List<AmapNodeSummary>
) {

    /**
     * 将候选节点压缩成单行摘要，便于日志和首页显示。
     */
    fun toSummaryText(): String {
        return candidateNodes
            .take(MAX_SUMMARY_NODE_COUNT)
            .joinToString(separator = "；") { node ->
                val label = node.text
                    .ifBlank { node.description }
                    .ifBlank { node.viewId }
                    .ifBlank { "无标签节点" }

                "$label@${node.bounds}"
            }
    }

    private companion object {
        const val MAX_SUMMARY_NODE_COUNT = 5
    }
}

/**
 * 高德地图只读适配器，负责识别窗口节点结构但不执行任何点击。
 */
object AmapMapAdapter {

    /**
     * 扫描高德地图窗口根节点，提取可点击节点摘要。
     *
     * @param rootNode 高德地图窗口根节点。
     * @return 高德地图只读UI画像。
     */
    fun inspect(rootNode: AccessibilityNodeInfo?): AmapMapUiProfile {
        val result = MutableProfile()

        inspectNode(
            node = rootNode,
            depth = 0,
            result = result
        )

        return AmapMapUiProfile(
            totalNodeCount = result.totalNodeCount,
            clickableNodeCount = result.clickableNodeCount,
            candidateNodes = result.candidateNodes
        )
    }

    // 采集当前高德窗口的纯 Kotlin 页面快照。
    fun captureSnapshot(
        rootNode: AccessibilityNodeInfo?,
        appVersion: String
    ): MapUiSnapshot {
        val snapshot = MapUiSnapshotCollector.collect(
            rootNode = rootNode,
            packageName = AmapUiProfile.packageName,
            appVersion = appVersion,
            gestureLabels = if (
                appVersion == AmapUiProfile.supportedAppVersion
            ) {
                MapUiActionLabels.allForProvider(MapProvider.AMAP)
            } else {
                emptyList()
            }
        )
        val checkInButtonTarget = if (
            appVersion == AmapUiProfile.supportedAppVersion
        ) {
            selectCheckInButtonTarget(rootNode).target
        } else {
            null
        }
        val checkInPointTarget = if (
            appVersion == AmapUiProfile.supportedAppVersion
        ) {
            selectCheckInPointTarget(rootNode).target
        } else {
            null
        }
        return if (
            checkInButtonTarget != null &&
            !snapshot.hasLabel("打卡")
        ) {
            snapshot.copy(
                nodes = snapshot.nodes + MapUiNodeSnapshot(
                    text = "打卡",
                    gestureTarget = true
                )
            )
        } else if (
            checkInPointTarget != null &&
            !snapshot.hasLabel("打卡点")
        ) {
            snapshot.copy(
                nodes = snapshot.nodes + MapUiNodeSnapshot(
                    text = "打卡点",
                    gestureTarget = true
                )
            )
        } else {
            snapshot
        }
    }

    // 执行高德 Profile 返回的单个动作。
    fun execute(
        rootNode: AccessibilityNodeInfo?,
        action: MapUiAction,
        appVersion: String,
        gestureTap: (Int, Int) -> Boolean
    ): AccessibilityClickResult {
        val gestureEnabled =
            appVersion == AmapUiProfile.supportedAppVersion
        val semanticResult =
            AccessibilityNodeActions.clickFirstMatchingLabel(
            rootNode = rootNode,
            labels = MapUiActionLabels.forProvider(
                MapProvider.AMAP,
                action
            ),
            gestureTap = if (gestureEnabled) {
                gestureTap
            } else {
                null
            },
            preferGesture = gestureEnabled
        )
        if (
            semanticResult.success ||
            !gestureEnabled
        ) {
            return semanticResult
        }
        val fallbackSelection = when (action) {
            MapUiAction.CLICK_CHECK_IN_POINT ->
                selectCheckInPointTarget(rootNode)
            MapUiAction.CLICK_CHECK_IN ->
                selectCheckInButtonTarget(rootNode)
            else -> return semanticResult
        }
        return MapUiGestureFallbackExecutor.execute(
            selection = fallbackSelection,
            tap = gestureTap
        )
    }

    // 从高德窗口节点树选择当前版本“打卡点”的卡片相对目标。
    private fun selectCheckInPointTarget(
        rootNode: AccessibilityNodeInfo?
    ): MapUiGestureTargetSelection {
        if (rootNode == null) {
            return MapUiGestureTargetSelection(
                rejectionReason = "窗口根节点为空"
            )
        }
        return AmapCheckInPointGesturePolicy.select(
            candidates = collectActionCandidates(rootNode),
            windowBounds = rootNode.toMapUiBounds()
        )
    }

    // 从高德窗口节点树选择当前版本右下角打卡按钮目标。
    private fun selectCheckInButtonTarget(
        rootNode: AccessibilityNodeInfo?
    ): MapUiGestureTargetSelection {
        if (rootNode == null) {
            return MapUiGestureTargetSelection(
                rejectionReason = "窗口根节点为空"
            )
        }
        return AmapCheckInButtonGesturePolicy.select(
            candidates = collectActionCandidates(rootNode),
            windowBounds = rootNode.toMapUiBounds()
        )
    }

    // 收集当前窗口内具备点击动作的候选边界。
    private fun collectActionCandidates(
        rootNode: AccessibilityNodeInfo
    ): List<MapUiGestureCandidate> {
        val result = mutableListOf<MapUiGestureCandidate>()
        collectActionCandidateNodes(
            node = rootNode,
            depth = 0,
            windowBounds = rootNode.toMapUiBounds(),
            result = result
        )
        return result
    }

    // 递归收集当前窗口内具备点击动作的候选边界。
    private fun collectActionCandidateNodes(
        node: AccessibilityNodeInfo?,
        depth: Int,
        windowBounds: MapUiBounds,
        result: MutableList<MapUiGestureCandidate>
    ) {
        if (
            node == null ||
            depth > MAX_NODE_DEPTH ||
            result.size >= MAX_TOTAL_NODE_COUNT
        ) {
            return
        }
        if (
            node.isClickable ||
            node.actionList.any { action ->
                action.id == AccessibilityNodeInfo.ACTION_CLICK
            }
        ) {
            val candidate = MapUiGestureCandidate(
                enabled = node.isEnabled,
                visible = node.isVisibleToUser,
                bounds = node.toMapUiBounds()
            )
            if (
                AmapActionCandidateAdmissionPolicy.accepts(
                    candidate = candidate,
                    windowBounds = windowBounds
                )
            ) {
                result += candidate
            }
        }
        for (childIndex in 0 until node.childCount) {
            collectActionCandidateNodes(
                node = node.getChild(childIndex),
                depth = depth + 1,
                windowBounds = windowBounds,
                result = result
            )
        }
    }

    /**
     * 递归扫描节点树，统计可点击节点并保留候选摘要。
     *
     * @param node 当前节点。
     * @param depth 当前深度。
     * @param result 扫描结果暂存对象。
     */
    private fun inspectNode(
        node: AccessibilityNodeInfo?,
        depth: Int,
        result: MutableProfile
    ) {
        if (
            node == null ||
            depth > MAX_NODE_DEPTH ||
            result.totalNodeCount >= MAX_TOTAL_NODE_COUNT
        ) {
            return
        }

        result.totalNodeCount += 1

        if (node.isClickable) {
            result.clickableNodeCount += 1

            if (result.candidateNodes.size < MAX_CANDIDATE_NODE_COUNT) {
                result.candidateNodes.add(
                    node.toSummary()
                )
            }
        }

        for (childIndex in 0 until node.childCount) {
            inspectNode(
                node = node.getChild(childIndex),
                depth = depth + 1,
                result = result
            )
        }
    }

    /**
     * 将无障碍节点转换为可持久化的摘要。
     */
    private fun AccessibilityNodeInfo.toSummary(): AmapNodeSummary {
        val bounds = Rect().also { rect ->
            getBoundsInScreen(rect)
        }

        return AmapNodeSummary(
            text = text?.toString().orEmpty(),
            description = contentDescription?.toString().orEmpty(),
            viewId = viewIdResourceName.orEmpty(),
            bounds = bounds.toShortString()
        )
    }

    /**
     * 临时保存一次扫描过程中的统计数据。
     */
    private class MutableProfile {
        var totalNodeCount = 0
        var clickableNodeCount = 0
        val candidateNodes = mutableListOf<AmapNodeSummary>()
    }

    private const val MAX_NODE_DEPTH = 10
    private const val MAX_TOTAL_NODE_COUNT = 300
    private const val MAX_CANDIDATE_NODE_COUNT = 20
}
