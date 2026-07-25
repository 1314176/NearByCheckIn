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
