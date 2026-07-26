// @Time: 2026/07/26 23:09
// @Author: MrWu
// @File: AccessibilityGestureTapDispatcher.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

class AccessibilityGestureTapDispatcher(
    private val service: AccessibilityService
) {

    // 向系统提交一次短时屏幕点击手势。
    fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    TAP_DURATION_MILLIS
                )
            )
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private companion object {
        const val TAP_DURATION_MILLIS = 80L
    }
}
