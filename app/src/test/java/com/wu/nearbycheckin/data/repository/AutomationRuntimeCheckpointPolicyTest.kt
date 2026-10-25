// @Time: 2026/09/05 15:12
// @Author: MrWu
// @File: AutomationRuntimeCheckpointPolicyTest.kt
// @Software: Android Studio

package com.wu.nearbycheckin.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import com.wu.nearbycheckin.core.model.AutomationSnapshot
import com.wu.nearbycheckin.core.model.LocationSnapshot
import com.wu.nearbycheckin.core.model.MovementClass
import com.wu.nearbycheckin.core.model.ProviderPlaceKey
import com.wu.nearbycheckin.core.model.MapProvider

class AutomationRuntimeCheckpointPolicyTest {

    // 五小时每秒一次的高速定位只改变小检查点，保留全部历史完成键。
    @Test
    fun fiveHourHighwayLocationsDoNotRewriteHistory() {
        var state = AutomationSnapshot(providerCompletedKeys = (1..1000).map {
            ProviderPlaceKey(MapProvider.BAIDU, "地点$it", "地址", "", "$it")
        }.toSet())
        repeat(18_000) { second ->
            val updated = state.copy(providerRuntime = state.providerRuntime.copy(
                latestLocation = LocationSnapshot(30.0 + second * 0.00001, 121.0,
                    3.8F, 120F, 1_000L + second * 1_000L, MovementClass.FAST_MOVING)
            ))
            assertTrue(AutomationRuntimeCheckpointPolicy.canCheckpoint(state, updated))
            val compact = AutomationRuntimeCheckpointPolicy.extract(updated)
            assertTrue(compact.providerCompletedKeys.isEmpty())
            assertEquals(updated, AutomationRuntimeCheckpointPolicy.merge(compact, state))
            state = updated
        }
        assertEquals(1000, state.providerCompletedKeys.size)
    }

    // 永久完成键或分屏请求变化必须使用完整原子提交。
    @Test
    fun durableChangesNeverUseRuntimeOnlyCheckpoint() {
        val state = AutomationSnapshot()
        assertFalse(AutomationRuntimeCheckpointPolicy.canCheckpoint(state,
            state.copy(providerCompletedKeys = setOf(ProviderPlaceKey(
                MapProvider.AMAP, "地点", "地址", "31,121", "location")))))
        assertFalse(AutomationRuntimeCheckpointPolicy.canCheckpoint(state,
            state.copy(autoSplitRequestedAtMillis = 123)))
    }

    // 模拟写完新事务后进程退出，旧检查点不能把新状态回退。
    @Test
    fun ignoresStaleCheckpointAfterDurableCommit() {
        val durable = AutomationSnapshot(automationStatus = "新事务已完成")
        val stale = AutomationSnapshot(automationStatus = "旧事务正在验证")
        assertEquals(durable, AutomationRuntimeCheckpointPolicy.restore(durable, stale, "new", "old"))
        assertEquals(stale, AutomationRuntimeCheckpointPolicy.restore(durable, stale, "new", "new"))
        assertEquals(durable, AutomationRuntimeCheckpointPolicy.restore(durable, stale, "", ""))
        assertEquals(durable, AutomationRuntimeCheckpointPolicy.restore(durable, null, "new", "new"))
    }
}
