package com.wu.nearbycheckin.platform.accessibility

import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.platform.maps.MapUiAction
import com.wu.nearbycheckin.platform.maps.MapUiPage

// 生成包含页面循环全部字段且值已清洗的结构化证据。
internal fun buildMapUiEvidence(
    provider: MapProvider,
    packageName: String,
    appVersion: String,
    profileVersion: String,
    page: MapUiPage,
    action: MapUiAction?,
    evidence: String
): String {
    return listOf(
        "provider=${provider.name}",
        "package=${sanitizeEvidenceValue(packageName)}",
        "appVersion=${sanitizeEvidenceValue(appVersion)}",
        "profileVersion=${sanitizeEvidenceValue(profileVersion)}",
        "page=${page.name}",
        "action=${action?.name.orEmpty()}",
        "evidence=${sanitizeEvidenceValue(evidence)}"
    ).joinToString(separator = "；")
}

// 在保留结构化前缀的前提下追加证据并限制 Worker 总长度。
internal fun appendBoundedWorkerEvidence(
    structuredEvidence: String,
    key: String,
    value: String
): String {
    val boundedPrefix =
        structuredEvidence.take(WORKER_EVIDENCE_MAX_LENGTH)
    val safeKey = sanitizeEvidenceValue(
        value = key,
        maxLength = EVIDENCE_KEY_MAX_LENGTH
    )
    val separator = "；$safeKey="
    val remainingLength =
        WORKER_EVIDENCE_MAX_LENGTH -
                boundedPrefix.length -
                separator.length
    if (value.isBlank() || remainingLength <= 0) {
        return boundedPrefix
    }
    return boundedPrefix +
            separator +
            sanitizeEvidenceValue(
                value = value,
                maxLength = remainingLength
            )
}

// 生成带事件名称且优先保留结构化字段的状态文本。
internal fun buildStructuredStatusText(
    event: String,
    structuredEvidence: String
): String {
    val eventPrefix =
        "event=${sanitizeEvidenceValue(event)}；"
    return eventPrefix +
            structuredEvidence.take(
                WORKER_EVIDENCE_MAX_LENGTH - eventPrefix.length
            )
}

internal const val WORKER_EVIDENCE_MAX_LENGTH = 1_024
private const val EVIDENCE_KEY_MAX_LENGTH = 40
