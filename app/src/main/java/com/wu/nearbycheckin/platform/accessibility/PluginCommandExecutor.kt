// @Time: 2026/08/16 02:14
// @Author: MrWu
// @File: PluginCommandExecutor.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.accessibility


import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

import com.wu.nearbycheckin.core.domain.plugin.PluginCommand
import com.wu.nearbycheckin.core.domain.plugin.PluginCommandType
import com.wu.nearbycheckin.core.model.MapProvider


/**
 * 在共享手势仲裁器下执行一条插件原子命令。
 */
class PluginCommandExecutor(
    private val gestureDispatcher: AccessibilityGestureTapDispatcher
) {

    // 按指纹、视图标识或文本顺序定位并执行命令。
    fun execute(
        rootNode: AccessibilityNodeInfo?,
        packageName: String,
        command: PluginCommand,
        onDeferredRepeatFirstTapCompleted: ((Boolean) -> Unit)? = null
    ): PluginCommandExecutionResult {
        if (rootNode == null) {
            return PluginCommandExecutionResult(false, "窗口根节点为空")
        }
        if (
            command.type == PluginCommandType.READ_CANDIDATES ||
            command.type == PluginCommandType.VERIFY_RESULT ||
            command.type == PluginCommandType.WAIT
        ) {
            return PluginCommandExecutionResult(
                true,
                "命令无需界面动作"
            )
        }
        return gestureDispatcher.executeAtomically(command.provider) {
            executeUiCommand(
                rootNode,
                packageName,
                command,
                onDeferredRepeatFirstTapCompleted
            )
        } ?: PluginCommandExecutionResult(
            false,
            "共享无障碍命令仲裁器正忙"
        )
    }

    // 在已取得全局仲裁权后执行实际 UI 动作。
    private fun executeUiCommand(
        rootNode: AccessibilityNodeInfo,
        packageName: String,
        command: PluginCommand,
        onDeferredRepeatFirstTapCompleted: ((Boolean) -> Unit)?
    ): PluginCommandExecutionResult {
        return when (command.type) {
            PluginCommandType.READ_CANDIDATES,
            PluginCommandType.VERIFY_RESULT,
            PluginCommandType.WAIT ->
                PluginCommandExecutionResult(true, "命令无需界面动作")
            PluginCommandType.BLOCK ->
                PluginCommandExecutionResult(false, "插件报告流程阻断")
            PluginCommandType.SCROLL_CANDIDATES ->
                scrollFirst(rootNode)
            else -> clickTarget(
                rootNode,
                packageName,
                command,
                onDeferredRepeatFirstTapCompleted
            )
        }
    }

    // 按稳定优先级定位目标并点击可执行节点或其中心点。
    private fun clickTarget(
        rootNode: AccessibilityNodeInfo,
        packageName: String,
        command: PluginCommand,
        onDeferredRepeatFirstTapCompleted: ((Boolean) -> Unit)?
    ): PluginCommandExecutionResult {
        val candidates = mutableListOf<NodeWithFingerprint>()
        collectNodes(rootNode, "0", candidates)
        val target = candidates.firstOrNull { item ->
            command.target.nodeFingerprint.isNotBlank() &&
                    item.fingerprint == command.target.nodeFingerprint
        } ?: candidates.firstOrNull { item ->
            command.target.viewId.isNotBlank() &&
                    item.node.viewIdResourceName == command.target.viewId
        } ?: candidates.firstOrNull { item ->
            item.node.labels().any { value ->
                command.target.labels.any { label ->
                    value == label || value.startsWith("$label,") ||
                            value.startsWith("$label，")
                }
            }
        }
        if (target == null) {
            return PluginCommandExecutionResult(
                false,
                "未找到目标：${command.target.labels.joinToString("/")}"
            )
        }
        val bounds = Rect().also(target.node::getBoundsInScreen)
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return PluginCommandExecutionResult(false, "目标节点边界无效")
        }
        if (
            PluginCommandGesturePolicy.shouldPreferGesture(
                provider = command.provider,
                commandType = command.type
            )
        ) {
            return submitGesture(
                bounds,
                packageName,
                command,
                onDeferredRepeatFirstTapCompleted
            )
        }
        val executable = findClickableAncestor(target.node)
        if (executable != null && executable.performAction(
                AccessibilityNodeInfo.ACTION_CLICK
            )
        ) {
            return PluginCommandExecutionResult(true, "已执行节点点击")
        }
        return submitGesture(
            bounds,
            packageName,
            command,
            onDeferredRepeatFirstTapCompleted
        )
    }

    // 按目标实时边界提交目标应用内的真实屏幕点击手势。
    private fun submitGesture(
        bounds: Rect,
        packageName: String,
        command: PluginCommand,
        onDeferredRepeatFirstTapCompleted: ((Boolean) -> Unit)?
    ): PluginCommandExecutionResult {
        val deferredRepeat = PluginCommandGesturePolicy.shouldUseDeferredRepeat(
            provider = command.provider,
            commandType = command.type,
            repeatAfterMillis = command.repeatAfterMillis
        )
        val accepted = gestureDispatcher.tapForPackage(
            x = bounds.centerX(),
            y = bounds.centerY(),
            packageName = packageName,
            onCompleted = onDeferredRepeatFirstTapCompleted
                .takeIf { deferredRepeat }
        )
        return PluginCommandExecutionResult(
            accepted,
            if (accepted) {
                if (deferredRepeat) {
                    "已提交高德首次打卡手势"
                } else {
                    "已提交共享仲裁手势"
                }
            } else {
                "系统拒绝手势"
            },
            deferredRepeatSubmitted = accepted && deferredRepeat
        )
    }

    // 对首个可滚动节点执行有限向前滚动。
    private fun scrollFirst(
        rootNode: AccessibilityNodeInfo
    ): PluginCommandExecutionResult {
        val nodes = mutableListOf<NodeWithFingerprint>()
        collectNodes(rootNode, "0", nodes)
        val scrollable = nodes.firstOrNull { item ->
            item.node.isScrollable
        }?.node ?: return PluginCommandExecutionResult(
            false,
            "候选页面没有可滚动节点"
        )
        val success = scrollable.performAction(
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        )
        return PluginCommandExecutionResult(
            success,
            if (success) "已滚动候选列表" else "候选列表滚动失败"
        )
    }

    // 递归采集节点及与快照采集器一致的路径指纹。
    private fun collectNodes(
        node: AccessibilityNodeInfo?,
        path: String,
        result: MutableList<NodeWithFingerprint>,
        depth: Int = 0
    ) {
        if (node == null || depth > MAX_DEPTH || result.size >= MAX_COUNT) {
            return
        }
        val bounds = Rect().also(node::getBoundsInScreen)
        val fingerprint = listOf(
            path,
            node.viewIdResourceName.orEmpty(),
            node.text?.toString().orEmpty(),
            bounds.left,
            bounds.top,
            bounds.right,
            bounds.bottom
        ).joinToString("|")
        result += NodeWithFingerprint(node, fingerprint)
        for (index in 0 until node.childCount) {
            collectNodes(
                node = node.getChild(index),
                path = "$path.$index",
                result = result,
                depth = depth + 1
            )
        }
    }

    // 从语义节点向上查找最近的可点击祖先。
    private fun findClickableAncestor(
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_ANCESTOR_DEPTH) {
            val candidate = current ?: return null
            if (candidate.isEnabled && candidate.isClickable) {
                return candidate
            }
            current = candidate.parent
        }
        return null
    }

    // 返回节点文本和内容描述。
    private fun AccessibilityNodeInfo.labels(): List<String> {
        return listOf(
            text?.toString().orEmpty(),
            contentDescription?.toString().orEmpty()
        ).map(String::trim).filter(String::isNotBlank)
    }

    private data class NodeWithFingerprint(
        val node: AccessibilityNodeInfo,
        val fingerprint: String
    )

    private companion object {
        const val MAX_DEPTH = 32
        const val MAX_COUNT = 2_000
        const val MAX_ANCESTOR_DEPTH = 20
    }
}

