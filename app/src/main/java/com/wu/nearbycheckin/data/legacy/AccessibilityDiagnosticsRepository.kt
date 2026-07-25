// @Time: 2026/07/20 18:32
// @Author: MrWu
// @File: AccessibilityDiagnosticsRepository.kt
// @Software: Android Studio


@file:Suppress("SpellCheckingInspection")


package com.wu.nearbycheckin.data.legacy


import android.content.Context
import androidx.core.content.edit


/**
 * 保存一次双地图无障碍窗口诊断的摘要状态。
 *
 * @property baiduReady 百度地图窗口是否已识别。
 * @property amapReady 高德地图窗口是否已识别。
 * @property totalWindowCount 本次诊断读取到的窗口总数。
 * @property targetWindowCount 本次诊断读取到的目标地图窗口数量。
 * @property targetNodeCount 本次诊断输出的目标地图节点数量。
 * @property baiduClickableNodeCount 百度地图可点击节点数量。
 * @property baiduCandidateSummary 百度地图候选可点击节点摘要。
 * @property amapClickableNodeCount 高德地图可点击节点数量。
 * @property amapCandidateSummary 高德地图候选可点击节点摘要。
 * @property reason 本次诊断触发原因。
 * @property lastUpdatedMillis 本次诊断写入时间戳。
 */
data class AccessibilityDiagnosticsSnapshot(
    val baiduReady: Boolean,
    val amapReady: Boolean,
    val totalWindowCount: Int,
    val targetWindowCount: Int,
    val targetNodeCount: Int,
    val baiduClickableNodeCount: Int,
    val baiduCandidateSummary: String,
    val amapClickableNodeCount: Int,
    val amapCandidateSummary: String,
    val reason: String,
    val lastUpdatedMillis: Long
)

/**
 * 负责持久化双地图无障碍诊断摘要，供主界面读取最近状态。
 *
 * @param context 当前应用上下文。
 */
