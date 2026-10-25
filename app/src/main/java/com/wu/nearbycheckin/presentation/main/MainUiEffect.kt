// @Time: 2026/07/20 18:29
// @Author: MrWu
// @File: MainUiEffect.kt
// @Software: Android Studio


package com.wu.nearbycheckin.presentation.main


/**
 * 表示必须由 Activity 一次性处理的系统导航动作。
 */
sealed interface MainUiEffect {

    data object RequestLocationPermission : MainUiEffect

    data object RequestOverlayPermission : MainUiEffect

    data object OpenAccessibilitySettings : MainUiEffect

    data object OpenDualMapSplit : MainUiEffect

    data class OpenMapApplications(
        val packageNames: List<String>
    ) : MainUiEffect

    data class ShowToast(
        val message: String
    ) : MainUiEffect
}
