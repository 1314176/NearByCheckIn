// @Time: 2026/07/26 20:51
// @Author: MrWu
// @File: MapUiGestureTargetPolicy.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.maps

data class MapUiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int
        get() = right - left
    val height: Int
        get() = bottom - top

    // 判断边界是否包含指定屏幕点。
    fun contains(x: Int, y: Int): Boolean {
        return x >= left && x < right && y >= top && y < bottom
    }
}

data class MapUiGestureCandidate(
    val enabled: Boolean,
    val visible: Boolean,
    val bounds: MapUiBounds
)

data class MapUiGestureTarget(
    val x: Int,
    val y: Int
)

data class MapUiGestureTargetSelection(
    val target: MapUiGestureTarget? = null,
    val rejectionReason: String = ""
)

object MapUiGestureTargetPolicy {

    // 从唯一安全候选节点生成运行时边界中心点。
    fun select(
        candidates: List<MapUiGestureCandidate>,
        windowBounds: MapUiBounds
    ): MapUiGestureTargetSelection {
        if (candidates.isEmpty()) {
            return MapUiGestureTargetSelection(
                rejectionReason = "匹配节点数量不是一个"
            )
        }
        if (windowBounds.width <= 0 || windowBounds.height <= 0) {
            return MapUiGestureTargetSelection(
                rejectionReason = "目标窗口边界无效"
            )
        }

        if (candidates.size == 1) {
            val candidate = candidates.single()
            if (!candidate.enabled) {
                return MapUiGestureTargetSelection(
                    rejectionReason = "节点未启用"
                )
            }
            if (!candidate.visible) {
                return MapUiGestureTargetSelection(
                    rejectionReason = "节点对用户不可见"
                )
            }
            if (
                candidate.bounds.width <= 0 ||
                candidate.bounds.height <= 0
            ) {
                return MapUiGestureTargetSelection(
                    rejectionReason = "节点边界无效"
                )
            }
            val target = MapUiGestureTarget(
                x = candidate.bounds.left +
                        candidate.bounds.width / 2,
                y = candidate.bounds.top +
                        candidate.bounds.height / 2
            )
            if (!windowBounds.contains(target.x, target.y)) {
                return MapUiGestureTargetSelection(
                    rejectionReason = "节点中心不在目标窗口内"
                )
            }
            return MapUiGestureTargetSelection(target = target)
        }

        val safeTargets = candidates.mapNotNull { candidate ->
            if (
                !candidate.enabled ||
                !candidate.visible ||
                candidate.bounds.width <= 0 ||
                candidate.bounds.height <= 0
            ) {
                null
            } else {
                MapUiGestureTarget(
                    x = candidate.bounds.left +
                            candidate.bounds.width / 2,
                    y = candidate.bounds.top +
                            candidate.bounds.height / 2
                ).takeIf { target ->
                    windowBounds.contains(target.x, target.y)
                }
            }
        }
        if (safeTargets.size != 1) {
            return MapUiGestureTargetSelection(
                rejectionReason = "匹配节点数量不是一个"
            )
        }
        return MapUiGestureTargetSelection(
            target = safeTargets.single()
        )
    }
}
