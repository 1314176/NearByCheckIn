// @Time: 2026/07/20 18:32
// @Author: MrWu
// @File: AccessibilityNodeActions.kt
// @Software: Android Studio


@file:Suppress("SpellCheckingInspection")


package com.wu.nearbycheckin.platform.maps


import android.view.accessibility.AccessibilityNodeInfo


/**
 * 提供基于可访问节点文本的确定性点击能力，不使用屏幕坐标盲点。
 */
object AccessibilityNodeActions {

    /**
     * 按文本或内容描述查找并点击首个匹配节点。
     *
     * @param rootNode 当前窗口根节点。
     * @param labels 允许匹配的节点文本或内容描述。
     * @return 点击结果。
     */
    fun clickFirstMatchingLabel(
        rootNode: AccessibilityNodeInfo?,
        labels: List<String>,
        gestureTap: ((Int, Int) -> Boolean)? = null,
        preferGesture: Boolean = false
    ): AccessibilityClickResult {
        if (rootNode == null) {
            return AccessibilityClickResult(
                success = false,
                message = "窗口根节点为空"
            )
        }

        val matchingNodes = mutableListOf<AccessibilityNodeInfo>()
        collectMatchingNodes(
            node = rootNode,
            labels = labels,
            depth = 0,
            result = matchingNodes
        )
        if (matchingNodes.isEmpty()) {
            return AccessibilityClickResult(
                success = false,
                message = "未找到节点：${labels.joinToString(separator = "/")}"
            )
        }

        val gestureClick = gestureTap?.let { tap ->
            {
                executeGestureTap(
                    rootNode = rootNode,
                    matchingNodes = matchingNodes,
                    gestureTap = tap
                )
            }
        }
        return MapUiClickPreferenceExecutor.execute(
            preferGesture = preferGesture,
            nodeClick = {
                clickMatchingNode(
                    matchingNodes = matchingNodes,
                    labels = labels
                )
            },
            gestureClick = gestureClick
        )
    }

    // 点击首个可执行匹配节点并返回确定结果。
    private fun clickMatchingNode(
        matchingNodes: List<AccessibilityNodeInfo>,
        labels: List<String>
    ): AccessibilityClickResult {
        val clickableNode = matchingNodes.firstNotNullOfOrNull { node ->
            findClickableNode(node)
        } ?: return AccessibilityClickResult(
            success = false,
            message =
                "节点不可点击：${labels.joinToString(separator = "/")}"
        )
        val clicked = clickableNode.performAction(
            AccessibilityNodeInfo.ACTION_CLICK
        )
        return AccessibilityClickResult(
            success = clicked,
            message = if (clicked) {
                "已点击节点：" +
                        labels.joinToString(separator = "/")
            } else {
                "节点点击失败：" +
                        labels.joinToString(separator = "/")
            }
        )
    }

    // 从唯一安全语义节点生成边界中心并提交一次手势。
    private fun executeGestureTap(
        rootNode: AccessibilityNodeInfo,
        matchingNodes: List<AccessibilityNodeInfo>,
        gestureTap: (Int, Int) -> Boolean
    ): AccessibilityClickResult {
        val selection = MapUiGestureTargetPolicy.select(
            candidates = matchingNodes.map { node ->
                MapUiGestureCandidate(
                    enabled = node.isEnabled,
                    visible = node.isVisibleToUser,
                    bounds = node.toMapUiBounds()
                )
            },
            windowBounds = rootNode.toMapUiBounds()
        )
        return MapUiGestureFallbackExecutor.execute(
            selection = selection,
            tap = gestureTap
        )
    }

    /**
     * 递归查找第一个匹配文本或描述的节点。
     *
     * @param node 当前节点。
     * @param labels 允许匹配的文本集合。
     * @param depth 当前递归深度。
     * @return 找到的节点，找不到时返回null。
     */
    private fun collectMatchingNodes(
        node: AccessibilityNodeInfo?,
        labels: List<String>,
        depth: Int,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        if (
            node == null ||
            depth > MAX_SEARCH_DEPTH ||
            result.size >= MAX_MATCHING_NODE_COUNT
        ) {
            return
        }

        val text = node.text?.toString().orEmpty().trim()
        val description = node.contentDescription?.toString().orEmpty().trim()

        if (
            labels.any { label ->
                text.matchesLabel(label) ||
                        description.matchesLabel(label)
            }
        ) {
            result += node
        }

        for (childIndex in 0 until node.childCount) {
            collectMatchingNodes(
                node = node.getChild(childIndex),
                labels = labels,
                depth = depth + 1,
                result = result
            )
        }
    }

    // 匹配精确文本或无障碍描述中常见的按钮后缀。
    private fun String.matchesLabel(label: String): Boolean {
        return this == label ||
                startsWith("$label,") ||
                startsWith("$label，")
    }

    /**
     * 从目标节点向上查找可点击节点。
     *
     * @param node 目标文本节点。
     * @return 可点击节点，找不到时返回null。
     */
    private fun findClickableNode(
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        return BoundedAncestorFinder.findFirst(
            startNode = node,
            parentOf = { currentNode -> currentNode.parent },
            matches = { currentNode ->
                currentNode.isEnabled &&
                        (
                                currentNode.isClickable ||
                                        currentNode.actionList.any { action ->
                                            action.id ==
                                                    AccessibilityNodeInfo.ACTION_CLICK
                                        }
                                )
            }
        )
    }

    private const val MAX_SEARCH_DEPTH = 12
    private const val MAX_MATCHING_NODE_COUNT = 20
}
