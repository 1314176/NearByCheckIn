// @Time: 2026/07/20 18:18
// @Author: MrWu
// @File: QueryRecordsUseCase.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.usecase


import java.time.Instant
import java.time.ZoneId

import com.wu.nearbycheckin.core.common.time.AppClock
import com.wu.nearbycheckin.core.domain.repository.CheckInTransactionRepository
import com.wu.nearbycheckin.core.model.CheckInRecord


/**
 * 标识主界面支持的记录查询范围。
 */
enum class RecordQueryScope {
    TODAY,
    ALL
}

/**
 * 从唯一事实来源查询已完成记录。
 */
class QueryRecordsUseCase(
    private val repository: CheckInTransactionRepository,
    private val clock: AppClock,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {

    // 按查询范围返回从新到旧排列的记录。
    operator fun invoke(
        scope: RecordQueryScope
    ): List<CheckInRecord> {
        val records = repository.currentSnapshot().records
        return records
            .filter { record ->
                scope == RecordQueryScope.ALL ||
                        record.isToday()
            }
            .sortedByDescending { record ->
                record.checkInTimeMillis
            }
    }

    // 判断记录时间是否属于设备当前时区的今天。
    private fun CheckInRecord.isToday(): Boolean {
        val date = Instant.ofEpochMilli(checkInTimeMillis)
            .atZone(zoneId)
            .toLocalDate()
        val today = Instant.ofEpochMilli(clock.nowMillis())
            .atZone(zoneId)
            .toLocalDate()
        return date == today
    }
}
