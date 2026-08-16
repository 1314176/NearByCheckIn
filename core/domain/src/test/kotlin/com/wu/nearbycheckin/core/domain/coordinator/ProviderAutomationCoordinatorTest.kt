// @Time: 2026/08/16 02:09
// @Author: MrWu
// @File: ProviderAutomationCoordinatorTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.coordinator


import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

import com.wu.nearbycheckin.core.common.time.AppClock
import com.wu.nearbycheckin.core.domain.testing.TestCheckInTransactionRepository
import com.wu.nearbycheckin.core.model.AutomationSession
import com.wu.nearbycheckin.core.model.AutomationSessionStatus
import com.wu.nearbycheckin.core.model.AutomationSnapshot
import com.wu.nearbycheckin.core.model.MapOperationEvidence
import com.wu.nearbycheckin.core.model.MapOperationStatus
import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.core.model.ProviderAutomationState
import com.wu.nearbycheckin.core.model.ProviderPlaceCandidate
import com.wu.nearbycheckin.core.model.ProviderTaskStatus


class ProviderAutomationCoordinatorTest {

    @Test
    fun `百度列表读取会导入已完成并只入队未完成候选`() {
        val fixture = fixture()
        fixture.coordinator.beginCandidateRefresh(MapProvider.BAIDU)
        fixture.coordinator.replaceCandidates(
            provider = MapProvider.BAIDU,
            candidates = listOf(
                candidate("已完成", completed = true),
                candidate("待执行", completed = false)
            ),
            listFingerprint = "list-1"
        )

        val snapshot = fixture.repository.currentSnapshot()
        assertEquals(2, snapshot.providerCandidates.size)
        assertEquals(1, snapshot.providerCompletedKeys.size)
        assertEquals(1, snapshot.providerTasks.size)
        assertEquals(
            ProviderAutomationState.SELECTING,
            snapshot.providerRuntime.baiduState
        )
        assertFalse(snapshot.providerRuntime.baiduListStale)
    }

    @Test
    fun `成功任务写入永久键且重读列表不会重复入队`() {
        val fixture = fixture()
        val candidate = candidate("地点A", completed = false)
        fixture.coordinator.beginCandidateRefresh(MapProvider.BAIDU)
        fixture.coordinator.replaceCandidates(
            MapProvider.BAIDU,
            listOf(candidate),
            "list-1"
        )
        val task = fixture.coordinator.claimNextTask(
            MapProvider.BAIDU,
            "worker"
        )
        assertNotNull(task)
        fixture.coordinator.beginVerification(task!!.taskId, "command-1")
        fixture.coordinator.finishTask(
            task.taskId,
            evidence(
                taskId = task.taskId,
                status = MapOperationStatus.SUCCESS
            )
        )

        fixture.coordinator.beginCandidateRefresh(MapProvider.BAIDU)
        fixture.coordinator.replaceCandidates(
            MapProvider.BAIDU,
            listOf(candidate),
            "list-2"
        )
        val snapshot = fixture.repository.currentSnapshot()
        assertEquals(1, snapshot.providerTasks.size)
        assertEquals(
            ProviderTaskStatus.COMPLETED,
            snapshot.providerTasks.single().status
        )
        assertTrue(candidate.toPlaceKey() in snapshot.providerCompletedKeys)
    }

    @Test
    fun `位置更新只标记列表过期不会抢占验证任务`() {
        val fixture = fixture()
        fixture.repository.updateSnapshot { snapshot ->
            snapshot.copy(
                providerRuntime = snapshot.providerRuntime.copy(
                    baiduState = ProviderAutomationState.VERIFYING,
                    baiduListStale = false
                )
            )
        }

        fixture.coordinator.updateLocation(
            com.wu.nearbycheckin.core.model.LocationSnapshot(
                latitude = 31.2,
                longitude = 121.4,
                accuracyMeters = 10F,
                speedKmh = 5F,
                timestampMillis = fixture.clock.nowMillis()
            )
        )

        val runtime = fixture.repository.currentSnapshot().providerRuntime
        assertTrue(runtime.baiduListStale)
        assertEquals(ProviderAutomationState.VERIFYING, runtime.baiduState)
    }

    @Test
    fun `精度过差或过期的位置不会进入百度候选事实源`() {
        val fixture = fixture()

        fixture.coordinator.updateLocation(
            com.wu.nearbycheckin.core.model.LocationSnapshot(
                latitude = 31.2,
                longitude = 121.4,
                accuracyMeters = 500F,
                speedKmh = 120F,
                timestampMillis = fixture.clock.nowMillis()
            )
        )
        fixture.coordinator.updateLocation(
            com.wu.nearbycheckin.core.model.LocationSnapshot(
                latitude = 31.3,
                longitude = 121.5,
                accuracyMeters = 10F,
                speedKmh = 120F,
                timestampMillis = fixture.clock.nowMillis() - 30_001L
            )
        )

        assertEquals(
            null,
            fixture.repository.currentSnapshot()
                .providerRuntime.latestLocation
        )
    }

