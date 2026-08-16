// @Time: 2026/08/16 01:59
// @Author: MrWu
// @File: MapAutomationPluginRegistryTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.plugin


import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

import com.wu.nearbycheckin.core.model.MapOperationEvidence
import com.wu.nearbycheckin.core.model.MapOperationStatus
import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.core.model.ProviderPlaceCandidate


class MapAutomationPluginRegistryTest {

    @Test
    fun `百度真实插件可解析且高德保持 legacy 不阻塞`() {
        val baidu = FakePlugin(MapProvider.BAIDU)
        val registry = MapAutomationPluginRegistry(
            listOf(
                MapAutomationPluginEntry(
                    provider = MapProvider.BAIDU,
                    availability = PluginAvailability.ENABLED,
                    plugin = baidu
                ),
                MapAutomationPluginEntry(
                    provider = MapProvider.AMAP,
                    availability = PluginAvailability.LEGACY,
                    reason = "百度 MVP 后接入"
                )
            )
        )

        assertSame(baidu, registry.resolve(MapProvider.BAIDU))
        assertNull(registry.resolve(MapProvider.AMAP))
        assertEquals(
            PluginAvailability.LEGACY,
            registry.entry(MapProvider.AMAP).availability
        )
    }

    @Test
    fun `缺失注册项显式返回 disabled`() {
        val registry = MapAutomationPluginRegistry(emptyList())

        assertEquals(
            PluginAvailability.DISABLED,
            registry.entry(MapProvider.BAIDU).availability
        )
    }

    private class FakePlugin(
        override val provider: MapProvider
    ) : MapAutomationPlugin {
        override val profileVersion = "fake-v1"

        // 返回固定页面以满足注册表测试。
        override fun inspect(window: MapWindowSnapshot): PluginPageState {
            return PluginPageState(
                page = PluginPage.HOME,
                profileVersion = profileVersion,
                fingerprint = window.fingerprint(),
                evidence = "fake"
            )
        }

        // 返回空候选集合以满足注册表测试。
        override fun readCandidates(
            window: MapWindowSnapshot
        ): List<ProviderPlaceCandidate> = emptyList()

        // 返回等待命令以满足注册表测试。
        override fun buildNextAction(
            state: PluginPageState
        ): PluginCommand? = null

        // 返回候选选择命令以满足注册表测试。
        override fun buildSelectAction(
            candidate: ProviderPlaceCandidate
        ): PluginCommand {
            return PluginCommand(
                commandId = "fake",
                taskId = "fake",
                provider = provider,
                type = PluginCommandType.SELECT_CANDIDATE
            )
        }

        // 返回固定未知证据以满足注册表测试。
        override fun verifyResult(
            window: MapWindowSnapshot
        ): MapOperationEvidence {
            return MapOperationEvidence(
                provider = provider,
                taskId = "",
                commandId = "",
                profileVersion = profileVersion,
                page = "HOME",
                matchedText = "",
                viewId = "",
                beforeFingerprint = "",
                afterFingerprint = window.fingerprint(),
                resultStatus = MapOperationStatus.UNKNOWN,
                message = "fake",
                observedAtMillis = window.capturedAtMillis
            )
        }
    }
}
