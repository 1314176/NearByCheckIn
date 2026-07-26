// @Time: 2026/07/26 06:04
// @Author: MrWu
// @File: MapUiUnknownDiagnosticsTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.maps

import org.junit.Assert.assertEquals
import org.junit.Test

class MapUiUnknownDiagnosticsTest {

    // 验证第七层可执行祖先被分类为超出执行上限。
    @Test
    fun classifiesSeventhLevelAncestorBeyondExecutionLimit() {
        assertEquals(
            MapUiAncestorDepthClass.BEYOND_EXECUTION_LIMIT,
            classifyMapUiAncestorDepth(7)
        )
    }

    // 验证缺少可执行祖先时格式化为二十层内未找到。
    @Test
    fun formatsMissingAncestorWithinDiagnosticLimit() {
        val result = formatMapUiLabelDiagnostics(
            listOf(
                MapUiLabelDiagnostic(
                    key = "mine",
                    present = true,
                    matchedNodeCount = 2,
                    directClickable = false,
                    directClickAction = false,
                    nearestExecutableAncestorDepth = null
                ),
                MapUiLabelDiagnostic(
                    key = "home",
                    present = true,
                    matchedNodeCount = 1,
                    directClickable = false,
                    directClickAction = true,
                    nearestExecutableAncestorDepth = 7
                )
            )
        )

        assertEquals(
            "minePresent=true,mineMatchedNodeCount=2," +
                    "mineDirectClickable=false," +
                    "mineDirectClickAction=false," +
                    "mineNearestExecutableAncestorDepth=none," +
                    "mineAncestorDepthClass=not_found_within_20," +
                    "homePresent=true,homeMatchedNodeCount=1," +
                    "homeDirectClickable=false," +
                    "homeDirectClickAction=true," +
                    "homeNearestExecutableAncestorDepth=7," +
                    "homeAncestorDepthClass=beyond_execution_limit",
            result
        )
    }
}
