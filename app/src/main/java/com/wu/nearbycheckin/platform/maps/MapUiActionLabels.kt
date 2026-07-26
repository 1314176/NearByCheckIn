// @Time: 2026/07/26 04:20
// @Author: MrWu
// @File: MapUiActionLabels.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.maps

import com.wu.nearbycheckin.core.model.MapProvider

object MapUiActionLabels {

    // 返回指定地图所有动作的去重白名单标签。
    fun allForProvider(provider: MapProvider): List<String> {
        return MapUiAction.entries
            .flatMap { action -> forProvider(provider, action) }
            .distinct()
    }

    // 返回指定地图和动作允许点击的当前版本标签。
    fun forProvider(
        provider: MapProvider,
        action: MapUiAction
    ): List<String> {
        return when (provider to action) {
            MapProvider.BAIDU to MapUiAction.CLICK_MINE ->
                listOf("我的")
            MapProvider.BAIDU to MapUiAction.CLICK_CHECK_IN_POINT ->
                listOf("打卡点")
            MapProvider.BAIDU to MapUiAction.CLICK_CHECK_IN ->
                listOf("立即打卡", "立刻打卡")
            MapProvider.BAIDU to MapUiAction.CLICK_HOME ->
                listOf("首页")
            MapProvider.BAIDU to MapUiAction.DISMISS_PROMPT ->
                listOf("知道了")
            MapProvider.BAIDU to MapUiAction.CLICK_DONE ->
                listOf("完成")
            MapProvider.AMAP to MapUiAction.CLICK_MINE ->
                listOf("我的")
            MapProvider.AMAP to MapUiAction.CLICK_CHECK_IN_POINT ->
                listOf("打卡点")
            MapProvider.AMAP to MapUiAction.CLICK_CHECK_IN ->
                listOf("开始打卡", "打卡")
            else -> emptyList()
        }
    }
}
