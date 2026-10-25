// @Time: 2026/08/16 02:09
// @Author: MrWu
// @File: ProviderAutomationCoordinatorTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.coordinator


import java.time.Instant
import java.time.ZoneId

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

    // 暂时失败耗尽三次后冷却恢复，不能要求用户升级插件才能再次尝试。
    @Test
    fun transientFailureResumesAfterBoundedCooldown() {
        val fixture = fixture()
        val candidate = candidate("服务区地点", false)
        fixture.coordinator.replaceCandidates(MapProvider.BAIDU, listOf(candidate), "list", "v1")
        fixture.repository.updateSnapshot { state -> state.copy(providerTasks = state.providerTasks.map {
            it.copy(status = ProviderTaskStatus.FAILED, attempts = 3, updatedAtMillis = fixture.clock.value,
                evidence = evidence(it.taskId, MapOperationStatus.TIMEOUT).copy(profileVersion = "v1"))
        }) }
        fixture.clock.value += 59_999
        fixture.coordinator.replaceCandidates(MapProvider.BAIDU, listOf(candidate), "list", "v1")
        assertEquals(null, fixture.coordinator.claimNextTask(MapProvider.BAIDU, "worker"))
        fixture.clock.value += 1
        fixture.coordinator.replaceCandidates(MapProvider.BAIDU, listOf(candidate), "list", "v1")
        val retried = requireNotNull(fixture.coordinator.claimNextTask(MapProvider.BAIDU, "worker"))
        assertEquals(1, retried.attempts)
        assertTrue(fixture.repository.currentSnapshot().providerCompletedKeys.isEmpty())
    }

    // 回放高速、服务区静止、再次驶出，百度持续成功时高德超时不得阻塞其队列。
    @Test
    fun highwayStopAndDepartureKeepProviderQueuesIndependent() {
        val fixture = fixture()
        val speeds = listOf(120F, 150F, 0F, 0F, 22F, 120F)
        speeds.forEachIndexed { index, speed ->
            fixture.clock.value += 60_000L
            val moving = ProviderMovementPolicy().classify(speed, null, null)
            fixture.coordinator.updateLocation(com.wu.nearbycheckin.core.model.LocationSnapshot(
                30.0 + index * 0.01, 121.0, 3.8F, speed, fixture.clock.value, moving
            ))
            val amap = candidate("当前高德打卡位置", false, MapProvider.AMAP)
                .copy(stableHint = "device-real-location:30.$index,121.0")
            fixture.coordinator.replaceCandidates(MapProvider.AMAP, listOf(amap), "amap-$index")
            assertTrue(fixture.repository.currentSnapshot().providerTasks.none {
                it.provider == MapProvider.AMAP && it.candidateKey != amap.toPlaceKey() &&
                        it.status == ProviderTaskStatus.RETRY_WAIT
            })
            val lastAmap = requireNotNull(fixture.coordinator.claimNextTask(MapProvider.AMAP, "amap"))
            fixture.coordinator.beginVerification(lastAmap.taskId, "command-1")
            fixture.coordinator.replaceCandidates(MapProvider.BAIDU,
                listOf(candidate("沿途地点$index", false)), "baidu-$index")
            val baidu = requireNotNull(fixture.coordinator.claimNextTask(MapProvider.BAIDU, "baidu"))
            fixture.coordinator.beginVerification(baidu.taskId, "command-1")
            assertTrue(fixture.coordinator.finishTask(baidu.taskId,
                evidence(baidu.taskId, MapOperationStatus.SUCCESS)))
            assertEquals(ProviderTaskStatus.VERIFYING,
                fixture.repository.currentSnapshot().providerTasks.first { it.taskId == lastAmap.taskId }.status)
            assertTrue(fixture.coordinator.finishTask(lastAmap.taskId,
                evidence(lastAmap.taskId, if (speed == 0F) MapOperationStatus.SUCCESS
                    else MapOperationStatus.TIMEOUT, MapProvider.AMAP)))
        }
        assertEquals(6, fixture.repository.currentSnapshot().providerRuntime.baiduTotalVerifiedSuccessCount)
        assertEquals(2, fixture.repository.currentSnapshot().records.count { it.provider == MapProvider.AMAP })
    }

    // 同一地图验证中不得再领取另一个地点，防止不同命令争抢同一个结果页。
    @Test
    fun onlyOneInFlightTaskPerProvider() {
        val fixture = fixture()
        fixture.coordinator.replaceCandidates(MapProvider.BAIDU,
            listOf(candidate("第一地点", false), candidate("第二地点", false)), "list")
        val task = requireNotNull(fixture.coordinator.claimNextTask(MapProvider.BAIDU, "worker-1"))
        assertEquals(null, fixture.coordinator.claimNextTask(MapProvider.BAIDU, "worker-2"))
        fixture.coordinator.beginVerification(task.taskId, "command-1")
        assertEquals(null, fixture.coordinator.claimNextTask(MapProvider.BAIDU, "worker-2"))
        fixture.coordinator.finishTask(task.taskId, evidence(task.taskId, MapOperationStatus.SUCCESS))
        assertEquals("第二地点", fixture.coordinator.claimNextTask(MapProvider.BAIDU, "worker-2")?.candidate?.name)
    }

    // 相同最终证据回调两次只能增加一次成功计数，迟到超时不能复活已完成任务。
    @Test
    fun ignoresDuplicateAndLateFinalEvidence() {
        val fixture = fixture()
        fixture.coordinator.replaceCandidates(MapProvider.BAIDU,
            listOf(candidate("服务区", false)), "list")
        val task = requireNotNull(fixture.coordinator.claimNextTask(MapProvider.BAIDU, "worker"))
        fixture.coordinator.beginVerification(task.taskId, "command-1")
        val success = evidence(task.taskId, MapOperationStatus.SUCCESS)
        assertTrue(fixture.coordinator.finishTask(task.taskId, success))
        assertFalse(fixture.coordinator.finishTask(task.taskId, success))
        assertFalse(fixture.coordinator.finishTask(task.taskId,
            success.copy(resultStatus = MapOperationStatus.TIMEOUT)))
        val snapshot = fixture.repository.currentSnapshot()
        assertEquals(1, snapshot.providerRuntime.baiduTotalVerifiedSuccessCount)
        assertEquals(ProviderTaskStatus.COMPLETED, snapshot.providerTasks.single().status)
    }

    // 另一地图或旧命令的迟到结果不能完成当前事务。
    @Test
    fun rejectsEvidenceFromAnotherProviderOrCommand() {
        val fixture = fixture()
        fixture.coordinator.replaceCandidates(MapProvider.BAIDU,
            listOf(candidate("服务区", false)), "list")
        val task = requireNotNull(fixture.coordinator.claimNextTask(MapProvider.BAIDU, "worker"))
        fixture.coordinator.beginVerification(task.taskId, "command-1")
        assertFalse(fixture.coordinator.finishTask(task.taskId,
            evidence(task.taskId, MapOperationStatus.SUCCESS, MapProvider.AMAP)))
        assertFalse(fixture.coordinator.finishTask(task.taskId,
            evidence(task.taskId, MapOperationStatus.SUCCESS).copy(commandId = "old")))
        assertEquals(ProviderTaskStatus.VERIFYING,
            fixture.repository.currentSnapshot().providerTasks.single().status)
    }

    // 验证移动后的旧位置等待任务被取消，后续只领取当前位置任务。
    @Test
    fun retiresObsoleteDeviceLocationTasks() {
        val fixture = fixture()
        val first = candidate("当前高德打卡位置", false, MapProvider.AMAP)
            .copy(stableHint = "device-real-location:31.20000,121.40000")
        val next = first.copy(stableHint = "device-real-location:31.20100,121.40100")
        fixture.coordinator.replaceCandidates(MapProvider.AMAP, listOf(first), "first")
        fixture.coordinator.replaceCandidates(MapProvider.AMAP, listOf(next), "next")
        val tasks = fixture.repository.currentSnapshot().providerTasks
        assertEquals(ProviderTaskStatus.CANCELLED, tasks.first().status)
        assertEquals(ProviderTaskStatus.QUEUED, tasks.last().status)
        assertEquals(next.toPlaceKey(), fixture.coordinator.claimNextTask(
            MapProvider.AMAP, "worker"
        )?.candidateKey)
    }

    // 验证刷新位置不打断正在验证的任务，也不取消已经确认成功的地点。
    @Test
    fun preservesVerifyingAndCompletedDeviceLocationTasks() {
        val fixture = fixture()
        val first = candidate("当前高德打卡位置", false, MapProvider.AMAP)
            .copy(stableHint = "device-real-location:31.20000,121.40000")
        val next = first.copy(stableHint = "device-real-location:31.20100,121.40100")
        fixture.coordinator.replaceCandidates(MapProvider.AMAP, listOf(first), "first")
        val task = fixture.coordinator.claimNextTask(MapProvider.AMAP, "worker")!!
        fixture.coordinator.beginVerification(task.taskId, "command-1")
        fixture.coordinator.replaceCandidates(MapProvider.AMAP, listOf(next), "next")
        assertEquals(ProviderTaskStatus.VERIFYING,
            fixture.repository.currentSnapshot().providerTasks.first().status)
        fixture.coordinator.finishTask(task.taskId,
            evidence(task.taskId, MapOperationStatus.SUCCESS, MapProvider.AMAP))
        fixture.coordinator.replaceCandidates(MapProvider.AMAP, listOf(next), "next")
        val snapshot = fixture.repository.currentSnapshot()
        assertEquals(ProviderTaskStatus.COMPLETED, snapshot.providerTasks.first().status)
        assertTrue(first.toPlaceKey() in snapshot.providerCompletedKeys)
        assertEquals(1, snapshot.records.size)
    }

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
        assertEquals(
            1,
            snapshot.providerRuntime.baiduDailyVerifiedSuccessCount
        )
        assertEquals(
            1,
            snapshot.providerRuntime.baiduTotalVerifiedSuccessCount
        )
        assertEquals(
            "完成",
            snapshot.providerRuntime.baiduLastVerifiedSuccessEvidence
        )
    }

    @Test
    fun `高德成功任务写入独立永久键且不会重复入队`() {
        val fixture = fixture()
        val candidate = candidate(
            name = "高德地点A",
            completed = false,
            provider = MapProvider.AMAP
        )
        fixture.coordinator.resumePendingTasks(MapProvider.AMAP)
        fixture.coordinator.beginCandidateRefresh(MapProvider.AMAP)
        fixture.coordinator.replaceCandidates(
            MapProvider.AMAP,
            listOf(candidate),
            "amap-list-1",
            profileVersion = "amap-v1"
        )
        val task = fixture.coordinator.claimNextTask(
            MapProvider.AMAP,
            "amap-worker"
        )!!
        fixture.coordinator.beginVerification(task.taskId, "command-1")
        fixture.coordinator.finishTask(
            task.taskId,
            evidence(
                taskId = task.taskId,
                status = MapOperationStatus.SUCCESS,
                provider = MapProvider.AMAP
            )
        )

        fixture.coordinator.beginCandidateRefresh(MapProvider.AMAP)
        fixture.coordinator.replaceCandidates(
            MapProvider.AMAP,
            listOf(candidate.copy(fingerprint = "fresh-amap-anchor")),
            "amap-list-2",
            profileVersion = "amap-v1"
        )

        val snapshot = fixture.repository.currentSnapshot()
        assertTrue(candidate.toPlaceKey() in snapshot.providerCompletedKeys)
        assertEquals(
            ProviderTaskStatus.COMPLETED,
            snapshot.providerTasks.single().status
        )
        assertEquals(
            null,
            fixture.coordinator.claimNextTask(
                MapProvider.AMAP,
                "amap-worker"
            )
        )
    }

    @Test
    fun `高德插件升级会重开缺少最终证据的旧完成任务`() {
        val fixture = fixture()
        val candidate = candidate(
            name = "高德当前位置",
            completed = false,
            provider = MapProvider.AMAP
        )
        fixture.coordinator.resumePendingTasks(MapProvider.AMAP)
        fixture.coordinator.beginCandidateRefresh(MapProvider.AMAP)
        fixture.coordinator.replaceCandidates(
            MapProvider.AMAP,
            listOf(candidate),
            "amap-list-v1",
            profileVersion = "amap-v1"
        )
        val task = fixture.coordinator.claimNextTask(
            MapProvider.AMAP,
            "amap-worker"
        )!!
        fixture.coordinator.beginVerification(task.taskId, "command-1")
        fixture.coordinator.finishTask(
            task.taskId,
            evidence(
                taskId = task.taskId,
                status = MapOperationStatus.SUCCESS,
                provider = MapProvider.AMAP
            ).copy(
                profileVersion = "amap-v1",
                page = "BLOCKED",
                message = "高德第二次点击收到频率提示",
                beforeCheckInCount = 221,
                afterCheckInCount = null
            )
        )

        fixture.coordinator.beginCandidateRefresh(MapProvider.AMAP)
        fixture.coordinator.replaceCandidates(
            MapProvider.AMAP,
            listOf(candidate.copy(fingerprint = "fresh-amap-anchor")),
            "amap-list-v2",
            profileVersion = "amap-v2"
        )

        val snapshot = fixture.repository.currentSnapshot()
        val reopenedTask = snapshot.providerTasks.single()
        assertEquals(ProviderTaskStatus.QUEUED, reopenedTask.status)
        assertEquals("fresh-amap-anchor", reopenedTask.candidate.fingerprint)
        assertFalse(candidate.toPlaceKey() in snapshot.providerCompletedKeys)
        assertTrue(
            snapshot.records.none { record ->
                record.id == "provider-${task.taskId}"
            }
        )
        assertNotNull(
            fixture.coordinator.claimNextTask(
                MapProvider.AMAP,
                "amap-worker"
            )
        )
    }

    @Test
    fun `百度每日额度阻断当天任务并在次日零点自动恢复`() {
        val startMillis = Instant.parse("2026-08-17T10:00:00Z")
            .toEpochMilli()
        val fixture = fixture(startMillis)
        fixture.coordinator.beginCandidateRefresh(MapProvider.BAIDU)
        fixture.coordinator.replaceCandidates(
            MapProvider.BAIDU,
            listOf(candidate("额度边界地点", completed = false)),
            "list-daily-quota"
        )
        val task = fixture.coordinator.claimNextTask(
            MapProvider.BAIDU,
            "worker"
        )!!
        fixture.coordinator.beginVerification(task.taskId, "command-1")
        val evidence = evidence(
            taskId = task.taskId,
            status = MapOperationStatus.BLOCKED
        ).copy(
            page = "DAILY_QUOTA_EXHAUSTED",
            message = "百度地图今日打卡额度已用尽"
        )

        fixture.coordinator.finishTask(task.taskId, evidence)
        fixture.coordinator.markBaiduDailyQuotaReached(evidence)

        val blocked = fixture.repository.currentSnapshot()
        assertEquals(
            ProviderAutomationState.BLOCKED,
            blocked.providerRuntime.baiduState
        )
        assertTrue(fixture.coordinator.refreshBaiduDailyQuota())
        assertFalse(fixture.coordinator.manualResume(MapProvider.BAIDU))
        assertEquals(
            ProviderTaskStatus.BLOCKED,
            blocked.providerTasks.single().status
        )

        fixture.clock.value = Instant.parse("2026-08-18T00:00:00Z")
            .toEpochMilli()

        assertFalse(fixture.coordinator.refreshBaiduDailyQuota())
        val resumed = fixture.repository.currentSnapshot()
        assertEquals(
            ProviderAutomationState.READY,
            resumed.providerRuntime.baiduState
        )
        assertEquals(0L, resumed.providerRuntime.baiduDailyQuotaResetAtMillis)
        assertEquals(
            ProviderTaskStatus.QUEUED,
            resumed.providerTasks.single().status
        )
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

    private fun fixture(nowMillis: Long = 10_000L): Fixture {
        val clock = MutableClock(nowMillis)
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
                taskIdFactory = { "task-${++id}" },
                zoneId = ZoneId.of("UTC")
            )
        )
    }

    private fun candidate(
        name: String,
        completed: Boolean,
        provider: MapProvider = MapProvider.BAIDU
    ): ProviderPlaceCandidate {
        return ProviderPlaceCandidate(
            provider = provider,
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
        status: MapOperationStatus,
        provider: MapProvider = MapProvider.BAIDU
    ): MapOperationEvidence {
        return MapOperationEvidence(
            provider = provider,
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
        var value: Long
    ) : AppClock {
        // 返回测试固定时间。
        override fun nowMillis(): Long = value
    }
}
