// @Time: 2026/09/05 15:10
// @Author: MrWu
// @File: AutomationRuntimeCheckpointPolicy.kt
// @Software: Android Studio

package com.wu.nearbycheckin.data.repository

import com.wu.nearbycheckin.core.model.AutomationSnapshot

internal object AutomationRuntimeCheckpointPolicy {

    // 仅运行态发生变化时使用小检查点，任务、完成键、记录和会话仍走完整原子提交。
    fun canCheckpoint(previous: AutomationSnapshot, updated: AutomationSnapshot): Boolean {
        return merge(updated, previous) == updated
    }

    // 提取小体积运行态，避免每秒定位更新重新编码全部历史任务。
    fun extract(snapshot: AutomationSnapshot): AutomationSnapshot {
        return AutomationSnapshot(
            providerRuntime = snapshot.providerRuntime,
            diagnostics = snapshot.diagnostics,
            automationStatus = snapshot.automationStatus,
            runtimeLogs = snapshot.runtimeLogs
        )
    }

    // 检查点只能覆盖运行态，不能覆盖永久去重键、任务和打卡记录。
    fun merge(runtime: AutomationSnapshot, durable: AutomationSnapshot): AutomationSnapshot {
        return durable.copy(
            providerRuntime = runtime.providerRuntime,
            diagnostics = runtime.diagnostics,
            automationStatus = runtime.automationStatus,
            runtimeLogs = runtime.runtimeLogs
        )
    }

    // 仅合并同一完整提交代次的检查点，崩溃残留的旧代次不得覆盖新状态。
    fun restore(durable: AutomationSnapshot, runtime: AutomationSnapshot?,
                durableRevision: String, runtimeRevision: String): AutomationSnapshot {
        return if (runtime != null && durableRevision.isNotBlank() &&
            durableRevision == runtimeRevision
        ) merge(runtime, durable) else durable
    }
}
