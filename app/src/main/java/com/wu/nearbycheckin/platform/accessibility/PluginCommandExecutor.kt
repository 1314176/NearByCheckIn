// @Time: 2026/08/16 02:14
// @Author: MrWu
// @File: PluginCommandExecutor.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.accessibility


import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

import com.wu.nearbycheckin.core.domain.plugin.PluginCommand
import com.wu.nearbycheckin.core.domain.plugin.PluginCommandTarget
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
        onDeferredRepeatFirstTapCompleted: ((Boolean) -> Unit)? = null,
        forceGesture: Boolean = false
    ): PluginCommandExecutionResult {
        if (rootNode == null) {
            return PluginCommandExecutionResult(false, "窗口根节点为空")
        }
        if (rootNode.packageName?.toString() != packageName) {
            return PluginCommandExecutionResult(false, "目标窗口包名已经变化，取消旧命令")
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
                onDeferredRepeatFirstTapCompleted,
                forceGesture
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
        onDeferredRepeatFirstTapCompleted: ((Boolean) -> Unit)?,
        forceGesture: Boolean
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
            PluginCommandType.EXPAND_CHECK_IN_PANEL ->
                expandBaiduPanel(rootNode, packageName, command, forceGesture)
            else -> clickTarget(
                rootNode,
                packageName,
                command,
                onDeferredRepeatFirstTapCompleted,
                forceGesture
            )
        }
    }

    // 按稳定优先级定位目标并点击可执行节点或其中心点。
    private fun clickTarget(
        rootNode: AccessibilityNodeInfo,
        packageName: String,
        command: PluginCommand,
        onDeferredRepeatFirstTapCompleted: ((Boolean) -> Unit)?,
        forceGesture: Boolean
    ): PluginCommandExecutionResult {
        val candidates = mutableListOf<NodeWithFingerprint>()
        collectNodes(rootNode, "0", candidates)
        val rootBounds = Rect().also(rootNode::getBoundsInScreen)
        val visibleCandidates = candidates.filter { item ->
            val bounds = Rect().also(item.node::getBoundsInScreen)
            PluginCommandTargetBoundsPolicy.selectVisiblePoint(
                targetLeft = bounds.left,
                targetTop = bounds.top,
                targetRight = bounds.right,
                targetBottom = bounds.bottom,
                windowLeft = rootBounds.left,
                windowTop = rootBounds.top,
                windowRight = rootBounds.right,
                windowBottom = rootBounds.bottom
            ) != null
        }
        val target = PluginCommandTargetPolicy.select(
            candidates = visibleCandidates,
            target = command.target,
            fingerprint = { it.fingerprint },
            viewId = { it.node.viewIdResourceName.orEmpty() },
            nodeLabels = { it.node.labels() }
        )
        if (target == null) {
            if (command.type == PluginCommandType.NAVIGATE_BACK) {
                val success = gestureDispatcher.navigateBackForPackage(packageName)
                return PluginCommandExecutionResult(success,
                    if (success) "已向当前焦点地图提交返回" else "目标地图不持有焦点，等待安全返回")
            }
            return PluginCommandExecutionResult(
                false,
                "未找到目标：${command.target.labels.joinToString("/")}"
            )
        }
        val targetBounds = Rect().also(target.node::getBoundsInScreen)
        val executable = findClickableAncestor(target.node)
        val actionTarget = if (command.type == PluginCommandType.DISMISS_RESULT) {
            executable?.takeIf { ancestor ->
                val ancestorBounds = Rect().also(ancestor::getBoundsInScreen)
                PluginCommandTargetBoundsPolicy.isDismissButtonAncestor(
                    targetHeight = targetBounds.height(),
                    ancestorHeight = ancestorBounds.height(),
                    windowHeight = rootBounds.height()
                )
            } ?: target.node
        } else {
            target.node
        }
        val bounds = Rect().also(actionTarget::getBoundsInScreen)
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return PluginCommandExecutionResult(false, "目标节点边界无效")
        }
        val visiblePoint = gestureDispatcher.selectSafeTapPoint(packageName, rootNode.windowId,
            GestureTapBounds(bounds.left, bounds.top, bounds.right, bounds.bottom))
        if (visiblePoint == null) {
            if (command.type == PluginCommandType.DISMISS_RESULT) {
                if (!forceGesture && (actionTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                    actionTarget.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id))) {
                    return PluginCommandExecutionResult(true, "确认按钮被系统遮挡，已请求节点确认或移入可见区")
                }
                if (gestureDispatcher.navigateBackForPackage(packageName)) {
                    return PluginCommandExecutionResult(true, "确认按钮被系统完全遮挡，已安全返回目标地图")
                }
            }
            return PluginCommandExecutionResult(false, "目标按钮没有避开系统触摸热区的安全点击位置")
        }
        // 高德直接使用已避开系统热区的真实手势，避免节点点击返回假成功。
        if (!forceGesture && command.type == PluginCommandType.DISMISS_RESULT &&
            !PluginCommandGesturePolicy.shouldPreferGesture(command.provider, command.type) &&
            actionTarget.isEnabled && actionTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ) {
            return PluginCommandExecutionResult(true, "已提交结果确认节点点击，区域=${bounds.toShortString()}")
        }
        if (
            forceGesture || PluginCommandGesturePolicy.shouldPreferGesture(
                provider = command.provider,
                commandType = command.type
            )
        ) {
            return submitGesture(
                visiblePoint,
                packageName,
                command,
                onDeferredRepeatFirstTapCompleted,
                rootNode.windowId
            )
        }
        if (executable != null && executable.performAction(
                AccessibilityNodeInfo.ACTION_CLICK
            )
        ) {
            return PluginCommandExecutionResult(true, "已执行节点点击")
        }
        return submitGesture(
            visiblePoint,
            packageName,
            command,
            onDeferredRepeatFirstTapCompleted,
            rootNode.windowId
        )
    }

    // 按目标实时边界提交目标应用内的真实屏幕点击手势。
    private fun submitGesture(
        point: GestureTapPoint,
        packageName: String,
        command: PluginCommand,
        onDeferredRepeatFirstTapCompleted: ((Boolean) -> Unit)?,
        windowId: Int
    ): PluginCommandExecutionResult {
        val deferredRepeat = PluginCommandGesturePolicy.shouldUseDeferredRepeat(
            provider = command.provider,
            commandType = command.type,
            repeatAfterMillis = command.repeatAfterMillis
        )
        val accepted = gestureDispatcher.tapForPackage(
            x = point.x,
            y = point.y,
            packageName = packageName,
            onCompleted = onDeferredRepeatFirstTapCompleted
                .takeIf { deferredRepeat },
            windowId = windowId
        )
        return PluginCommandExecutionResult(
            accepted,
            if (accepted) {
                if (deferredRepeat) {
                    "已提交高德首次打卡手势"
                } else {
                    "已提交共享仲裁手势，坐标=(${point.x},${point.y})"
                }
            } else {
                "系统拒绝手势"
            },
            deferredRepeatSubmitted = accepted && deferredRepeat
        )
    }

    // 只展开百度打卡统计面板，普通滚动不可用或无效时提交窗格内的安全上拉。
    private fun expandBaiduPanel(
        rootNode: AccessibilityNodeInfo,
        packageName: String,
        command: PluginCommand,
        forceGesture: Boolean
    ): PluginCommandExecutionResult {
        if (command.provider != MapProvider.BAIDU) {
            return PluginCommandExecutionResult(false, "面板展开命令仅适用于百度地图")
        }
        val nodes = mutableListOf<NodeWithFingerprint>()
        collectNodes(rootNode, "0", nodes)
        val anchor = PluginCommandTargetPolicy.select(nodes, command.target,
            fingerprint = { it.fingerprint }, viewId = { it.node.viewIdResourceName.orEmpty() },
            nodeLabels = { it.node.labels() })?.node
            ?: return PluginCommandExecutionResult(false, "百度打卡统计标题已变化，等待重新读取")
        if (!forceGesture) {
            var ancestor: AccessibilityNodeInfo? = anchor
            repeat(MAX_ANCESTOR_DEPTH) {
                val current = ancestor ?: return@repeat
                if (current.isScrollable && current.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    return PluginCommandExecutionResult(true, "已请求滚动百度打卡面板，等待真实按钮露出")
                }
                ancestor = current.parent
            }
        }
        val bounds = Rect().also(anchor::getBoundsInScreen)
        val swipe = gestureDispatcher.expandPanelForPackage(packageName, rootNode.windowId,
            GestureTapBounds(bounds.left, bounds.top, bounds.right, bounds.bottom))
            ?: return PluginCommandExecutionResult(false, "百度打卡面板没有安全上拉路径，等待重新进入")
        return PluginCommandExecutionResult(true,
            "已提交百度面板上拉手势：(${swipe.start.x},${swipe.start.y})->(${swipe.end.x},${swipe.end.y})")
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

internal object PluginCommandTargetBoundsPolicy {

    // 仅使用文字附近的小型按钮容器，禁止把整张结果卡片或全屏遮罩当作确认按钮。
    fun isDismissButtonAncestor(
        targetHeight: Int,
        ancestorHeight: Int,
        windowHeight: Int
    ): Boolean {
        return targetHeight > 0 && ancestorHeight >= targetHeight &&
                ancestorHeight <= maxOf(targetHeight * 4, windowHeight / 8)
    }

    // 返回目标与当前窗口可见交集的中心点，避免分屏裁切后点击落入另一地图。
    fun selectVisiblePoint(
        targetLeft: Int,
        targetTop: Int,
        targetRight: Int,
        targetBottom: Int,
        windowLeft: Int,
        windowTop: Int,
        windowRight: Int,
        windowBottom: Int
    ): GestureTapPoint? {
        val left = maxOf(targetLeft, windowLeft)
        val top = maxOf(targetTop, windowTop)
        val right = minOf(targetRight, windowRight)
        val bottom = minOf(targetBottom, windowBottom)
        if (right <= left || bottom <= top) {
            return null
        }
        return GestureTapPoint(
            x = left + (right - left) / 2,
            y = top + (bottom - top) / 2
        )
    }
}

internal object PluginCommandTargetPolicy {

    // 固定目标失效必须重新采集页面，不能回退点击同名的模板入口。
    fun <T> select(
        candidates: List<T>,
        target: PluginCommandTarget,
        fingerprint: (T) -> String,
        viewId: (T) -> String,
        nodeLabels: (T) -> List<String>
    ): T? {
        if (target.nodeFingerprint.isNotBlank()) {
            return candidates.firstOrNull { fingerprint(it) == target.nodeFingerprint }
        }
        return candidates.firstOrNull {
            target.viewId.isNotBlank() && viewId(it) == target.viewId
        } ?: PluginCommandLabelPolicy.select(candidates, target.labels, nodeLabels)
    }
}

internal object PluginCommandLabelPolicy {

    // 按命令标签的优先级选择按钮，每个标签先精确匹配再兼容附带描述。
    fun <T> select(candidates: List<T>, labels: List<String>, nodeLabels: (T) -> List<String>): T? {
        for (label in labels) {
            candidates.firstOrNull { label in nodeLabels(it) }?.let { return it }
            candidates.firstOrNull { item ->
                nodeLabels(item).any { it.startsWith("$label,") || it.startsWith("$label，") }
            }?.let { return it }
        }
        return null
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