class AccessibilityDiagnosticsRepository(
    context: Context
) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 保存最近一次双地图窗口诊断摘要。
     *
     * @param snapshot 需要保存的诊断摘要。
     */
    fun saveSnapshot(snapshot: AccessibilityDiagnosticsSnapshot) {
        preferences.edit {
            putBoolean(
                BAIDU_READY_KEY,
                snapshot.baiduReady
            )
            putBoolean(
                AMAP_READY_KEY,
                snapshot.amapReady
            )
            putInt(
                TOTAL_WINDOW_COUNT_KEY,
                snapshot.totalWindowCount
            )
            putInt(
                TARGET_WINDOW_COUNT_KEY,
                snapshot.targetWindowCount
            )
            putInt(
                TARGET_NODE_COUNT_KEY,
                snapshot.targetNodeCount
            )
            putInt(
                BAIDU_CLICKABLE_NODE_COUNT_KEY,
                snapshot.baiduClickableNodeCount
            )
            putString(
                BAIDU_CANDIDATE_SUMMARY_KEY,
                snapshot.baiduCandidateSummary
            )
            putInt(
                AMAP_CLICKABLE_NODE_COUNT_KEY,
                snapshot.amapClickableNodeCount
            )
            putString(
                AMAP_CANDIDATE_SUMMARY_KEY,
                snapshot.amapCandidateSummary
            )
            putString(
                REASON_KEY,
                snapshot.reason
            )
            putLong(
                LAST_UPDATED_MILLIS_KEY,
                snapshot.lastUpdatedMillis
            )
        }
    }

    /**
     * 读取最近一次双地图窗口诊断摘要。
     *
     * @return 已有诊断结果时返回摘要，否则返回null。
     */
    fun getLatestSnapshot(): AccessibilityDiagnosticsSnapshot? {
        val lastUpdatedMillis = preferences.getLong(
            LAST_UPDATED_MILLIS_KEY,
            0L
        )

        if (lastUpdatedMillis <= 0L) {
            return null
        }

        return AccessibilityDiagnosticsSnapshot(
            baiduReady = preferences.getBoolean(
                BAIDU_READY_KEY,
                false
            ),
            amapReady = preferences.getBoolean(
                AMAP_READY_KEY,
                false
            ),
            totalWindowCount = preferences.getInt(
                TOTAL_WINDOW_COUNT_KEY,
                0
            ),
            targetWindowCount = preferences.getInt(
                TARGET_WINDOW_COUNT_KEY,
                0
            ),
            targetNodeCount = preferences.getInt(
                TARGET_NODE_COUNT_KEY,
                0
            ),
            baiduClickableNodeCount = preferences.getInt(
                BAIDU_CLICKABLE_NODE_COUNT_KEY,
                0
            ),
            baiduCandidateSummary = preferences.getString(
                BAIDU_CANDIDATE_SUMMARY_KEY,
                ""
            ).orEmpty(),
            amapClickableNodeCount = preferences.getInt(
                AMAP_CLICKABLE_NODE_COUNT_KEY,
                0
            ),
            amapCandidateSummary = preferences.getString(
                AMAP_CANDIDATE_SUMMARY_KEY,
                ""
            ).orEmpty(),
            reason = preferences.getString(
                REASON_KEY,
                ""
            ).orEmpty(),
            lastUpdatedMillis = lastUpdatedMillis
        )
    }

    /**
     * 请求无障碍服务自动打开百度和高德并尝试进入系统分屏。
     */
    fun requestAutoSplitLaunch() {
        preferences.edit {
            putLong(
                AUTO_SPLIT_REQUEST_TIME_MILLIS_KEY,
                System.currentTimeMillis()
            )
            putString(
                AUTO_SPLIT_STATUS_KEY,
                "已请求自动打开双地图分屏"
            )
        }
    }

    /**
     * 消费一次待处理的自动分屏请求，避免重复执行系统分屏动作。
     *
     * @return 存在待处理请求时返回请求时间戳，否则返回null。
     */
    fun consumeAutoSplitLaunchRequest(): Long? {
        val requestTimeMillis = preferences.getLong(
            AUTO_SPLIT_REQUEST_TIME_MILLIS_KEY,
            0L
        )

        if (requestTimeMillis <= 0L) {
            return null
        }

        preferences.edit {
            remove(
                AUTO_SPLIT_REQUEST_TIME_MILLIS_KEY
            )
        }

        return requestTimeMillis
    }

    /**
     * 清理尚未被无障碍服务消费的自动分屏请求，避免历史请求在下次打开软件时继续执行。
     */
    fun clearPendingAutoSplitLaunchRequest() {
        preferences.edit {
            remove(
                AUTO_SPLIT_REQUEST_TIME_MILLIS_KEY
            )
        }
    }

    /**
     * 保存最近一次自动分屏执行状态，供主界面展示。
     *
     * @param statusText 自动分屏执行状态说明。
     */
    fun saveAutoSplitLaunchStatus(statusText: String) {
        preferences.edit {
            putString(
                AUTO_SPLIT_STATUS_KEY,
                statusText
            )
        }
    }

    /**
     * 读取最近一次自动分屏执行状态。
     *
     * @return 自动分屏状态文本；没有状态时返回空字符串。
     */
    fun getAutoSplitLaunchStatus(): String {
        return preferences.getString(
            AUTO_SPLIT_STATUS_KEY,
            ""
        ).orEmpty()
    }

    private companion object {
        const val PREFERENCES_NAME = "accessibility_diagnostics"
        const val BAIDU_READY_KEY = "baidu_ready"
        const val AMAP_READY_KEY = "amap_ready"
        const val TOTAL_WINDOW_COUNT_KEY = "total_window_count"
        const val TARGET_WINDOW_COUNT_KEY = "target_window_count"
        const val TARGET_NODE_COUNT_KEY = "target_node_count"
        const val BAIDU_CLICKABLE_NODE_COUNT_KEY = "baidu_clickable_node_count"
        const val BAIDU_CANDIDATE_SUMMARY_KEY = "baidu_candidate_summary"
        const val AMAP_CLICKABLE_NODE_COUNT_KEY = "amap_clickable_node_count"
        const val AMAP_CANDIDATE_SUMMARY_KEY = "amap_candidate_summary"
        const val REASON_KEY = "reason"
        const val LAST_UPDATED_MILLIS_KEY = "last_updated_millis"
        const val AUTO_SPLIT_REQUEST_TIME_MILLIS_KEY =
            "auto_split_request_time_millis"
        const val AUTO_SPLIT_STATUS_KEY = "auto_split_status"
    }
}
