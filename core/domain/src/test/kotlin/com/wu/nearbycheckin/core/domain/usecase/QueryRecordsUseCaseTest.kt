// @Time: 2026/07/26 02:49
// @Author: MrWu
// @File: QueryRecordsUseCaseTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.usecase


import java.time.ZoneId
import java.time.ZonedDateTime

import org.junit.Assert.assertEquals
import org.junit.Test

import com.wu.nearbycheckin.core.common.time.AppClock
import com.wu.nearbycheckin.core.domain.testing.TestCheckInTransactionRepository
import com.wu.nearbycheckin.core.model.AutomationSnapshot
import com.wu.nearbycheckin.core.model.CheckInRecord


class QueryRecordsUseCaseTest {

    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val fixedNowMillis = ZonedDateTime.of(
        2026,
        7,
        26,
        12,
        0,
        0,
        0,
        zoneId
    ).toInstant().toEpochMilli()

    // 验证 TODAY 只返回固定时区内的当天记录。
    @Test
    fun returnsOnlyTodayRecordsForFixedClock() {
        val repository = repositoryWithTodayAndYesterday()
        val useCase = QueryRecordsUseCase(
            repository = repository,
            clock = AppClock { fixedNowMillis },
            zoneId = zoneId
        )

        val records = useCase(RecordQueryScope.TODAY)

        assertEquals(listOf("today"), records.map(CheckInRecord::id))
    }

    // 验证 ALL 按完成时间从新到旧返回全部记录。
    @Test
    fun returnsAllRecordsNewestFirst() {
        val repository = repositoryWithTodayAndYesterday()
        val useCase = QueryRecordsUseCase(
            repository = repository,
            clock = AppClock { fixedNowMillis },
            zoneId = zoneId
        )

        val records = useCase(RecordQueryScope.ALL)

        assertEquals(
            listOf("today", "yesterday"),
            records.map(CheckInRecord::id)
        )
    }

    // 构造包含今天和昨天记录的内存仓储。
    private fun repositoryWithTodayAndYesterday(): TestCheckInTransactionRepository {
        return TestCheckInTransactionRepository(
            AutomationSnapshot(
                records = listOf(
                    record(
                        id = "yesterday",
                        timestampMillis =
                            fixedNowMillis - 24 * 60 * 60 * 1_000L
                    ),
                    record(
                        id = "today",
                        timestampMillis = fixedNowMillis - 1_000L
                    )
                )
            )
        )
    }

    // 构造用于日期筛选的完整打卡记录。
    private fun record(
        id: String,
        timestampMillis: Long
    ): CheckInRecord {
        return CheckInRecord(
            id = id,
            poiUid = id,
            poiName = id,
            address = "测试地址",
            category = "测试分类",
            latitude = 31.0,
            longitude = 121.0,
            distanceMeters = 10,
            stayDurationMillis = 1_000L,
            checkInTimeMillis = timestampMillis
        )
    }
}
