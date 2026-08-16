// @Time: 2026/08/16 02:14
// @Author: MrWu
// @File: PluginWindowSnapshotCollector.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.maps


import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

import com.wu.nearbycheckin.core.domain.plugin.MapWindowSnapshot
import com.wu.nearbycheckin.core.domain.plugin.PluginNodeSnapshot
import com.wu.nearbycheckin.core.model.MapProvider


/**
 * 将 Android 无障碍节点树转换为插件可测试的纯 Kotlin 快照。
 */
object PluginWindowSnapshotCollector {

    // 采集有限深度和数量的节点并生成稳定路径指纹。
    fun collect(
        rootNode: AccessibilityNodeInfo?,
        provider: MapProvider,
        packageName: String,
        appVersion: String,
        profileVersion: String,
        capturedAtMillis: Long = System.currentTimeMillis()
    ): MapWindowSnapshot {
        val nodes = mutableListOf<PluginNodeSnapshot>()
        collectNodes(
            node = rootNode,
            depth = 0,
            path = "0",
            parentFingerprint = "root",
            result = nodes,
            nodeCounter = MutableNodeCounter()
        )
        return MapWindowSnapshot(
            provider = provider,
            packageName = packageName,
            appVersion = appVersion,
            profileVersion = profileVersion,
            nodes = nodes,
            capturedAtMillis = capturedAtMillis
        )
    }

    // 递归复制节点属性且不在领域层保留 Android 对象。
    private fun collectNodes(
        node: AccessibilityNodeInfo?,
        depth: Int,
        path: String,
        parentFingerprint: String,
        result: MutableList<PluginNodeSnapshot>,
        nodeCounter: MutableNodeCounter
    ) {
        if (
            node == null ||
            depth > MAX_DEPTH ||
            nodeCounter.count >= MAX_VISITED_NODE_COUNT
        ) {
            return
        }
        nodeCounter.count += 1
        val bounds = Rect().also(node::getBoundsInScreen)
        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        val fingerprint = listOf(
            path,
            viewId,
            text,
            bounds.left,
            bounds.top,
            bounds.right,
            bounds.bottom
        ).joinToString("|")
        val hasUserLabel = text.isNotBlank() || description.isNotBlank()
        val isActionable = node.isClickable ||
                node.hasClickAction() || node.isScrollable
        if (
            hasUserLabel ||
            (
                result.size < MAX_STRUCTURAL_NODE_COUNT &&
                        (viewId.isNotBlank() || isActionable)
            )
        ) {
            result += PluginNodeSnapshot(
                text = text,
                description = description,
                viewId = viewId,
                clickable = node.isClickable || node.hasClickAction(),
                scrollable = node.isScrollable,
                enabled = node.isEnabled,
                selected = node.isSelected,
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
                depth = depth,
                parentFingerprint = parentFingerprint,
                fingerprint = fingerprint
            )
        }
        for (index in 0 until node.childCount) {
            collectNodes(
                node = node.getChild(index),
                depth = depth + 1,
                path = "$path.$index",
                parentFingerprint = fingerprint,
                result = result,
                nodeCounter = nodeCounter
            )
        }
    }

    // 判断节点是否声明点击动作。
    private fun AccessibilityNodeInfo.hasClickAction(): Boolean {
        return actionList.any { action ->
            action.id == AccessibilityNodeInfo.ACTION_CLICK
        }
    }

    private data class MutableNodeCounter(
        var count: Int = 0
    )

    private const val MAX_DEPTH = 32
    private const val MAX_STRUCTURAL_NODE_COUNT = 600
    private const val MAX_VISITED_NODE_COUNT = 2_000
}
