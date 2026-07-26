package com.wu.nearbycheckin.platform.maps

/**
 * 保存一次无障碍节点或语义手势点击结果。
 *
 * @property success 点击是否成功提交。
 * @property message 点击结果或失败原因。
 */
data class AccessibilityClickResult(
    val success: Boolean,
    val message: String
)
