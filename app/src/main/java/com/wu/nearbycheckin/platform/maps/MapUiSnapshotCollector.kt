package com.wu.nearbycheckin.platform.maps

import android.view.accessibility.AccessibilityNodeInfo

object MapUiSnapshotCollector {

    // 把 Android 节点树转换为不含 Android 类型的只读快照。
    fun collect(
        rootNode: AccessibilityNodeInfo?,
        packageName: String,
        appVersion: String
    ): MapUiSnapshot {
        val nodes = mutableListOf<MapUiNodeSnapshot>()
        collectNodes(rootNode, depth = 0, result = nodes)
        return MapUiSnapshot(
            packageName = packageName,
            appVersion = appVersion,
            nodes = nodes
        )
    }

    // 递归采集页面节点并限制遍历深度和总量。
    private fun collectNodes(
        node: AccessibilityNodeInfo?,
        depth: Int,
        result: MutableList<MapUiNodeSnapshot>
    ) {
        if (
            node == null ||
            depth > MAX_DEPTH ||
            result.size >= MAX_NODE_COUNT
        ) {
            return
        }

        result += MapUiNodeSnapshot(
            text = node.text?.toString().orEmpty(),
            description = node.contentDescription?.toString().orEmpty(),
            viewId = node.viewIdResourceName.orEmpty(),
            clickable = node.hasClickableAncestor()
        )

        for (childIndex in 0 until node.childCount) {
            collectNodes(
                node = node.getChild(childIndex),
                depth = depth + 1,
                result = result
            )
        }
    }

    // 按实际执行规则判断当前节点或祖先是否可点击。
    private fun AccessibilityNodeInfo.hasClickableAncestor(): Boolean {
        var currentNode: AccessibilityNodeInfo? = this

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
                return true
            }

            currentNode = currentNode.parent
        }

        return false
    }

    private const val MAX_DEPTH = 12
    private const val MAX_NODE_COUNT = 300
}