    @Test
    fun `只领取当前可见候选并刷新任务节点锚点`() {
        val fixture = fixture()
        val hidden = candidate("已滚出屏幕", completed = false)
        val visible = candidate("当前可见", completed = false)
        fixture.coordinator.beginCandidateRefresh(MapProvider.BAIDU)
        fixture.coordinator.replaceCandidates(
            MapProvider.BAIDU,
            listOf(hidden, visible),
            "list-1"
        )
        fixture.coordinator.beginCandidateRefresh(MapProvider.BAIDU)
        fixture.coordinator.replaceCandidates(
            MapProvider.BAIDU,
            listOf(visible.copy(fingerprint = "fresh-anchor")),
            "list-2"
        )

        val claimed = fixture.coordinator.claimNextTask(
            MapProvider.BAIDU,
            "worker"
        )

        assertNotNull(claimed)
        assertEquals("当前可见", claimed?.candidate?.name)
        assertEquals("fresh-anchor", claimed?.candidate?.fingerprint)
    }

    @Test
    fun `插件升级后只恢复当前可见的旧版失败任务`() {
        val fixture = fixture()
        val candidate = candidate("地点A", completed = false)
        fixture.coordinator.beginCandidateRefresh(MapProvider.BAIDU)
        fixture.coordinator.replaceCandidates(
            MapProvider.BAIDU,
            listOf(candidate),
            "list-1",
            profileVersion = "baidu-v1"
        )
        fixture.repository.updateSnapshot { snapshot ->
            snapshot.copy(
                providerTasks = snapshot.providerTasks.map { task ->
                    task.copy(
                        status = ProviderTaskStatus.FAILED,
                        attempts = 3,
                        lastError = "旧版定位失败",
                        evidence = evidence(
                            taskId = task.taskId,
                            status = MapOperationStatus.FAILED
                        ).copy(profileVersion = "baidu-v1")
                    )
                }
            )
        }

        fixture.coordinator.beginCandidateRefresh(MapProvider.BAIDU)
        fixture.coordinator.replaceCandidates(
            MapProvider.BAIDU,
            listOf(candidate.copy(fingerprint = "fresh-anchor")),
            "list-2",
            profileVersion = "baidu-v2"
        )

        val task = fixture.repository.currentSnapshot()
            .providerTasks.single()
        assertEquals(ProviderTaskStatus.QUEUED, task.status)
        assertEquals(0, task.attempts)
        assertEquals("fresh-anchor", task.candidate.fingerprint)
        assertEquals(null, task.evidence)
    }

    private fun fixture(): Fixture {
        val clock = MutableClock(10_000L)
        val repository = TestCheckInTransactionRepository(
            AutomationSnapshot(
                session = AutomationSession(
                    sessionId = "session",
                    startedAtMillis = clock.nowMillis(),
                    stoppedAtMillis = 0L,
                    status = AutomationSessionStatus.RUNNING
                ),
                providerRuntime =
                    com.wu.nearbycheckin.core.model.DualMapRuntimeState(
                        baiduState = ProviderAutomationState.READY
                    )
            )
        )
        var id = 0
        return Fixture(
            repository = repository,
            clock = clock,
            coordinator = ProviderAutomationCoordinator(
                repository = repository,
                clock = clock,
                taskIdFactory = { "task-${++id}" }
            )
        )
    }

    private fun candidate(
        name: String,
        completed: Boolean
    ): ProviderPlaceCandidate {
        return ProviderPlaceCandidate(
            provider = MapProvider.BAIDU,
            name = name,
            address = "上海市地址",
            distanceText = "100m",
            completed = completed,
            fingerprint = "fp-$name",
            observedAtMillis = 10_000L,
            stableHint = "stable-$name"
        )
    }

    private fun evidence(
        taskId: String,
        status: MapOperationStatus
    ): MapOperationEvidence {
        return MapOperationEvidence(
            provider = MapProvider.BAIDU,
            taskId = taskId,
            commandId = "command-1",
            profileVersion = "baidu-v1",
            page = "SUCCESS",
            matchedText = "打卡成功",
            viewId = "success",
            beforeFingerprint = "before",
            afterFingerprint = "after",
            resultStatus = status,
            message = "完成",
            observedAtMillis = 10_000L
        )
    }

    private data class Fixture(
        val repository: TestCheckInTransactionRepository,
        val clock: MutableClock,
        val coordinator: ProviderAutomationCoordinator
    )

    private class MutableClock(
        private var value: Long
    ) : AppClock {
        // 返回测试固定时间。
        override fun nowMillis(): Long = value
    }
}
