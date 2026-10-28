// @Time: 2026/10/26 00:26
// @Author: MrWu
// @File: CheckInRangeRecoveryPolicy.kt
// @Software: Android Studio

package com.wu.nearbycheckin.core.domain.plugin

object CheckInRangeEvidencePolicy {

    // 仅识别明确拒绝打卡的距离或范围提示，避免把正常范围说明和导航距离当成失败。
    fun matches(message: String): Boolean {
        val text = message.filterNot(Char::isWhitespace)
        val checkIn = text.contains("打卡") || text.contains("签到")
        val distance = text.contains("范围") || text.contains("距离") ||
                text.contains("太远") || text.contains("过远")
        val rejected = listOf("超出", "超过", "已超", "不在", "未在", "离开", "太远", "过远")
            .any(text::contains)
        return checkIn && distance && rejected
    }

    // 按明确确认文案绑定当前提示的关闭节点，缺少可见按钮时交给安全返回处理。
    fun dismissTarget(nodes: List<PluginNodeSnapshot>): PluginCommandTarget? {
        for (label in listOf("知道了", "我知道了", "确定", "好的", "关闭", "返回")) {
            val node = nodes.firstOrNull {
                it.enabled && it.right > it.left && it.bottom > it.top &&
                        it.labels().any { value -> value == label ||
                                value.startsWith("$label,") || value.startsWith("$label，") }
            } ?: continue
            return PluginCommandTarget(labels = listOf(label), nodeFingerprint = node.fingerprint)
        }
        return null
    }
}

class CheckInRangeRecoveryPolicy {
    var isRecovering: Boolean = false
        private set
    private var locationTimestampBeforeFailure = 0L
    private var signal: RangeSignal? = null

    // 瞬时提示绑定当前任务和命令，不能把旧 Toast 套用到后续地点。
    fun record(taskId: String, commandId: String, message: String, nowMillis: Long): Boolean {
        if (!isRecovering && taskId.isNotBlank() && CheckInRangeEvidencePolicy.matches(message) &&
            signal?.let { it.taskId == taskId && it.commandId == commandId && it.message == message } != true
        ) {
            signal = RangeSignal(taskId, commandId, message, nowMillis)
            return true
        }
        return false
    }

    // 每条提示最多消费一次，只接受十秒内且任务和命令完全一致的证据。
    fun consume(taskId: String, commandId: String, nowMillis: Long): String? {
        val current = signal ?: return null
        signal = null
        return current.message.takeIf {
            current.taskId == taskId && current.commandId == commandId &&
                    nowMillis - current.observedAtMillis in 0L..10_000L
        }
    }

    // 一次范围失败只撤销一次旧队列，并记住失败时的定位基准。
    fun begin(locationTimestampMillis: Long): Boolean {
        if (isRecovering) return false
        isRecovering = true
        locationTimestampBeforeFailure = locationTimestampMillis
        signal = null
        return true
    }

    // 必须先离开旧列表和结果页，且获得更新的有效定位，才能重新提交地点。
    fun canResume(page: PluginPage, locationTimestampMillis: Long?, locationFresh: Boolean): Boolean {
        return isRecovering && page in setOf(PluginPage.HOME, PluginPage.MINE,
            PluginPage.FOOTPRINT, PluginPage.CHECK_IN_ENTRY) && locationFresh &&
                locationTimestampMillis != null &&
                locationTimestampMillis > locationTimestampBeforeFailure
    }

    // 恢复完成或 Worker 停止时释放恢复状态和未消费的瞬时证据。
    fun reset() {
        isRecovering = false
        locationTimestampBeforeFailure = 0L
        signal = null
    }

    private data class RangeSignal(
        val taskId: String,
        val commandId: String,
        val message: String,
        val observedAtMillis: Long
    )
}
