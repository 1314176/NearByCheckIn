package com.wu.nearbycheckin.platform.maps

object MapUiClickPreferenceExecutor {

    // 按手势优先开关执行节点点击或语义手势回退。
    fun execute(
        preferGesture: Boolean,
        nodeClick: () -> AccessibilityClickResult,
        gestureClick: (() -> AccessibilityClickResult)?
    ): AccessibilityClickResult {
        if (preferGesture && gestureClick != null) {
            return gestureClick()
        }
        val nodeResult = nodeClick()
        if (nodeResult.success || gestureClick == null) {
            return nodeResult
        }
        return gestureClick()
    }
}
