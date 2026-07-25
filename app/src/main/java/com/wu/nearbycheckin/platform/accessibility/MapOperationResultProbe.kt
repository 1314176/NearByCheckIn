// @Time: 2026/07/20 18:39
// @Author: MrWu
// @File: MapOperationResultProbe.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.accessibility


import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

import com.wu.nearbycheckin.core.model.MapOperationStatus


/**
 * 保存一次地图页面结果文本探测。
 */
data class MapOperationProbeResult(
    val status: MapOperationProbeStatus,
    val evidenceText: String
) {

    val failureCode: String
        get() = if (status.operationStatus == MapOperationStatus.FAILED) {
            "MAP_OPERATION_FAILED"
        } else {
            ""
        }
}

/**
 * 描述地图端页面探测的内部状态。
 */
enum class MapOperationProbeStatus(
    val displayText: String,
    val operationStatus: MapOperationStatus,
    val isTerminal: Boolean
) {
    SUCCESS(
        displayText = "打卡成功",
        operationStatus = MapOperationStatus.SUCCESS,
        isTerminal = true
    ),
    ALREADY_COMPLETED(
        displayText = "今日已打卡",
        operationStatus = MapOperationStatus.ALREADY_COMPLETED,
        isTerminal = true
    ),
    FAILED(
        displayText = "打卡失败",
        operationStatus = MapOperationStatus.FAILED,
        isTerminal = true
    ),
    BLOCKED(
        displayText = "需要人工处理",
        operationStatus = MapOperationStatus.BLOCKED,
        isTerminal = true
    ),
    UNSUPPORTED(
        displayText = "当前页面不支持自动打卡",
        operationStatus = MapOperationStatus.UNSUPPORTED,
        isTerminal = true
    ),
    UNKNOWN(
        displayText = "暂未识别结果",
        operationStatus = MapOperationStatus.UNKNOWN,
        isTerminal = false
    )
}

// 按精确兼容标记解析旧地图页面结果文本。
internal fun resolveMapOperationProbeStatus(
    text: String
): MapOperationProbeStatus {
    return when {
        ALREADY_COMPLETED_TEXT_MARKERS.any(text::contains) ->
            MapOperationProbeStatus.ALREADY_COMPLETED
        SUCCESS_TEXT_MARKERS.any(text::contains) ->
            MapOperationProbeStatus.SUCCESS
        BLOCKED_TEXT_MARKERS.any(text::contains) ->
            MapOperationProbeStatus.BLOCKED
        UNSUPPORTED_TEXT_MARKERS.any(text::contains) ->
            MapOperationProbeStatus.UNSUPPORTED
        FAILURE_TEXT_MARKERS.any(text::contains) ->
            MapOperationProbeStatus.FAILED
        else -> MapOperationProbeStatus.UNKNOWN
    }
}

/**
 * 隔离无障碍节点查找、证据采集和结果关键词解析。
 */
class MapOperationResultProbe(
    private val service: AccessibilityService
) {

    // 查找指定地图包名对应的窗口根节点。
    fun findWindowRootNode(
        packageName: String
    ): AccessibilityNodeInfo? {
        service.windows.orEmpty().forEach { window ->
            val rootNode = window.root ?: return@forEach
            if (
                containsPackageName(
                    node = rootNode,
                    packageName = packageName,
                    depth = 0
                )
            ) {
                return rootNode
            }
        }
        return service.rootInActiveWindow?.takeIf { rootNode ->
            containsPackageName(
                node = rootNode,
                packageName = packageName,
                depth = 0
            )
        }
    }

    // 采集当前页面文本并解析地图端操作状态。
    fun probe(packageName: String): MapOperationProbeResult {
        val visibleTexts = mutableListOf<String>()
        collectVisibleNodeTexts(
            node = findWindowRootNode(packageName),
            depth = 0,
            result = visibleTexts
        )
        return MapOperationProbeResult(
            status = resolveMapOperationProbeStatus(
                visibleTexts.joinToString(separator = " ")
            ),
            evidenceText = visibleTexts
                .take(EVIDENCE_TEXT_COUNT)
                .joinToString(separator = " / ")
        )
    }

    // 递归判断节点树是否属于目标地图应用。
    private fun containsPackageName(
        node: AccessibilityNodeInfo?,
        packageName: String,
        depth: Int
    ): Boolean {
        if (node == null || depth > PACKAGE_SCAN_DEPTH) {
            return false
        }
        if (node.packageName?.toString() == packageName) {
            return true
        }
        for (childIndex in 0 until node.childCount) {
            if (
                containsPackageName(
                    node = node.getChild(childIndex),
                    packageName = packageName,
                    depth = depth + 1
                )
            ) {
                return true
            }
        }
        return false
    }

    // 收集节点文本和内容描述作为结果证据。
    private fun collectVisibleNodeTexts(
        node: AccessibilityNodeInfo?,
        depth: Int,
        result: MutableList<String>
    ) {
        if (
            node == null ||
            depth > TEXT_SCAN_DEPTH ||
            result.size >= TEXT_MAX_COUNT
        ) {
            return
        }

        val text = node.text?.toString().orEmpty().trim()
        val description =
            node.contentDescription?.toString().orEmpty().trim()
        if (text.isNotBlank()) {
            result += text
        }
        if (description.isNotBlank() && description != text) {
            result += description
        }
        for (childIndex in 0 until node.childCount) {
            collectVisibleNodeTexts(
                node = node.getChild(childIndex),
                depth = depth + 1,
                result = result
            )
        }
    }

    private companion object {
        const val PACKAGE_SCAN_DEPTH = 4
        const val TEXT_SCAN_DEPTH = 12
        const val TEXT_MAX_COUNT = 120
        const val EVIDENCE_TEXT_COUNT = 8
    }
}

private val SUCCESS_TEXT_MARKERS = listOf(
    "打卡成功",
    "签到成功"
)
private val ALREADY_COMPLETED_TEXT_MARKERS = listOf(
    "今日已打卡",
    "当前地点已打卡",
    "今天已签到"
)
private val FAILURE_TEXT_MARKERS = listOf(
    "打卡失败",
    "签到失败",
    "失败",
    "未成功",
    "网络异常",
    "稍后再试",
    "无法打卡"
)
private val BLOCKED_TEXT_MARKERS = listOf(
    "登录",
    "验证码",
    "安全验证",
    "权限",
    "授权",
    "账号",
    "风险"
)
private val UNSUPPORTED_TEXT_MARKERS = listOf(
    "不支持",
    "暂未开放",
    "功能不可用",
    "版本过低",
    "无法识别"
)
