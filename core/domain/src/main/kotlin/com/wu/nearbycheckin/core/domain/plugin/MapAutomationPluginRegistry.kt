// @Time: 2026/08/16 01:59
// @Author: MrWu
// @File: MapAutomationPluginRegistry.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.plugin


import com.wu.nearbycheckin.core.model.MapProvider


/**
 * 标识地图插件当前是否可进入新 Provider 自动化链路。
 */
enum class PluginAvailability {
    ENABLED,
    LEGACY,
    DISABLED
}

/**
 * 保存注册表内一个 Provider 的插件或兼容状态。
 */
data class MapAutomationPluginEntry(
    val provider: MapProvider,
    val availability: PluginAvailability,
    val plugin: MapAutomationPlugin? = null,
    val reason: String = ""
) {

    init {
        require(
            availability != PluginAvailability.ENABLED || plugin != null
        ) {
            "启用状态必须提供地图自动化插件"
        }
        require(plugin == null || plugin.provider == provider) {
            "插件 Provider 与注册项不一致"
        }
    }
}

/**
 * 按 Provider 提供唯一插件解析入口，并显式表达 legacy/disabled 状态。
 */
class MapAutomationPluginRegistry(
    entries: List<MapAutomationPluginEntry>
) {
    private val entriesByProvider = entries.associateBy { entry ->
        entry.provider
    }

    // 返回指定 Provider 的注册项，缺失时降级为 DISABLED。
    fun entry(provider: MapProvider): MapAutomationPluginEntry {
        return entriesByProvider[provider] ?: MapAutomationPluginEntry(
            provider = provider,
            availability = PluginAvailability.DISABLED,
            reason = "未注册地图自动化插件"
        )
    }

    // 返回指定 Provider 已启用的真实插件，否则返回空值。
    fun resolve(provider: MapProvider): MapAutomationPlugin? {
        return entry(provider).takeIf { item ->
            item.availability == PluginAvailability.ENABLED
        }?.plugin
    }

    // 返回全部 Provider 的稳定注册快照。
    fun entries(): List<MapAutomationPluginEntry> {
        return MapProvider.entries.map(::entry)
    }
}
