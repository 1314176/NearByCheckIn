// @Time: 2026/07/20 18:32
// @Author: MrWu
// @File: AccessibilityNodeActions.kt
// @Software: Android Studio


@file:Suppress("SpellCheckingInspection")


package com.wu.nearbycheckin.platform.maps


import android.view.accessibility.AccessibilityNodeInfo


/**
 * 保存一次无障碍节点点击结果。
 *
 * @property success 点击是否成功执行。
 * @property message 点击结果或失败原因。
 */
data class AccessibilityClickResult(
    val success: Boolean,
    val message: String
)

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
        labels: List<String>
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

        val clickableNode = matchingNodes.firstNotNullOfOrNull { node ->
            findClickableNode(node)
        } ?: return AccessibilityClickResult(
            success = false,
            message = "节点不可点击：${labels.joinToString(separator = "/")}"
        )

        val clicked = clickableNode.performAction(
            AccessibilityNodeInfo.ACTION_CLICK
        )

        return AccessibilityClickResult(
            success = clicked,
            message = if (clicked) {
                "已点击节点：${labels.joinToString(separator = "/")}"
            } else {
                "节点点击失败：${labels.joinToString(separator = "/")}"
            }
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
        var currentNode: AccessibilityNodeInfo? = node

        while (currentNode != null) {
            if (
                currentNode.isEnabled &&
                (
                        currentNode.isClickable ||
                                currentNode.actionList.any { action ->
                                    action.id == AccessibilityNodeInfo.ACTION_CLICK
                                }
                        )
            ) {
                return currentNode
            }

            currentNode = currentNode.parent
        }

        return null
    }

    private const val MAX_SEARCH_DEPTH = 12
    private const val MAX_MATCHING_NODE_COUNT = 20
}
