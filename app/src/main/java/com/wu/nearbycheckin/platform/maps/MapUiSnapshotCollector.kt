// @Time: 2026/07/26 04:21
// @Author: MrWu
// @File: MapUiSnapshotCollector.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.maps

import android.view.accessibility.AccessibilityNodeInfo

object MapUiSnapshotCollector {

    // 把 Android 节点树转换为不含 Android 类型的只读快照。
    fun collect(
        rootNode: AccessibilityNodeInfo?,
        packageName: String,
        appVersion: String,
        gestureLabels: List<String> = emptyList()
    ): MapUiSnapshot {
        val nodes = mutableListOf<CollectedNode>()
        collectNodes(rootNode, depth = 0, result = nodes)
        val gestureNodeIndexes = gestureNodeIndexes(
            nodes = nodes,
            labels = gestureLabels,
            windowBounds = rootNode?.toMapUiBounds()
        )
        return MapUiSnapshot(
            packageName = packageName,
            appVersion = appVersion,
            nodes = nodes.mapIndexed { index, node ->
                node.snapshot.copy(
                    gestureTarget = index in gestureNodeIndexes
                )
            }
        )
    }

    // 只读汇总指定标签节点的直接动作能力和最近可执行祖先深度。
    internal fun collectLabelDiagnostics(
        rootNode: AccessibilityNodeInfo?,
        targets: List<MapUiLabelDiagnosticTarget>
    ): List<MapUiLabelDiagnostic> {
        val accumulators = targets.associateWith {
            MutableLabelDiagnostic()
        }
        collectLabelDiagnosticNodes(
            node = rootNode,
            depth = 0,
            accumulators = accumulators,
            nodeCounter = MutableNodeCounter()
        )
        return targets.map { target ->
            val accumulator = requireNotNull(accumulators[target])
            MapUiLabelDiagnostic(
                key = target.key,
                present = accumulator.matchedNodeCount > 0,
                matchedNodeCount = accumulator.matchedNodeCount,
                directClickable = accumulator.directClickable,
                directClickAction = accumulator.directClickAction,
                nearestExecutableAncestorDepth =
                    accumulator.nearestExecutableAncestorDepth
            )
        }
    }

    // 递归采集页面节点并限制遍历深度和总量。
    private fun collectNodes(
        node: AccessibilityNodeInfo?,
        depth: Int,
        result: MutableList<CollectedNode>
    ) {
        if (
            node == null ||
            depth > MAX_DEPTH ||
            result.size >= MAX_NODE_COUNT
        ) {
            return
        }

        result += CollectedNode(
            snapshot = MapUiNodeSnapshot(
                text = node.text?.toString().orEmpty(),
                description = node.contentDescription?.toString().orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                clickable = node.hasClickableAncestor()
            ),
            gestureCandidate = MapUiGestureCandidate(
                enabled = node.isEnabled,
                visible = node.isVisibleToUser,
                bounds = node.toMapUiBounds()
            )
        )

        for (childIndex in 0 until node.childCount) {
            collectNodes(
                node = node.getChild(childIndex),
                depth = depth + 1,
                result = result
            )
        }
    }

    // 返回满足唯一标签与安全边界规则的节点索引。
    private fun gestureNodeIndexes(
        nodes: List<CollectedNode>,
        labels: List<String>,
        windowBounds: MapUiBounds?
    ): Set<Int> {
        if (labels.isEmpty() || windowBounds == null) {
            return emptySet()
        }
        return labels.flatMap { label ->
            val matchingIndexes = nodes.withIndex()
                .filter { indexedNode ->
                    indexedNode.value.snapshot.labels().any { value ->
                        value.matchesUiLabel(label)
                    }
                }
                .map { indexedNode -> indexedNode.index }
            val selection = MapUiGestureTargetPolicy.select(
                candidates = matchingIndexes.map { index ->
                    nodes[index].gestureCandidate
                },
                windowBounds = windowBounds
            )
            if (selection.target != null) {
                matchingIndexes
            } else {
                emptyList()
            }
        }.toSet()
    }

