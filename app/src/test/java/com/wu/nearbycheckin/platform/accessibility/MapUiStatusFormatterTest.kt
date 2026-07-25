package com.wu.nearbycheckin.platform.accessibility

import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.platform.maps.MapUiAction
import com.wu.nearbycheckin.platform.maps.MapUiPage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapUiStatusFormatterTest {

    // 验证结构化状态始终包含页面循环要求的全部字段。
    @Test
    fun buildsAllStructuredStatusFields() {
        assertEquals(
            "provider=BAIDU；package=com.baidu.BaiduMap；" +
                    "appVersion=21.18.50；" +
                    "profileVersion=baidu_21_18_50_v1；" +
                    "page=UNKNOWN；action=WAIT；" +
                    "evidence=等待 恢复 窗口 安全",
            buildMapUiEvidence(
                provider = MapProvider.BAIDU,
                packageName = "com.baidu.BaiduMap",
                appVersion = "21.18.50",
                profileVersion = "baidu_21_18_50_v1",
                page = MapUiPage.UNKNOWN,
                action = MapUiAction.WAIT,
                evidence = "等待\n恢复/窗口；安全"
            )
        )
    }

    // 验证追加超长证据时保留前置结构化字段并限制总长度。
    @Test
    fun preservesStructuredPrefixWhenLimitingWorkerEvidence() {
        val structuredPrefix = buildMapUiEvidence(
            provider = MapProvider.AMAP,
            packageName = "com.autonavi.minimap",
            appVersion = "16.21.0.2010",
            profileVersion = "amap_16_21_0_2010_v1",
            page = MapUiPage.SUCCESS,
            action = null,
            evidence = "签到成功"
        )
        val result = appendBoundedWorkerEvidence(
            structuredEvidence = structuredPrefix,
            key = "probeEvidence",
            value = "a".repeat(2_000)
        )

        assertTrue(result.startsWith(structuredPrefix))
        assertEquals(1_024, result.length)
    }

    // 验证统一状态文本保留事件和最终结构化结果证据。
    @Test
    fun includesStructuredResultEvidenceInStatusText() {
        val resultEvidence = buildMapUiEvidence(
            provider = MapProvider.BAIDU,
            packageName = "com.baidu.BaiduMap",
            appVersion = "21.18.50",
            profileVersion = "baidu_21_18_50_v1",
            page = MapUiPage.SUCCESS,
            action = null,
            evidence = "打卡成功"
        )

        assertEquals(
            "event=结果已回写；$resultEvidence",
            buildStructuredStatusText(
                event = "结果已回写",
                structuredEvidence = resultEvidence
            )
        )
    }
}
