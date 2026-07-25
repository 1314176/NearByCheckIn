// @Time: 2026/07/20 18:18
// @Author: MrWu
// @File: ClearRecordsUseCase.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.usecase


import com.wu.nearbycheckin.core.domain.repository.CheckInTransactionRepository
import com.wu.nearbycheckin.core.domain.repository.LegacyDataCleaner
import com.wu.nearbycheckin.core.model.DualMapTransactionStatus


/**
 * 标识用户确认后允许清理的数据范围。
 */
enum class ClearScope {
    HISTORY,
    FAILED_TRANSACTIONS,
    PERMANENT_COMPLETION_KEYS,
    ALL
}

/**
 * 保存清理用例的明确执行结果。
 */
data class ClearResult(
    val cleared: Boolean,
    val confirmationRequired: Boolean
)

/**
 * 在二次确认后原子清理指定范围的数据。
 */
class ClearRecordsUseCase(
    private val repository: CheckInTransactionRepository,
    private val legacyDataCleaner: LegacyDataCleaner
) {

    // 根据范围执行清理，未确认时不修改任何数据。
    operator fun invoke(
        scope: ClearScope,
        confirmed: Boolean
    ): ClearResult {
        if (!confirmed) {
            return ClearResult(
                cleared = false,
                confirmationRequired = true
            )
        }

        val cleared = when (scope) {
            ClearScope.HISTORY -> clearHistory()
            ClearScope.FAILED_TRANSACTIONS ->
                clearFailedTransactions()
            ClearScope.PERMANENT_COMPLETION_KEYS ->
                clearPermanentKeys()
            ClearScope.ALL ->
                repository.clearAll() &&
                        legacyDataCleaner.clearLegacyData()
        }
        return ClearResult(
            cleared = cleared,
            confirmationRequired = false
        )
    }

    // 只清理新架构中的展示记录。
    private fun clearHistory(): Boolean {
        repository.updateSnapshot { snapshot ->
            snapshot.copy(
                records = emptyList(),
                legacyDualMapRecords = emptyList()
            )
        }
        return true
    }

    // 清理失败事务及其命令并保留成功历史。
    private fun clearFailedTransactions(): Boolean {
        repository.updateSnapshot { snapshot ->
            val failedIds = snapshot.transactions
                .filter { transaction ->
                    transaction.state.status ==
                            DualMapTransactionStatus.FAILED
                }
                .mapTo(mutableSetOf()) { transaction ->
                    transaction.transactionId
                }
            snapshot.copy(
                transactions = snapshot.transactions.filterNot {
                    it.transactionId in failedIds
                },
                commands = snapshot.commands.filterNot {
                    it.transactionId in failedIds
                }
            )
        }
        return true
    }

    // 只清理永久去重键并保留历史记录。
    private fun clearPermanentKeys(): Boolean {
        repository.updateSnapshot { snapshot ->
            snapshot.copy(completedPlaceKeys = emptySet())
        }
        return true
    }
}
