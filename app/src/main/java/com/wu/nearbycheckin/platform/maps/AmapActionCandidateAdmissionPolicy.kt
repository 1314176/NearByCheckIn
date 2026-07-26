// @Time: 2026/07/27 01:29
// @Author: MrWu
// @File: AmapActionCandidateAdmissionPolicy.kt
// @Software: Android Studio

package com.wu.nearbycheckin.platform.maps

object AmapActionCandidateAdmissionPolicy {

    // 仅接纳当前高德窗口内可见、启用且边界有效的动作候选。
    fun accepts(
        candidate: MapUiGestureCandidate,
        windowBounds: MapUiBounds
    ): Boolean {
        return windowBounds.width > 0 &&
                windowBounds.height > 0 &&
                candidate.enabled &&
                candidate.visible &&
                candidate.bounds.width > 0 &&
                candidate.bounds.height > 0 &&
                candidate.bounds.left >= windowBounds.left &&
                candidate.bounds.top >= windowBounds.top &&
                candidate.bounds.right <= windowBounds.right &&
                candidate.bounds.bottom <= windowBounds.bottom
    }
}
