// @Time: 2026/07/26 01:29
// @Author: MrWu
// @File: DualMapPaneLaunchPolicy.kt
// @Software: Codex

package com.wu.nearbycheckin.platform.multiwindow

import android.content.Intent

/**
 * 统一生成双地图当前窗格替换所需的 Activity 启动标记。
 */
internal object DualMapPaneLaunchPolicy {

    // 生成百度地图替换当前分屏窗格所需的启动标记。
    fun currentPaneReplacementFlags(existingFlags: Int): Int {
        val excludedFlags =
            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        return (existingFlags and excludedFlags.inv()) or
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
    }
}
