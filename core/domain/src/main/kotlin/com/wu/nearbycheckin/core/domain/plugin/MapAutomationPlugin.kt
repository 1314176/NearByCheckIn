// @Time: 2026/08/16 01:59
// @Author: MrWu
// @File: MapAutomationPlugin.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.plugin


import com.wu.nearbycheckin.core.model.MapOperationEvidence
import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.core.model.ProviderPlaceCandidate


/**
 * 保存与 Android 无障碍类型解耦的窗口节点摘要。
 */
data class PluginNodeSnapshot(
    val text: String = "",
    val description: String = "",
    val viewId: String = "",
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val depth: Int = 0,
    val parentFingerprint: String = "",
    val fingerprint: String = ""
) {

    // 返回节点全部非空语义标签。
    fun labels(): List<String> {
        return listOf(text, description, viewId)
            .map(String::trim)
            .filter(String::isNotBlank)
    }
}

/**
 * 保存插件检查页面时使用的纯 Kotlin 窗口快照。
 */
data class MapWindowSnapshot(
    val provider: MapProvider,
    val packageName: String,
    val appVersion: String,
    val profileVersion: String,
    val nodes: List<PluginNodeSnapshot>,
    val capturedAtMillis: Long
) {

    // 生成限制长度且与节点顺序一致的页面指纹。
    fun fingerprint(): String {
        return nodes.joinToString("|") { node ->
            node.fingerprint.ifBlank {
                node.labels().joinToString("/")
            }
        }.take(MAX_FINGERPRINT_LENGTH)
    }

    private companion object {
        const val MAX_FINGERPRINT_LENGTH = 2_000
    }
}

/**
 * 标识插件可识别的通用页面状态。
 */
enum class PluginPage {
    HOME,
    MINE,
    FOOTPRINT,
    CHECK_IN_ENTRY,
    CANDIDATE_LIST,
    SUCCESS,
    ALREADY_COMPLETED,
    DAILY_QUOTA_EXHAUSTED,
    SHARE_PANEL,
    RECOVERY,
    BLOCKED,
    UNKNOWN
}

/**
 * 保存插件页面识别结果和可追踪证据。
 */
data class PluginPageState(
    val page: PluginPage,
    val profileVersion: String,
    val fingerprint: String,
    val evidence: String,
    val blockedReason: String = ""
)

/**
 * 定义单个地图窗口的页面识别、候选读取、动作规划与结果验证端口。
 */
interface MapAutomationPlugin {
    val provider: MapProvider
    val profileVersion: String

    // 识别当前窗口所处页面并保留诊断证据。
    fun inspect(window: MapWindowSnapshot): PluginPageState

    // 从候选列表页面读取当前可见地点。
    fun readCandidates(
        window: MapWindowSnapshot
    ): List<ProviderPlaceCandidate>

    // 根据页面状态生成不绑定具体候选的下一动作。
    fun buildNextAction(state: PluginPageState): PluginCommand?

    // 为指定候选生成选择命令。
    fun buildSelectAction(
        candidate: ProviderPlaceCandidate
    ): PluginCommand

    // 验证动作后的窗口并返回结构化证据。
    fun verifyResult(window: MapWindowSnapshot): MapOperationEvidence
}
