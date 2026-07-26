// @Time: 2026/07/27 00:46
// @Author: MrWu
// @File: AmapCheckInButtonGesturePolicy.kt
// @Software: Android Studio

package com.wu.nearbycheckin.platform.maps

object AmapCheckInButtonGesturePolicy {

    // 从当前版本右下角打卡按钮边界生成安全中心点击位置。
    fun select(
        candidates: List<MapUiGestureCandidate>,
        windowBounds: MapUiBounds
    ): MapUiGestureTargetSelection {
        if (windowBounds.width <= 0 || windowBounds.height <= 0) {
            return MapUiGestureTargetSelection(
                rejectionReason = "高德窗口边界无效"
            )
        }

        val eligibleBounds = candidates
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
        val compactAnchors = eligibleBounds
            .filter { bounds ->
                val leftRatio =
                    (bounds.left - windowBounds.left) * 100 /
                            windowBounds.width
                val topRatio =
                    (bounds.top - windowBounds.top) * 100 /
                            windowBounds.height
                val bottomRatio =
                    (bounds.bottom - windowBounds.top) * 100 /
                            windowBounds.height
                val widthRatio =
                    bounds.width * 100 / windowBounds.width
                val heightRatio =
                    bounds.height * 100 / windowBounds.height
                leftRatio in MIN_BUTTON_LEFT_PERCENT..
                        MAX_BUTTON_LEFT_PERCENT &&
                        topRatio in MIN_BUTTON_TOP_PERCENT..
                        MAX_BUTTON_TOP_PERCENT &&
                        bottomRatio <= MAX_BUTTON_BOTTOM_PERCENT &&
                        widthRatio in MIN_BUTTON_WIDTH_PERCENT..
                        MAX_BUTTON_WIDTH_PERCENT &&
                        heightRatio in MIN_BUTTON_HEIGHT_PERCENT..
                        MAX_BUTTON_HEIGHT_PERCENT
            }
        val usesWideActionBar = compactAnchors.isEmpty()
        val anchors = if (usesWideActionBar) {
            eligibleBounds.filter { bounds ->
                val leftRatio =
                    (bounds.left - windowBounds.left) * 100 /
                            windowBounds.width
                val topRatio =
                    (bounds.top - windowBounds.top) * 100 /
                            windowBounds.height
                val bottomRatio =
                    (bounds.bottom - windowBounds.top) * 100 /
                            windowBounds.height
                val widthRatio =
                    bounds.width * 100 / windowBounds.width
                val heightRatio =
                    bounds.height * 100 / windowBounds.height
                leftRatio in MIN_ACTION_BAR_LEFT_PERCENT..
                        MAX_ACTION_BAR_LEFT_PERCENT &&
                        topRatio in MIN_BUTTON_TOP_PERCENT..
                        MAX_BUTTON_TOP_PERCENT &&
                        bottomRatio <= MAX_BUTTON_BOTTOM_PERCENT &&
                        widthRatio in MIN_ACTION_BAR_WIDTH_PERCENT..
                        MAX_ACTION_BAR_WIDTH_PERCENT &&
                        heightRatio in MIN_BUTTON_HEIGHT_PERCENT..
                        MAX_BUTTON_HEIGHT_PERCENT
            }
        } else {
            compactAnchors
        }
        if (anchors.isEmpty()) {
            return MapUiGestureTargetSelection(
                rejectionReason = "未找到高德打卡按钮锚点"
            )
        }

        val maximumArea = anchors.maxOf { bounds ->
            bounds.width.toLong() * bounds.height
        }
        val largestAnchors = anchors.filter { bounds ->
            bounds.width.toLong() * bounds.height == maximumArea
        }
        if (largestAnchors.size != 1) {
            return MapUiGestureTargetSelection(
                rejectionReason = "高德打卡按钮锚点不唯一"
            )
        }

        val anchor = largestAnchors.single()
        return MapUiGestureTargetSelection(
            target = MapUiGestureTarget(
                x = if (usesWideActionBar) {
                    anchor.left +
                            anchor.width *
                            ACTION_BAR_BUTTON_X_PERCENT / 100
                } else {
                    anchor.left + anchor.width / 2
                },
                y = anchor.top + anchor.height / 2
            )
        )
    }

    private const val MIN_BUTTON_LEFT_PERCENT = 70
    private const val MAX_BUTTON_LEFT_PERCENT = 90
    private const val MIN_BUTTON_TOP_PERCENT = 75
    private const val MAX_BUTTON_TOP_PERCENT = 88
    private const val MAX_BUTTON_BOTTOM_PERCENT = 94
    private const val MIN_BUTTON_WIDTH_PERCENT = 15
    private const val MAX_BUTTON_WIDTH_PERCENT = 30
    private const val MIN_BUTTON_HEIGHT_PERCENT = 7
    private const val MAX_BUTTON_HEIGHT_PERCENT = 18
    private const val MIN_ACTION_BAR_LEFT_PERCENT = 0
    private const val MAX_ACTION_BAR_LEFT_PERCENT = 10
    private const val MIN_ACTION_BAR_WIDTH_PERCENT = 85
    private const val MAX_ACTION_BAR_WIDTH_PERCENT = 95
    private const val ACTION_BAR_BUTTON_X_PERCENT = 89
}
