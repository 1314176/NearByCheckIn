package com.wu.nearbycheckin.platform.maps

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

// 把无障碍节点屏幕边界转换为纯 Kotlin 边界。
internal fun AccessibilityNodeInfo.toMapUiBounds(): MapUiBounds {
    val rect = Rect().also { bounds ->
        getBoundsInScreen(bounds)
    }
    return MapUiBounds(
        left = rect.left,
        top = rect.top,
        right = rect.right,
        bottom = rect.bottom
    )
}