internal object PluginCommandGesturePolicy {

    // 高德可点击节点经常返回假成功，所有会改变页面的动作统一使用真实手势。
    fun shouldPreferGesture(
        provider: MapProvider,
        commandType: PluginCommandType
    ): Boolean {
        return provider == MapProvider.AMAP &&
                commandType in AMAP_GESTURE_FIRST_COMMANDS
    }

    // 高德打卡首次点击完成后延迟重新读取页面，避免第二击沿用失效坐标。
    fun shouldUseDeferredRepeat(
        provider: MapProvider,
        commandType: PluginCommandType,
        repeatAfterMillis: Long
    ): Boolean {
        return provider == MapProvider.AMAP &&
                repeatAfterMillis > 0L &&
                commandType in AMAP_DEFERRED_REPEAT_COMMANDS
    }

    private val AMAP_GESTURE_FIRST_COMMANDS = setOf(
        PluginCommandType.NAVIGATE_MINE,
        PluginCommandType.NAVIGATE_FOOTPRINT,
        PluginCommandType.OPEN_CHECK_IN_ENTRY,
        PluginCommandType.SELECT_CANDIDATE,
        PluginCommandType.DISMISS_RESULT
    )
    private val AMAP_DEFERRED_REPEAT_COMMANDS = setOf(
        PluginCommandType.OPEN_CHECK_IN_ENTRY,
        PluginCommandType.SELECT_CANDIDATE
    )
}

/**
 * 保存插件命令同步提交结果。
 */
data class PluginCommandExecutionResult(
    val success: Boolean,
    val message: String,
    val deferredRepeatSubmitted: Boolean = false
)