    // 在快照采集边界内汇总目标标签且不保留原始节点内容。
    private fun collectLabelDiagnosticNodes(
        node: AccessibilityNodeInfo?,
        depth: Int,
        accumulators:
            Map<MapUiLabelDiagnosticTarget, MutableLabelDiagnostic>,
        nodeCounter: MutableNodeCounter
    ) {
        if (
            node == null ||
            depth > MAX_DEPTH ||
            nodeCounter.count >= MAX_NODE_COUNT
        ) {
            return
        }
        nodeCounter.count += 1

        val labels = listOf(
            node.text?.toString().orEmpty().trim(),
            node.contentDescription?.toString().orEmpty().trim(),
            node.viewIdResourceName.orEmpty().trim()
        ).filter(String::isNotBlank)
        accumulators.forEach { (target, accumulator) ->
            if (
                labels.any { value ->
                    value.matchesUiLabel(target.label)
                }
            ) {
                accumulator.record(node)
            }
        }

        for (childIndex in 0 until node.childCount) {
            collectLabelDiagnosticNodes(
                node = node.getChild(childIndex),
                depth = depth + 1,
                accumulators = accumulators,
                nodeCounter = nodeCounter
            )
        }
    }

    // 按实际执行规则判断当前节点或祖先是否可点击。
    private fun AccessibilityNodeInfo.hasClickableAncestor(): Boolean {
        return BoundedAncestorFinder.findFirst(
            startNode = this,
            parentOf = { node -> node.parent },
            matches = { node ->
                node.isEnabled &&
                        (
                                node.isClickable ||
                                        node.actionList.any { action ->
                                            action.id ==
                                                    AccessibilityNodeInfo.ACTION_CLICK
                                        }
                                )
            }
        ) != null
    }

    // 返回当前节点起二十层内最近的可执行节点深度。
    private fun AccessibilityNodeInfo.nearestExecutableAncestorDepth():
        Int? {
        var currentNode: AccessibilityNodeInfo? = this
        var parentDepth = 0
        while (
            currentNode != null &&
            parentDepth <= MAP_UI_DIAGNOSTIC_ANCESTOR_DEPTH_LIMIT
        ) {
            if (currentNode.isExecutable()) {
                return parentDepth
            }
            currentNode = currentNode.parent
            parentDepth += 1
        }
        return null
    }

    // 判断节点是否满足现有点击执行条件。
    private fun AccessibilityNodeInfo.isExecutable(): Boolean {
        return isEnabled && (
                isClickable ||
                        actionList.any { action ->
                            action.id == AccessibilityNodeInfo.ACTION_CLICK
                        }
                )
    }

    private data class MutableLabelDiagnostic(
        var matchedNodeCount: Int = 0,
        var directClickable: Boolean = false,
        var directClickAction: Boolean = false,
        var nearestExecutableAncestorDepth: Int? = null
    ) {

        // 合并一个命中节点的直接动作能力和最近祖先深度。
        fun record(node: AccessibilityNodeInfo) {
            matchedNodeCount += 1
            directClickable = directClickable || node.isClickable
            directClickAction = directClickAction ||
                    node.actionList.any { action ->
                        action.id == AccessibilityNodeInfo.ACTION_CLICK
                    }
            val nodeDepth = node.nearestExecutableAncestorDepth()
            if (
                nodeDepth != null &&
                (
                        nearestExecutableAncestorDepth == null ||
                                nodeDepth <
                                requireNotNull(
                                    nearestExecutableAncestorDepth
                                )
                        )
            ) {
                nearestExecutableAncestorDepth = nodeDepth
            }
        }
    }

    private data class MutableNodeCounter(
        var count: Int = 0
    )

    private data class CollectedNode(
        val snapshot: MapUiNodeSnapshot,
        val gestureCandidate: MapUiGestureCandidate
    )

    private const val MAX_DEPTH = 12
    private const val MAX_NODE_COUNT = 300
}
