// @Time: 2026/08/16 01:59
// @Author: MrWu
// @File: PluginCommand.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.plugin


import com.wu.nearbycheckin.core.model.MapCommand
import com.wu.nearbycheckin.core.model.MapOperationEvidence
import com.wu.nearbycheckin.core.model.MapOperationResult
import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.core.model.ProviderPlaceKey


/**
 * 标识插件命令的稳定动作类型。
 */
enum class PluginCommandType {
    NAVIGATE_MINE,
    NAVIGATE_FOOTPRINT,
    OPEN_CHECK_IN_ENTRY,
    READ_CANDIDATES,
    SELECT_CANDIDATE,
    VERIFY_RESULT,
    SCROLL_CANDIDATES,
    DISMISS_RESULT,
    NAVIGATE_BACK,
    WAIT,
    BLOCK,
    LEGACY_EXECUTE
}

/**
 * 保存插件命令可由平台层解析的纯 Kotlin 目标描述。
 */
data class PluginCommandTarget(
    val labels: List<String> = emptyList(),
    val viewId: String = "",
    val nodeFingerprint: String = "",
    val candidateKey: ProviderPlaceKey? = null,
    val x: Int? = null,
    val y: Int? = null
)

/**
 * 保存从领域状态机提交给地图插件执行器的原子命令。
 */
data class PluginCommand(
    val commandId: String,
    val taskId: String,
    val provider: MapProvider,
    val type: PluginCommandType,
    val target: PluginCommandTarget = PluginCommandTarget(),
    val createdAtMillis: Long = 0L,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val repeatAfterMillis: Long = 0L,
    val evidenceHint: String = ""
) {
    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
    }
}

// 将旧地图命令映射为可渐进迁移的插件命令。
fun MapCommand.toPluginCommand(): PluginCommand {
    return PluginCommand(
        commandId = commandId,
        taskId = transactionId,
        provider = provider,
        type = PluginCommandType.LEGACY_EXECUTE,
        createdAtMillis = createdAtMillis,
        evidenceHint = "legacy-attempt=$attempt"
    )
}

// 将旧地图结果映射为插件结构化证据，保留原始诊断字段。
fun MapOperationResult.toPluginEvidence(
    profileVersion: String = flowVersion,
    page: String = "LEGACY"
): MapOperationEvidence {
    return MapOperationEvidence(
        provider = provider,
        taskId = transactionId,
        commandId = commandId,
        profileVersion = profileVersion,
        page = page,
        matchedText = matchedPlaceText,
        viewId = matchedNode,
        beforeFingerprint = "",
        afterFingerprint = evidenceText,
        resultStatus = status,
        message = failureMessage.ifBlank { evidenceText },
        observedAtMillis = completedAt
    )
}
