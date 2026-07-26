// @Time: 2026/07/26 06:06
// @Author: MrWu
// @File: MapUiUnknownDiagnostics.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.maps

internal data class MapUiLabelDiagnosticTarget(
    val key: String,
    val label: String
)

internal data class MapUiLabelDiagnostic(
    val key: String,
    val present: Boolean,
    val matchedNodeCount: Int,
    val directClickable: Boolean,
    val directClickAction: Boolean,
    val nearestExecutableAncestorDepth: Int?
)

internal enum class MapUiAncestorDepthClass(
    val evidenceValue: String
) {
    WITHIN_EXECUTION_LIMIT("within_execution_limit"),
    BEYOND_EXECUTION_LIMIT("beyond_execution_limit"),
    NOT_FOUND_WITHIN_DIAGNOSTIC_LIMIT("not_found_within_20")
}

// 按六层执行边界分类诊断得到的最近可执行祖先深度。
internal fun classifyMapUiAncestorDepth(
    depth: Int?
): MapUiAncestorDepthClass {
    return when {
        depth == null ->
            MapUiAncestorDepthClass.NOT_FOUND_WITHIN_DIAGNOSTIC_LIMIT
        depth <= MAP_UI_EXECUTION_ANCESTOR_DEPTH_LIMIT ->
            MapUiAncestorDepthClass.WITHIN_EXECUTION_LIMIT
        else ->
            MapUiAncestorDepthClass.BEYOND_EXECUTION_LIMIT
    }
}

// 格式化不含原始节点内容的标签执行能力诊断。
internal fun formatMapUiLabelDiagnostics(
    diagnostics: List<MapUiLabelDiagnostic>
): String {
    return diagnostics.joinToString(separator = ",") { diagnostic ->
        val depth = diagnostic.nearestExecutableAncestorDepth
        val depthClass = classifyMapUiAncestorDepth(depth)
        listOf(
            "${diagnostic.key}Present=${diagnostic.present}",
            "${diagnostic.key}MatchedNodeCount=" +
                    diagnostic.matchedNodeCount,
            "${diagnostic.key}DirectClickable=" +
                    diagnostic.directClickable,
            "${diagnostic.key}DirectClickAction=" +
                    diagnostic.directClickAction,
            "${diagnostic.key}NearestExecutableAncestorDepth=" +
                    (depth?.toString() ?: "none"),
            "${diagnostic.key}AncestorDepthClass=" +
                    depthClass.evidenceValue
        ).joinToString(separator = ",")
    }
}

internal const val MAP_UI_EXECUTION_ANCESTOR_DEPTH_LIMIT = 6
internal const val MAP_UI_DIAGNOSTIC_ANCESTOR_DEPTH_LIMIT = 20
