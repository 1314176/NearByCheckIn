// @Time: 2026/07/26 02:49
// @Author: MrWu
// @File: ClearRecordsUseCaseTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.usecase


import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import com.wu.nearbycheckin.core.domain.repository.LegacyDataCleaner
import com.wu.nearbycheckin.core.domain.testing.TestCheckInTransactionRepository
import com.wu.nearbycheckin.core.model.AutomationSnapshot
import com.wu.nearbycheckin.core.model.CheckInRecord
import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.core.model.ProviderCheckInTask
import com.wu.nearbycheckin.core.model.ProviderPlaceCandidate
import com.wu.nearbycheckin.core.model.ProviderTaskStatus


class ClearRecordsUseCaseTest {

    // 验证未确认时不清理新旧数据。
    @Test
    fun doesNotClearWithoutConfirmation() {
        val initialSnapshot = snapshotWithRecord()
        val repository =
            TestCheckInTransactionRepository(initialSnapshot)
        val legacyCleaner = RecordingLegacyDataCleaner()
        val useCase = ClearRecordsUseCase(
            repository = repository,
            legacyDataCleaner = legacyCleaner
        )

        val result = useCase(
            scope = ClearScope.ALL,
            confirmed = false
        )

        assertFalse(result.cleared)
        assertTrue(result.confirmationRequired)
        assertEquals(initialSnapshot, repository.currentSnapshot())
        assertFalse(legacyCleaner.called)
    }

    // 验证确认全量清理时同时清理新仓储和旧数据。
    @Test
    fun clearsNewAndLegacyDataWhenConfirmed() {
        val repository =
            TestCheckInTransactionRepository(snapshotWithRecord())
        val legacyCleaner = RecordingLegacyDataCleaner()
        val useCase = ClearRecordsUseCase(
            repository = repository,
            legacyDataCleaner = legacyCleaner
        )

        val result = useCase(
            scope = ClearScope.ALL,
            confirmed = true
        )

        assertTrue(result.cleared)
        assertFalse(result.confirmationRequired)
        assertEquals(AutomationSnapshot(), repository.currentSnapshot())
        assertTrue(legacyCleaner.called)
    }

    // 验证百度定向清理保留高德键和全部历史记录。
    @Test
    fun clearsOnlyBaiduCompletionKeysAndTerminalTasks() {
        val baiduCandidate = providerCandidate(MapProvider.BAIDU, "百度地点")
        val amapCandidate = providerCandidate(MapProvider.AMAP, "高德地点")
        val initial = snapshotWithRecord().copy(
            providerCompletedKeys = setOf(
                baiduCandidate.toPlaceKey(),
                amapCandidate.toPlaceKey()
            ),
            providerTasks = listOf(
                providerTask(baiduCandidate),
                providerTask(amapCandidate)
            )
        )
        val repository = TestCheckInTransactionRepository(initial)
        val useCase = ClearRecordsUseCase(
            repository,
            RecordingLegacyDataCleaner()
        )

        val result = useCase(
            ClearScope.BAIDU_PERMANENT_COMPLETION_KEYS,
            confirmed = true
        )

        val updated = repository.currentSnapshot()
        assertTrue(result.cleared)
        assertEquals(1, updated.records.size)
        assertEquals(
            setOf(amapCandidate.toPlaceKey()),
            updated.providerCompletedKeys
        )
        assertEquals(listOf(MapProvider.AMAP), updated.providerTasks.map {
            task -> task.provider
        })
    }

    // 创建 Provider 候选测试数据。
    private fun providerCandidate(
        provider: MapProvider,
        name: String
    ): ProviderPlaceCandidate {
        return ProviderPlaceCandidate(
            provider = provider,
            name = name,
            address = "测试地址",
            distanceText = "10米",
            completed = false,
            fingerprint = "$provider-$name",
            observedAtMillis = 1_000L,
            stableHint = name
        )
    }

    // 创建已完成 Provider 任务测试数据。
    private fun providerTask(
        candidate: ProviderPlaceCandidate
    ): ProviderCheckInTask {
        return ProviderCheckInTask(
            taskId = candidate.name,
            sessionId = "session",
            provider = candidate.provider,
            candidate = candidate,
            candidateKey = candidate.toPlaceKey(),
            status = ProviderTaskStatus.COMPLETED,
            attempts = 1,
            commandId = "command",
            createdAtMillis = 1_000L,
            updatedAtMillis = 1_000L
        )
    }

    // 构造包含一条记录的非空快照。
    private fun snapshotWithRecord(): AutomationSnapshot {
        return AutomationSnapshot(
            records = listOf(
                CheckInRecord(
                    id = "record",
                    poiUid = "record",
                    poiName = "测试地点",
                    address = "测试地址",
                    category = "测试分类",
                    latitude = 31.0,
                    longitude = 121.0,
                    distanceMeters = 10,
                    stayDurationMillis = 1_000L,
                    checkInTimeMillis = 1_000L
                )
            )
        )
    }
}

private class RecordingLegacyDataCleaner : LegacyDataCleaner {

    var called = false

    // 记录旧数据清理调用并返回成功。
    override fun clearLegacyData(): Boolean {
        called = true
        return true
    }
}
