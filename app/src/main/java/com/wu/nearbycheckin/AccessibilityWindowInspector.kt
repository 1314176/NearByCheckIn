// @Time: 2026/07/02 01:54
// @Author: MrWu
// @File: AccessibilityWindowInspector.kt
// @Software: Android Studio


@file:Suppress("SpellCheckingInspection")


package com.wu.nearbycheckin


import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 只读检查无障碍窗口和节点，用于采集百度地图与高德地图分屏诊断信息。
 */
object AccessibilityWindowInspector {

    /**
     * 检查当前可访问窗口和目标地图节点摘要。
     *
     * @param windows 当前无障碍服务可读取的交互窗口列表。
     * @param reason 本次诊断触发原因。
     * @return 本次诊断得到的窗口摘要状态。
     */
    fun inspectWindows(
        windows: List<AccessibilityWindowInfo>,
        reason: String
    ): AccessibilityDiagnosticsSnapshot {
        var baiduReady = false
        var amapReady = false
        var targetWindowCount = 0
        var targetNodeCount = 0
        var baiduClickableNodeCount = 0
        var baiduCandidateSummary = ""
        var amapClickableNodeCount = 0
        var amapCandidateSummary = ""

        windows.forEach { window ->
            val rootNode = window.root
            val packageName = resolvePackageName(
                rootNode
            )

            if (packageName == BAIDU_MAP_PACKAGE_NAME) {
                baiduReady = true
                val profile = BaiduMapAdapter.inspect(
                    rootNode
                )

                baiduClickableNodeCount = profile.clickableNodeCount
                baiduCandidateSummary = profile.toSummaryText()
            }

            if (packageName == AMAP_PACKAGE_NAME) {
                amapReady = true
                val profile = AmapMapAdapter.inspect(
                    rootNode
                )

                amapClickableNodeCount = profile.clickableNodeCount
                amapCandidateSummary = profile.toSummaryText()
            }

            if (packageName in TARGET_MAP_PACKAGE_NAMES) {
                targetWindowCount += 1
                val counter = NodeCounter()

                inspectNodeTree(
                    node = rootNode,
                    depth = 0,
                    counter = counter,
                    logDetails = false
                )

                targetNodeCount += counter.count
            }
        }

        return AccessibilityDiagnosticsSnapshot(
            baiduReady = baiduReady,
            amapReady = amapReady,
            totalWindowCount = windows.size,
            targetWindowCount = targetWindowCount,
            targetNodeCount = targetNodeCount,
            baiduClickableNodeCount = baiduClickableNodeCount,
            baiduCandidateSummary = baiduCandidateSummary,
            amapClickableNodeCount = amapClickableNodeCount,
            amapCandidateSummary = amapCandidateSummary,
            reason = reason,
            lastUpdatedMillis = System.currentTimeMillis()
        )
    }

    /**
     * 从根节点和子节点中解析窗口所属包名，兼容分屏场景下根节点包名为空的情况。
     *
     * @param rootNode 当前窗口根节点。
     * @return 识别到的包名，无法识别时返回空字符串。
     */
    private fun resolvePackageName(rootNode: AccessibilityNodeInfo?): String {
        if (rootNode == null) {
            return ""
        }

        val rootPackageName = rootNode.packageName?.toString().orEmpty()

        if (rootPackageName.isNotBlank()) {
            return rootPackageName
        }

        return findFirstPackageName(
            node = rootNode,
            depth = 0
        )
    }

    /**
     * 递归查找节点树中的首个非空包名。
     *
     * @param node 当前节点。
     * @param depth 当前递归深度。
     * @return 找到的包名，找不到时返回空字符串。
     */
    private fun findFirstPackageName(
        node: AccessibilityNodeInfo?,
        depth: Int
    ): String {
        if (
            node == null ||
            depth > MAX_PACKAGE_SCAN_DEPTH
        ) {
            return ""
        }

        val packageName = node.packageName?.toString().orEmpty()

        if (packageName.isNotBlank()) {
            return packageName
        }

        for (childIndex in 0 until node.childCount) {
            val childPackageName = findFirstPackageName(
                node = node.getChild(childIndex),
                depth = depth + 1
            )

            if (childPackageName.isNotBlank()) {
                return childPackageName
            }
        }

        return ""
    }

    /**
     * 输出窗口诊断摘要，避免重复打印完整节点树。
     *
     * @param snapshot 本次诊断摘要。
     */
    fun logSnapshot(snapshot: AccessibilityDiagnosticsSnapshot) {
        Log.i(
            TAG,
            "分屏窗口状态：百度=${if (snapshot.baiduReady) "已就绪" else "未就绪"}，" +
                    "高德=${if (snapshot.amapReady) "已就绪" else "未就绪"}，" +
                    "窗口=${snapshot.totalWindowCount}，" +
                    "目标窗口=${snapshot.targetWindowCount}，" +
                    "节点=${snapshot.targetNodeCount}，" +
                    "百度可点=${snapshot.baiduClickableNodeCount}，" +
                    "高德可点=${snapshot.amapClickableNodeCount}，" +
                    "原因=${snapshot.reason}"
        )

        if (snapshot.baiduCandidateSummary.isNotBlank()) {
            Log.i(
                TAG,
                "百度候选节点：${snapshot.baiduCandidateSummary}"
            )
        }

        if (snapshot.amapCandidateSummary.isNotBlank()) {
            Log.i(
                TAG,
                "高德候选节点：${snapshot.amapCandidateSummary}"
            )
        }
    }

    /**
     * 递归检查节点树，并按需输出文本、描述、View ID、边界和可点击状态。
     *
     * @param node 当前需要输出的节点。
     * @param depth 当前节点深度。
     * @param counter 本次窗口诊断已输出节点数量。
     * @param logDetails 是否打印节点明细。
     */
    private fun inspectNodeTree(
        node: AccessibilityNodeInfo?,
        depth: Int,
        counter: NodeCounter,
        logDetails: Boolean
    ) {
        if (
            node == null ||
            depth > MAX_NODE_DEPTH ||
            counter.count >= MAX_NODE_LOG_COUNT
        ) {
            return
        }

        counter.count += 1

        if (logDetails) {
            val bounds = Rect().also { rect ->
                node.getBoundsInScreen(rect)
            }

            Log.d(
                TAG,
                "${"  ".repeat(depth)}节点 text=${node.text?.toString().orEmpty()} " +
                        "desc=${node.contentDescription?.toString().orEmpty()} " +
                        "viewId=${node.viewIdResourceName.orEmpty()} " +
                        "clickable=${node.isClickable} " +
                        "enabled=${node.isEnabled} " +
                        "bounds=$bounds"
            )
        }

        for (childIndex in 0 until node.childCount) {
            inspectNodeTree(
                node = node.getChild(childIndex),
                depth = depth + 1,
                counter = counter,
                logDetails = logDetails
            )
        }
    }

    /**
     * 记录单次节点树输出数量，防止复杂页面日志过量。
     */
    private class NodeCounter {
        var count = 0
    }

    private const val TAG = "WindowInspector"
    private const val BAIDU_MAP_PACKAGE_NAME = "com.baidu.BaiduMap"
    private const val AMAP_PACKAGE_NAME = "com.autonavi.minimap"
    private const val MAX_NODE_DEPTH = 8
    private const val MAX_PACKAGE_SCAN_DEPTH = 4
    private const val MAX_NODE_LOG_COUNT = 120

    private val TARGET_MAP_PACKAGE_NAMES = setOf(
        BAIDU_MAP_PACKAGE_NAME,
        AMAP_PACKAGE_NAME
    )
}
