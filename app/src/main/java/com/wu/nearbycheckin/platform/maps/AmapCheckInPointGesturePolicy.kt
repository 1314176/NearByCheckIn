// @Time: 2026/07/27 00:38
// @Author: MrWu
// @File: AmapCheckInPointGesturePolicy.kt
// @Software: Android Studio

package com.wu.nearbycheckin.platform.maps

object AmapCheckInPointGesturePolicy {

    // 从当前版本足迹卡片边界计算“打卡点”的安全相对点击位置。
    fun select(
        candidates: List<MapUiGestureCandidate>,
        windowBounds: MapUiBounds
    ): MapUiGestureTargetSelection {
        if (windowBounds.width <= 0 || windowBounds.height <= 0) {
            return MapUiGestureTargetSelection(
                rejectionReason = "高德窗口边界无效"
            )
        }

        val anchors = candidates
            .filter { candidate ->
                candidate.enabled &&
                        candidate.visible &&
                        candidate.bounds.width > 0 &&
                        candidate.bounds.height > 0 &&
                        candidate.bounds.left >= windowBounds.left &&
                        candidate.bounds.top >= windowBounds.top &&
                        candidate.bounds.right <= windowBounds.right &&
                        candidate.bounds.bottom <= windowBounds.bottom
            }
            .map(MapUiGestureCandidate::bounds)
            .distinct()
            .filter { bounds ->
                val widthRatio =
                    bounds.width * 100 / windowBounds.width
                val heightRatio =
                    bounds.height * 100 / windowBounds.height
                val topRatio =
                    (bounds.top - windowBounds.top) * 100 /
                            windowBounds.height
                widthRatio in MIN_CARD_WIDTH_PERCENT..
                        MAX_CARD_WIDTH_PERCENT &&
                        heightRatio in MIN_CARD_HEIGHT_PERCENT..
                        MAX_CARD_HEIGHT_PERCENT &&
                        topRatio in MIN_CARD_TOP_PERCENT..
                        MAX_CARD_TOP_PERCENT
            }
        if (anchors.isEmpty()) {
            return MapUiGestureTargetSelection(
                rejectionReason = "未找到高德足迹卡片锚点"
            )
        }

        val minimumArea = anchors.minOf { bounds ->
            bounds.width.toLong() * bounds.height
        }
        val smallestAnchors = anchors.filter { bounds ->
            bounds.width.toLong() * bounds.height == minimumArea
        }
        if (smallestAnchors.size != 1) {
            return MapUiGestureTargetSelection(
                rejectionReason = "高德足迹卡片锚点不唯一"
            )
        }

        val anchor = smallestAnchors.single()
        val target = MapUiGestureTarget(
            x = anchor.left +
                    anchor.width * CHECK_IN_POINT_X_PERCENT / 100,
            y = anchor.top +
                    anchor.height * CHECK_IN_POINT_Y_PERCENT / 100
        )
        if (!windowBounds.contains(target.x, target.y)) {
            return MapUiGestureTargetSelection(
                rejectionReason = "高德打卡点目标不在窗口内"
            )
        }
        return MapUiGestureTargetSelection(target = target)
    }

    private const val MIN_CARD_WIDTH_PERCENT = 90
    private const val MAX_CARD_WIDTH_PERCENT = 100
    private const val MIN_CARD_HEIGHT_PERCENT = 24
    private const val MAX_CARD_HEIGHT_PERCENT = 32
    private const val MIN_CARD_TOP_PERCENT = 35
    private const val MAX_CARD_TOP_PERCENT = 42
    private const val CHECK_IN_POINT_X_PERCENT = 49
    private const val CHECK_IN_POINT_Y_PERCENT = 64
}
