package com.wu.nearbycheckin.platform.maps

import com.wu.nearbycheckin.core.model.MapProvider

object MapUiActionLabels {

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
            MapProvider.AMAP to MapUiAction.CLICK_MINE ->
                listOf("我的")
            MapProvider.AMAP to MapUiAction.CLICK_FOOTPRINT ->
                listOf("足迹", "我的足迹")
            MapProvider.AMAP to MapUiAction.CLICK_CHECK_IN ->
                listOf("打卡", "点击打卡")
            else -> emptyList()
        }
    }
}
