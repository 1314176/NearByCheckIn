package com.wu.nearbycheckin.platform.maps

object MapUiGestureFallbackExecutor {

    // 根据已校验目标提交一次手势并返回确定结果。
    fun execute(
        selection: MapUiGestureTargetSelection,
        tap: (Int, Int) -> Boolean
    ): AccessibilityClickResult {
        val target = selection.target
            ?: return AccessibilityClickResult(
                success = false,
                message =
                    "语义边界手势已拒绝：${selection.rejectionReason}"
            )
        val submitted = tap(target.x, target.y)
        return AccessibilityClickResult(
            success = submitted,
            message = if (submitted) {
                "语义边界手势已提交"
            } else {
                "语义边界手势提交失败"
            }
        )
    }
}
