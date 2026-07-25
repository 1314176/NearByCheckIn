// @Time: 2026/07/20 18:32
// @Author: MrWu
// @File: BaiduMapAdapter.kt
// @Software: Android Studio


@file:Suppress("SpellCheckingInspection")


package com.wu.nearbycheckin.platform.maps


import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

import com.wu.nearbycheckin.core.model.MapProvider


/**
 * 保存百度地图可访问节点的只读摘要。
 *
 * @property text 节点文本。
 * @property description 节点内容描述。
 * @property viewId 节点资源ID。
 * @property bounds 节点屏幕边界。
 */
data class BaiduNodeSummary(
    val text: String,
    val description: String,
    val viewId: String,
    val bounds: String
)

/**
 * 保存一次百度地图窗口只读识别结果。
 *
 * @property totalNodeCount 扫描到的节点总数。
 * @property clickableNodeCount 可点击节点数量。
 * @property candidateNodes 可用于后续规则分析的可点击节点摘要。
 */
data class BaiduMapUiProfile(
    val totalNodeCount: Int,
    val clickableNodeCount: Int,
    val candidateNodes: List<BaiduNodeSummary>
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
 * 百度地图只读适配器，负责识别窗口节点结构但不执行任何点击。
 */
object BaiduMapAdapter {

    /**
     * 扫描百度地图窗口根节点，提取可点击节点摘要。
     *
     * @param rootNode 百度地图窗口根节点。
     * @return 百度地图只读UI画像。
     */
    fun inspect(rootNode: AccessibilityNodeInfo?): BaiduMapUiProfile {
        val result = MutableProfile()

        inspectNode(
            node = rootNode,
            depth = 0,
            result = result
        )

        return BaiduMapUiProfile(
            totalNodeCount = result.totalNodeCount,
            clickableNodeCount = result.clickableNodeCount,
            candidateNodes = result.candidateNodes
        )
    }

    // 采集当前百度窗口的纯 Kotlin 页面快照。
    fun captureSnapshot(
        rootNode: AccessibilityNodeInfo?,
        appVersion: String
    ): MapUiSnapshot {
        return MapUiSnapshotCollector.collect(
            rootNode = rootNode,
            packageName = BaiduUiProfile.packageName,
            appVersion = appVersion
        )
    }

    // 执行百度 Profile 返回的单个动作。
    fun execute(
        rootNode: AccessibilityNodeInfo?,
        action: MapUiAction
    ): AccessibilityClickResult {
        return AccessibilityNodeActions.clickFirstMatchingLabel(
            rootNode = rootNode,
            labels = MapUiActionLabels.forProvider(
                MapProvider.BAIDU,
                action
            )
        )
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
    private fun AccessibilityNodeInfo.toSummary(): BaiduNodeSummary {
        val bounds = Rect().also { rect ->
            getBoundsInScreen(rect)
        }

        return BaiduNodeSummary(
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
        val candidateNodes = mutableListOf<BaiduNodeSummary>()
    }

    private const val MAX_NODE_DEPTH = 10
    private const val MAX_TOTAL_NODE_COUNT = 300
    private const val MAX_CANDIDATE_NODE_COUNT = 20
}
