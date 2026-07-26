# 双地图打卡收尾与高德真实路径修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让高德 `16.21.0.2010` 按“我的 → 打卡点 → 开始打卡 → 等待 2000 毫秒 → 再次开始打卡”完成闭环，并让百度 `21.18.50` 在确认成功后自动点击右上角“完成”。

**Architecture:** Profile 只负责把页面快照转换为页面、动作和证据；百度 Adapter 继续优先节点点击，高德精确版本针对已确认的节点虚假成功问题优先使用唯一语义边界手势；纯 Kotlin 流程策略保存高德两次点击阶段和百度成功清理规则；Worker 只负责按策略调度延迟、保留后台状态并回写结果。

**Tech Stack:** Kotlin、Android AccessibilityService、Handler、JUnit 4、Gradle。

## Global Constraints

- 高德真实路径固定为“我的 → 打卡点 → 开始打卡”。
- 高德第一次“开始打卡”成功提交后固定等待 `2000L` 毫秒，再重新采集窗口并安全提交第二次点击。
- 高德只有在第二次点击已提交后，才把“打卡太频繁”作为第一次打卡已生效的成功证据。
- 百度成功页必须返回 `SUCCESS + CLICK_DONE`，并在成功结果回写前尝试点击“完成”。
- 百度“完成”点击失败不得把已经确认的打卡成功降级为失败，但必须记录清理失败证据。
- 语义边界手势只对高德 `16.21.0.2010` 和百度 `21.18.50` 的精确版本开放。
- 只允许唯一、启用、对用户可见、边界有效且中心位于当前根窗口内的语义目标；不使用固定坐标。
- 不修改候选地点、巡航扫描间隔、事务存储、命令串行、重试次数或分屏策略。
- 不新增第三方依赖。
- 新增或修改函数的中文功能注释放在函数定义上方。

---

## 文件结构

- 修改 `app/src/main/java/com/wu/nearbycheckin/platform/maps/MapUiProfileModels.kt`：增加频率限制页面和成功页清理动作。
- 修改 `app/src/main/java/com/wu/nearbycheckin/platform/maps/MapUiActionLabels.kt`：更新两个地图的动作白名单。
- 修改 `app/src/main/java/com/wu/nearbycheckin/platform/maps/AmapUiProfile.kt`：实现高德真实页面路径与频率限制证据。
- 修改 `app/src/main/java/com/wu/nearbycheckin/platform/maps/BaiduUiProfile.kt`：成功页返回清理动作。
- 修改 `app/src/main/java/com/wu/nearbycheckin/platform/maps/AmapMapAdapter.kt`：仅为精确高德版本采集并优先执行安全语义手势。
- 修改 `app/src/main/java/com/wu/nearbycheckin/platform/maps/AccessibilityNodeActions.kt`：增加默认关闭的手势优先执行开关。
- 新建 `app/src/main/java/com/wu/nearbycheckin/platform/accessibility/AmapCheckInFollowUpPolicy.kt`：保存高德两次点击的纯 Kotlin 阶段规则。
- 新建 `app/src/main/java/com/wu/nearbycheckin/platform/accessibility/MapUiSuccessCleanupPolicy.kt`：保存百度成功页最佳努力清理规则。
- 修改 `app/src/main/java/com/wu/nearbycheckin/platform/accessibility/MapUiExecutionPolicy.kt`：让陈旧的频率限制页参与重复未知页上限。
- 修改 `app/src/main/java/com/wu/nearbycheckin/platform/accessibility/DualMapCommandWorker.kt`：接入两阶段延迟、频率限制成功覆盖和百度清理动作。
- 修改对应 Profile、标签和执行策略测试；新增两个纯流程策略测试。

### Task 1: 更新双地图 Profile 契约与高德安全手势目标

**Files:**
- Modify: `app/src/main/java/com/wu/nearbycheckin/platform/maps/MapUiProfileModels.kt`
- Modify: `app/src/main/java/com/wu/nearbycheckin/platform/maps/MapUiActionLabels.kt`
- Modify: `app/src/main/java/com/wu/nearbycheckin/platform/maps/AmapUiProfile.kt`
- Modify: `app/src/main/java/com/wu/nearbycheckin/platform/maps/BaiduUiProfile.kt`
- Modify: `app/src/main/java/com/wu/nearbycheckin/platform/maps/AmapMapAdapter.kt`
- Modify: `app/src/test/java/com/wu/nearbycheckin/platform/maps/AmapUiProfileTest.kt`
- Modify: `app/src/test/java/com/wu/nearbycheckin/platform/maps/BaiduUiProfileTest.kt`
- Modify: `app/src/test/java/com/wu/nearbycheckin/platform/maps/MapUiActionLabelsTest.kt`

**Interfaces:**
- Consumes: `MapUiSnapshot.hasActionableLabel(label)`、`MapUiActionLabels.allForProvider(provider)`、`AccessibilityNodeActions.clickFirstMatchingLabel(...)`。
- Produces: `MapUiPage.RATE_LIMITED`、`MapUiAction.CLICK_DONE`、高德真实路径决策、高德精确版本手势目标采集、百度成功清理动作。

- [ ] **Step 1: 写入失败的 Profile 和标签契约测试**

将 `AmapUiProfileTest` 中旧路径测试替换为以下契约：

```kotlin
// 验证高德允许安全语义手势目标进入“我的”。
@Test
fun opensMineFromGestureTarget() {
    val decision = profile.decide(
        snapshot(gestureTargets = listOf("我的"))
    )

    assertEquals(MapUiPage.HOME, decision.page)
    assertEquals(MapUiAction.CLICK_MINE, decision.action)
}

// 验证高德从“我的”页面进入打卡点。
@Test
fun opensCheckInPointFromMine() {
    val decision = profile.decide(
        snapshot(gestureTargets = listOf("打卡点"))
    )

    assertEquals(MapUiPage.MINE, decision.page)
    assertEquals(MapUiAction.CLICK_CHECK_IN_POINT, decision.action)
}

// 验证高德打卡页执行“开始打卡”。
@Test
fun startsCheckInFromCheckInPointPage() {
    val decision = profile.decide(
        snapshot(gestureTargets = listOf("开始打卡"))
    )

    assertEquals(MapUiPage.CHECK_IN, decision.page)
    assertEquals(MapUiAction.CLICK_CHECK_IN, decision.action)
}

// 验证高德频率限制文案进入显式页面状态但不自行点击。
@Test
fun recognizesRateLimitedEvidence() {
    val decision = profile.decide(
        snapshot(texts = listOf("打卡太频繁，请稍后再试"))
    )

    assertEquals(MapUiPage.RATE_LIMITED, decision.page)
    assertEquals(MapUiAction.WAIT, decision.action)
}

// 验证高德不再沿用旧足迹动作。
@Test
fun doesNotUseLegacyFootprintRoute() {
    val decision = profile.decide(
        snapshot(gestureTargets = listOf("我的足迹"))
    )

    assertEquals(MapUiPage.UNKNOWN, decision.page)
    assertEquals(MapUiAction.WAIT, decision.action)
}
```

把测试辅助函数扩展为：

```kotlin
// 构造不依赖 Android 的高德页面快照。
private fun snapshot(
    texts: List<String> = emptyList(),
    clickable: List<String> = emptyList(),
    gestureTargets: List<String> = emptyList()
): MapUiSnapshot {
    return MapUiSnapshot(
        packageName = profile.packageName,
        appVersion = profile.supportedAppVersion,
        nodes = texts.map { text ->
            MapUiNodeSnapshot(text = text)
        } + clickable.map { text ->
            MapUiNodeSnapshot(text = text, clickable = true)
        } + gestureTargets.map { text ->
            MapUiNodeSnapshot(text = text, gestureTarget = true)
        }
    )
}
```

在 `BaiduUiProfileTest` 中修改成功页断言：

```kotlin
// 验证百度成功页先返回“完成”清理动作。
@Test
fun cleansUpExplicitSuccessPage() {
    val decision = profile.decide(
        snapshot(texts = listOf("打卡成功", "完成"))
    )

    assertEquals(MapUiPage.SUCCESS, decision.page)
    assertEquals(MapUiAction.CLICK_DONE, decision.action)
}
```

在 `MapUiActionLabelsTest` 中更新断言：

```kotlin
// 验证高德动作映射使用当前真机真实路径标签。
@Test
fun mapsAmapActionsToVerifiedLabels() {
    assertEquals(
        listOf("打卡点"),
        MapUiActionLabels.forProvider(
            MapProvider.AMAP,
            MapUiAction.CLICK_CHECK_IN_POINT
        )
    )
    assertEquals(
        listOf("开始打卡"),
        MapUiActionLabels.forProvider(
            MapProvider.AMAP,
            MapUiAction.CLICK_CHECK_IN
        )
    )
}

// 验证“完成”只属于百度成功页清理动作。
@Test
fun mapsDoneOnlyToBaiduCleanup() {
    assertEquals(
        listOf("完成"),
        MapUiActionLabels.forProvider(
            MapProvider.BAIDU,
            MapUiAction.CLICK_DONE
        )
    )
    assertEquals(
        emptyList<String>(),
        MapUiActionLabels.forProvider(
            MapProvider.AMAP,
            MapUiAction.CLICK_DONE
        )
    )
}
```

同时把百度全部动作标签期望值更新为：

```kotlin
listOf(
    "我的",
    "打卡点",
    "立即打卡",
    "立刻打卡",
    "首页",
    "知道了",
    "完成"
)
```

- [ ] **Step 2: 运行目标测试并确认 RED**

Run:

```powershell
$env:GRADLE_USER_HOME='D:\Softwares\Android\GradleUserHome'
$env:ANDROID_HOME='D:\Softwares\Android\Sdk'
$env:ANDROID_SDK_ROOT='D:\Softwares\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.maps.AmapUiProfileTest" --tests "com.wu.nearbycheckin.platform.maps.BaiduUiProfileTest" --tests "com.wu.nearbycheckin.platform.maps.MapUiActionLabelsTest" --console=plain
```

Expected: 因 `MapUiPage.RATE_LIMITED` 和 `MapUiAction.CLICK_DONE` 尚不存在，测试源码编译失败。

- [ ] **Step 3: 实现最小 Profile 模型和动作标签**

在 `MapUiPage` 增加：

```kotlin
RATE_LIMITED,
```

在 `MapUiAction` 增加：

```kotlin
CLICK_DONE,
```

把高德动作标签改为：

```kotlin
MapProvider.AMAP to MapUiAction.CLICK_MINE ->
    listOf("我的")
MapProvider.AMAP to MapUiAction.CLICK_CHECK_IN_POINT ->
    listOf("打卡点")
MapProvider.AMAP to MapUiAction.CLICK_CHECK_IN ->
    listOf("开始打卡")
```

增加百度清理标签：

```kotlin
MapProvider.BAIDU to MapUiAction.CLICK_DONE ->
    listOf("完成")
```

- [ ] **Step 4: 实现高德真实路径和百度成功清理决策**

把 `AmapUiProfile.decide` 的核心顺序改为：

```kotlin
return when {
    snapshot.containsAnyLabelFragment(BLOCKED_MARKERS) ->
        decision(MapUiPage.BLOCKED, MapUiAction.BLOCK, "人工阻断")
    snapshot.containsLabelFragment("打卡太频繁") ->
        decision(
            MapUiPage.RATE_LIMITED,
            MapUiAction.WAIT,
            "打卡太频繁"
        )
    snapshot.hasLabel("打卡成功") &&
            snapshot.hasAnyLabel(listOf("完成", "成功")) ->
        decision(MapUiPage.SUCCESS, null, "打卡成功")
    snapshot.containsLabelFragment("当前地点已打卡") ->
        decision(
            MapUiPage.ALREADY_COMPLETED,
            null,
            "当前地点已打卡"
        )
    snapshot.hasActionableLabel("开始打卡") ->
        decision(
            MapUiPage.CHECK_IN,
            MapUiAction.CLICK_CHECK_IN,
            "执行开始打卡"
        )
    snapshot.hasActionableLabel("打卡点") ->
        decision(
            MapUiPage.MINE,
            MapUiAction.CLICK_CHECK_IN_POINT,
            "进入打卡点"
        )
    snapshot.hasActionableLabel("我的") ->
        decision(
            MapUiPage.HOME,
            MapUiAction.CLICK_MINE,
            "进入我的"
        )
    else ->
        decision(MapUiPage.UNKNOWN, MapUiAction.WAIT, "未知页面")
}
```

把 `BaiduUiProfile` 的明确成功分支改为：

```kotlin
snapshot.hasLabel("打卡成功") &&
        snapshot.hasAnyLabel(listOf("完成", "成功")) ->
    decision(
        MapUiPage.SUCCESS,
        MapUiAction.CLICK_DONE,
        "打卡成功，等待关闭结果页"
    )
```

- [ ] **Step 5: 为高德精确版本采集现有安全语义手势目标**

把 `AmapMapAdapter.captureSnapshot` 改为：

```kotlin
// 采集当前高德窗口的纯 Kotlin 页面快照。
fun captureSnapshot(
    rootNode: AccessibilityNodeInfo?,
    appVersion: String
): MapUiSnapshot {
    return MapUiSnapshotCollector.collect(
        rootNode = rootNode,
        packageName = AmapUiProfile.packageName,
        appVersion = appVersion,
        gestureLabels = if (
            appVersion == AmapUiProfile.supportedAppVersion
        ) {
            MapUiActionLabels.allForProvider(MapProvider.AMAP)
        } else {
            emptyList()
        }
    )
}
```

- [ ] **Step 6: 运行目标测试并确认 GREEN**

Run: Task 1 Step 2 的相同命令。

Expected: 三个目标测试类全部通过。

- [ ] **Step 7: 运行地图模块测试并提交 Task 1**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.maps.*" --console=plain
git diff --check
git add app/src/main/java/com/wu/nearbycheckin/platform/maps app/src/test/java/com/wu/nearbycheckin/platform/maps
git commit -m "修正双地图页面动作契约"
```

Expected: 地图模块测试通过，提交只包含 Profile、动作标签和高德 Adapter 变更。

### Task 2: 增加高德两阶段点击与百度成功清理纯策略

**Files:**
- Create: `app/src/main/java/com/wu/nearbycheckin/platform/accessibility/AmapCheckInFollowUpPolicy.kt`
- Create: `app/src/main/java/com/wu/nearbycheckin/platform/accessibility/MapUiSuccessCleanupPolicy.kt`
- Modify: `app/src/main/java/com/wu/nearbycheckin/platform/accessibility/MapUiExecutionPolicy.kt`
- Create: `app/src/test/java/com/wu/nearbycheckin/platform/accessibility/AmapCheckInFollowUpPolicyTest.kt`
- Create: `app/src/test/java/com/wu/nearbycheckin/platform/accessibility/MapUiSuccessCleanupPolicyTest.kt`
- Modify: `app/src/test/java/com/wu/nearbycheckin/platform/accessibility/MapUiExecutionPolicyTest.kt`

**Interfaces:**
- Consumes: `MapProvider`、`MapUiPage`、`MapUiAction`、`AccessibilityClickResult`。
- Produces: `AmapCheckInStage`、`AmapCheckInFollowUpPolicy`、`MapUiSuccessCleanupDisposition`、`MapUiSuccessCleanupPolicy`，以及频率限制页的重复上限行为。

- [ ] **Step 1: 写入高德两阶段策略失败测试**

创建 `AmapCheckInFollowUpPolicyTest.kt`：

```kotlin
package com.wu.nearbycheckin.platform.accessibility

import com.wu.nearbycheckin.platform.maps.MapUiPage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapCheckInFollowUpPolicyTest {

    // 验证第一次成功提交后进入等待第二次点击阶段。
    @Test
    fun waitsForSecondClickAfterFirstSubmission() {
        assertEquals(
            AmapCheckInStage.WAITING_SECOND_CLICK,
            AmapCheckInFollowUpPolicy.nextStageAfterSuccessfulCheckIn(
                AmapCheckInStage.NONE
            )
        )
    }

    // 验证第二次成功提交后进入结果识别阶段。
    @Test
    fun marksSecondClickAsSubmitted() {
        assertEquals(
            AmapCheckInStage.SECOND_CLICK_SUBMITTED,
            AmapCheckInFollowUpPolicy.nextStageAfterSuccessfulCheckIn(
                AmapCheckInStage.WAITING_SECOND_CLICK
            )
        )
    }

    // 验证第二次点击固定等待两秒。
    @Test
    fun usesTwoSecondDelayBeforeSecondClick() {
        assertEquals(
            2_000L,
            AmapCheckInFollowUpPolicy.delayBeforeNextLoopMillis(
                AmapCheckInStage.WAITING_SECOND_CLICK,
                defaultDelayMillis = 1_500L
            )
        )
    }

    // 验证只有第二次点击已提交后才接受频率限制成功证据。
    @Test
    fun acceptsRateLimitOnlyAfterSecondClick() {
        assertFalse(
            AmapCheckInFollowUpPolicy.isSuccessfulRateLimit(
                page = MapUiPage.RATE_LIMITED,
                stage = AmapCheckInStage.NONE
            )
        )
        assertFalse(
            AmapCheckInFollowUpPolicy.isSuccessfulRateLimit(
                page = MapUiPage.RATE_LIMITED,
                stage = AmapCheckInStage.WAITING_SECOND_CLICK
            )
        )
        assertTrue(
            AmapCheckInFollowUpPolicy.isSuccessfulRateLimit(
                page = MapUiPage.RATE_LIMITED,
                stage = AmapCheckInStage.SECOND_CLICK_SUBMITTED
            )
        )
    }
}
```

- [ ] **Step 2: 写入百度成功清理策略失败测试**

创建 `MapUiSuccessCleanupPolicyTest.kt`：

```kotlin
package com.wu.nearbycheckin.platform.accessibility

import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.platform.maps.AccessibilityClickResult
import com.wu.nearbycheckin.platform.maps.MapUiAction
import com.wu.nearbycheckin.platform.maps.MapUiPage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapUiSuccessCleanupPolicyTest {

    // 验证只有百度成功页“完成”动作属于最佳努力清理。
    @Test
    fun selectsOnlyBaiduDoneAction() {
        assertTrue(
            MapUiSuccessCleanupPolicy.shouldExecute(
                provider = MapProvider.BAIDU,
                page = MapUiPage.SUCCESS,
                action = MapUiAction.CLICK_DONE
            )
        )
        assertFalse(
            MapUiSuccessCleanupPolicy.shouldExecute(
                provider = MapProvider.AMAP,
                page = MapUiPage.SUCCESS,
                action = MapUiAction.CLICK_DONE
            )
        )
    }

    // 验证清理失败证据明确记录失败但不产生失败状态。
    @Test
    fun recordsCleanupFailureEvidence() {
        val evidence = MapUiSuccessCleanupPolicy.appendEvidence(
            profileEvidence = "page=SUCCESS",
            clickResult = AccessibilityClickResult(
                success = false,
                message = "未找到节点：完成"
            )
        )

        assertTrue(evidence.contains("cleanup=failed"))
        assertTrue(evidence.contains("未找到节点：完成"))
    }

    // 验证当前命令已提交打卡时清理失败仍完成原成功结果。
    @Test
    fun finishesConfirmedSuccessWhenCleanupFails() {
        assertEquals(
            MapUiSuccessCleanupDisposition.FINISH_SUCCESS,
            MapUiSuccessCleanupPolicy.dispositionAfter(
                checkInSubmitted = true,
                clickResult = AccessibilityClickResult(
                    success = false,
                    message = "节点点击失败：完成"
                )
            )
        )
    }

    // 验证陈旧成功页清理成功后继续当前新命令。
    @Test
    fun continuesNewCommandAfterStalePageCleanup() {
        assertEquals(
            MapUiSuccessCleanupDisposition.CONTINUE_COMMAND,
            MapUiSuccessCleanupPolicy.dispositionAfter(
                checkInSubmitted = false,
                clickResult = AccessibilityClickResult(
                    success = true,
                    message = "已点击节点：完成"
                )
            )
        )
    }

    // 验证陈旧成功页清理失败时不伪造新命令成功。
    @Test
    fun failsNewCommandWhenStalePageCannotBeCleaned() {
        assertEquals(
            MapUiSuccessCleanupDisposition.FAIL_COMMAND,
            MapUiSuccessCleanupPolicy.dispositionAfter(
                checkInSubmitted = false,
                clickResult = AccessibilityClickResult(
                    success = false,
                    message = "节点点击失败：完成"
                )
            )
        )
    }
}
```

在 `MapUiExecutionPolicyTest` 增加：

```kotlin
// 验证陈旧频率限制页按未知页重复上限停止。
@Test
fun countsRateLimitedPageAsRepeatedUnknown() {
    assertEquals(
        3,
        policy.repeatedUnknownCountFor(
            page = MapUiPage.RATE_LIMITED,
            fingerprint = "rate-limited",
            lastFingerprint = "rate-limited",
            repeatedUnknownCount = 2
        )
    )
}
```

- [ ] **Step 3: 运行纯策略测试并确认 RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.accessibility.AmapCheckInFollowUpPolicyTest" --tests "com.wu.nearbycheckin.platform.accessibility.MapUiSuccessCleanupPolicyTest" --tests "com.wu.nearbycheckin.platform.accessibility.MapUiExecutionPolicyTest" --console=plain
```

Expected: 因两个策略类和 `AmapCheckInStage` 尚不存在而编译失败。

- [ ] **Step 4: 实现高德两阶段纯策略**

创建 `AmapCheckInFollowUpPolicy.kt`：

```kotlin
package com.wu.nearbycheckin.platform.accessibility

import com.wu.nearbycheckin.platform.maps.MapUiPage

enum class AmapCheckInStage {
    NONE,
    WAITING_SECOND_CLICK,
    SECOND_CLICK_SUBMITTED
}

object AmapCheckInFollowUpPolicy {

    // 返回一次成功“开始打卡”提交后的高德流程阶段。
    fun nextStageAfterSuccessfulCheckIn(
        currentStage: AmapCheckInStage
    ): AmapCheckInStage {
        return when (currentStage) {
            AmapCheckInStage.NONE ->
                AmapCheckInStage.WAITING_SECOND_CLICK
            AmapCheckInStage.WAITING_SECOND_CLICK,
            AmapCheckInStage.SECOND_CLICK_SUBMITTED ->
                AmapCheckInStage.SECOND_CLICK_SUBMITTED
        }
    }

    // 在等待第二次点击时返回固定两秒延迟。
    fun delayBeforeNextLoopMillis(
        stage: AmapCheckInStage,
        defaultDelayMillis: Long
    ): Long {
        return if (stage == AmapCheckInStage.WAITING_SECOND_CLICK) {
            SECOND_CLICK_DELAY_MILLIS
        } else {
            defaultDelayMillis
        }
    }

    // 仅在第二次点击已提交后接受频率限制成功证据。
    fun isSuccessfulRateLimit(
        page: MapUiPage,
        stage: AmapCheckInStage
    ): Boolean {
        return page == MapUiPage.RATE_LIMITED &&
                stage == AmapCheckInStage.SECOND_CLICK_SUBMITTED
    }

    const val SECOND_CLICK_DELAY_MILLIS = 2_000L
}
```

- [ ] **Step 5: 实现百度成功清理纯策略**

创建 `MapUiSuccessCleanupPolicy.kt`：

```kotlin
package com.wu.nearbycheckin.platform.accessibility

import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.platform.maps.AccessibilityClickResult
import com.wu.nearbycheckin.platform.maps.MapUiAction
import com.wu.nearbycheckin.platform.maps.MapUiPage

enum class MapUiSuccessCleanupDisposition {
    FINISH_SUCCESS,
    CONTINUE_COMMAND,
    FAIL_COMMAND
}

object MapUiSuccessCleanupPolicy {

    // 判断当前动作是否是百度成功页的最佳努力清理。
    fun shouldExecute(
        provider: MapProvider,
        page: MapUiPage,
        action: MapUiAction?
    ): Boolean {
        return provider == MapProvider.BAIDU &&
                page == MapUiPage.SUCCESS &&
                action == MapUiAction.CLICK_DONE
    }

    // 把成功页清理结果追加到原 Profile 证据。
    fun appendEvidence(
        profileEvidence: String,
        clickResult: AccessibilityClickResult
    ): String {
        val cleanup = if (clickResult.success) {
            "cleanup=success"
        } else {
            "cleanup=failed"
        }
        return "$profileEvidence|$cleanup|" +
                "cleanupResult=${clickResult.message}"
    }

    // 根据当前命令是否已提交打卡决定清理后的命令去向。
    fun dispositionAfter(
        checkInSubmitted: Boolean,
        clickResult: AccessibilityClickResult
    ): MapUiSuccessCleanupDisposition {
        return when {
            checkInSubmitted ->
                MapUiSuccessCleanupDisposition.FINISH_SUCCESS
            clickResult.success ->
                MapUiSuccessCleanupDisposition.CONTINUE_COMMAND
            else ->
                MapUiSuccessCleanupDisposition.FAIL_COMMAND
        }
    }
}
```

- [ ] **Step 6: 让频率限制页参与重复未知页计数**

把 `repeatedUnknownCountFor` 的页面条件改为：

```kotlin
if (
    page != MapUiPage.UNKNOWN &&
    page != MapUiPage.RATE_LIMITED
) {
    return 0
}
```

`outcomeFor` 无需把 `RATE_LIMITED` 直接映射为成功；Worker 会先用高德阶段策略识别真正成功，否则重复三次后沿用 `UNSUPPORTED`。

- [ ] **Step 7: 运行纯策略测试并确认 GREEN**

Run: Task 2 Step 3 的相同命令。

Expected: 三个目标测试类全部通过。

- [ ] **Step 8: 提交 Task 2**

```powershell
git diff --check
git add app/src/main/java/com/wu/nearbycheckin/platform/accessibility app/src/test/java/com/wu/nearbycheckin/platform/accessibility
git commit -m "新增双地图打卡收尾策略"
```

Expected: 提交只包含两个纯策略及执行策略的频率限制计数变更。

### Task 3: 接入 Worker 的高德两阶段流程与百度成功清理

**Files:**
- Modify: `app/src/main/java/com/wu/nearbycheckin/platform/accessibility/DualMapCommandWorker.kt`
- Modify: `app/src/main/java/com/wu/nearbycheckin/platform/maps/AmapMapAdapter.kt`
- Modify: `app/src/main/java/com/wu/nearbycheckin/platform/maps/AccessibilityNodeActions.kt`

**Interfaces:**
- Consumes: `AmapCheckInStage`、`AmapCheckInFollowUpPolicy`、`MapUiSuccessCleanupPolicy`、高德新版 `AmapMapAdapter.execute(...)`。
- Produces: 高德精确版本手势优先执行、后台可恢复的高德两次点击循环，以及不会把陈旧成功页误判为新命令成功的百度自动清理。

- [ ] **Step 1: 运行 Worker 接线前的基线编译**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin --console=plain
```

Expected: `BUILD SUCCESSFUL`，证明 Task 1 和 Task 2 的独立提交未破坏当前 Worker。

- [ ] **Step 2: 让高德精确版本优先使用安全语义手势**

把 `AccessibilityNodeActions.clickFirstMatchingLabel` 扩展为：

```kotlin
fun clickFirstMatchingLabel(
    rootNode: AccessibilityNodeInfo?,
    labels: List<String>,
    gestureTap: ((Int, Int) -> Boolean)? = null,
    preferGesture: Boolean = false
): AccessibilityClickResult
```

采集 `matchingNodes` 后，在查找可点击祖先前增加：

```kotlin
if (preferGesture && gestureTap != null) {
    return executeGestureTap(
        rootNode = rootNode,
        matchingNodes = matchingNodes,
        gestureTap = gestureTap
    )
}
```

把现有手势选择代码提取为带中文功能注释的私有函数：

```kotlin
// 从唯一安全语义节点生成边界中心并提交一次手势。
private fun executeGestureTap(
    rootNode: AccessibilityNodeInfo,
    matchingNodes: List<AccessibilityNodeInfo>,
    gestureTap: (Int, Int) -> Boolean
): AccessibilityClickResult {
    val selection = MapUiGestureTargetPolicy.select(
        candidates = matchingNodes.map { node ->
            MapUiGestureCandidate(
                enabled = node.isEnabled,
                visible = node.isVisibleToUser,
                bounds = node.toMapUiBounds()
            )
        },
        windowBounds = rootNode.toMapUiBounds()
    )
    return MapUiGestureFallbackExecutor.execute(
        selection = selection,
        tap = gestureTap
    )
}
```

百度调用不传 `preferGesture`，继续节点优先。把高德 Adapter 执行签名改为：

```kotlin
// 执行高德 Profile 返回的单个动作。
fun execute(
    rootNode: AccessibilityNodeInfo?,
    action: MapUiAction,
    appVersion: String,
    gestureTap: (Int, Int) -> Boolean
): AccessibilityClickResult {
    val gestureEnabled =
        appVersion == AmapUiProfile.supportedAppVersion
    return AccessibilityNodeActions.clickFirstMatchingLabel(
        rootNode = rootNode,
        labels = MapUiActionLabels.forProvider(
            MapProvider.AMAP,
            action
        ),
        gestureTap = if (gestureEnabled) gestureTap else null,
        preferGesture = gestureEnabled
    )
}
```

- [ ] **Step 3: 在线程循环参数中保存高德阶段和百度提交状态**

把 `executeNextAction` 扩展为：

```kotlin
private fun executeNextAction(
    command: MapCommand,
    startedAtMillis: Long,
    actionCount: Int = 0,
    lastFingerprint: String = "",
    repeatedUnknownCount: Int = 0,
    windowWaitAttempt: Int = 0,
    amapCheckInStage: AmapCheckInStage =
        AmapCheckInStage.NONE,
    baiduCheckInSubmitted: Boolean = false
)
```

所有递归调用和 `waitForTargetWindowBeforeAction` 都必须原样传递 `amapCheckInStage` 和 `baiduCheckInSubmitted`。窗口不存在时只增加 `windowWaitAttempt`，不改变阶段、提交状态、动作次数或页面指纹。

- [ ] **Step 4: 在普通终态判断前处理高德频率限制成功**

在计算 `outcome` 前增加：

```kotlin
if (
    command.provider == MapProvider.AMAP &&
    AmapCheckInFollowUpPolicy.isSuccessfulRateLimit(
        page = decision.page,
        stage = amapCheckInStage
    )
) {
    finishProfileOutcome(
        command = command,
        startedAtMillis = startedAtMillis,
        outcome = MapUiExecutionOutcome.SUCCESS,
        decision = decision.copy(
            evidence =
                "第二次点击触发频率限制，第一次打卡已生效"
        ),
        profileEvidence = appendBoundedWorkerEvidence(
            structuredEvidence = profileEvidence,
            key = "followUp",
            value = "second_click_rate_limited_success"
        )
    )
    return
}
```

没有进入 `SECOND_CLICK_SUBMITTED` 的频率限制页继续走 `WAIT` 和重复页面上限，不直接写成功。

- [ ] **Step 5: 在普通终态结束前执行百度最佳努力清理**

在 `outcome != CONTINUE` 的分支前增加：

```kotlin
if (
    MapUiSuccessCleanupPolicy.shouldExecute(
        provider = command.provider,
        page = decision.page,
        action = decision.action
    )
) {
    val cleanupResult = command.provider.execute(
        rootNode = rootNode,
        action = requireNotNull(decision.action),
        appVersion = currentAppVersion
    )
    val cleanupEvidence =
        MapUiSuccessCleanupPolicy.appendEvidence(
            profileEvidence = profileEvidence,
            clickResult = cleanupResult
        )
    reportStructuredStatus(
        event = "成功页清理",
        structuredEvidence = cleanupEvidence
    )
    when (
        MapUiSuccessCleanupPolicy.dispositionAfter(
            checkInSubmitted = baiduCheckInSubmitted,
            clickResult = cleanupResult
        )
    ) {
        MapUiSuccessCleanupDisposition.FINISH_SUCCESS ->
            finishProfileOutcome(
                command = command,
                startedAtMillis = startedAtMillis,
                outcome = MapUiExecutionOutcome.SUCCESS,
                decision = decision,
                profileEvidence = cleanupEvidence
            )
        MapUiSuccessCleanupDisposition.CONTINUE_COMMAND ->
            mainHandler.postDelayed(
                {
                    executeNextAction(
                        command = command,
                        startedAtMillis = startedAtMillis,
                        actionCount = actionCount + 1,
                        lastFingerprint = fingerprint,
                        repeatedUnknownCount = 0,
                        windowWaitAttempt = 0,
                        amapCheckInStage = amapCheckInStage,
                        baiduCheckInSubmitted = false
                    )
                },
                MAP_OPERATION_STEP_DELAY_MILLIS
            )
        MapUiSuccessCleanupDisposition.FAIL_COMMAND ->
            finishCommand(
                command = command,
                result = createOperationResult(
                    command = command,
                    status = MapOperationStatus.FAILED,
                    startedAtMillis = startedAtMillis,
                    failureCode = "BAIDU_STALE_SUCCESS_CLEANUP_FAILED",
                    failureMessage = cleanupResult.message,
                    evidenceText = cleanupEvidence
                )
            )
    }
    return
}
```

当前命令已经提交百度打卡时，清理失败仍以 `SUCCESS` 回写；如果新命令启动时看到陈旧成功页，清理成功后继续执行该命令，清理失败则明确失败，绝不把陈旧页面伪造成新地点成功。

- [ ] **Step 6: 在成功动作后推进高德阶段、百度提交状态并选择延迟**

普通动作执行成功后计算：

```kotlin
val nextAmapCheckInStage = if (
    command.provider == MapProvider.AMAP &&
    action == MapUiAction.CLICK_CHECK_IN
) {
    AmapCheckInFollowUpPolicy.nextStageAfterSuccessfulCheckIn(
        amapCheckInStage
    )
} else {
    amapCheckInStage
}
val nextDelayMillis =
    AmapCheckInFollowUpPolicy.delayBeforeNextLoopMillis(
        stage = nextAmapCheckInStage,
        defaultDelayMillis = MAP_OPERATION_STEP_DELAY_MILLIS
    )
val nextBaiduCheckInSubmitted =
    baiduCheckInSubmitted || (
            command.provider == MapProvider.BAIDU &&
                    action == MapUiAction.CLICK_CHECK_IN
            )
```

下一次 `executeNextAction` 传入 `nextAmapCheckInStage`、`nextBaiduCheckInSubmitted` 并使用 `nextDelayMillis`。第一次成功点击后延迟 2000 毫秒；第二次成功点击后恢复普通 1500 毫秒页面识别延迟。

- [ ] **Step 7: 把手势分发器传给高德 Adapter**

把 `MapProvider.execute` 的高德分支改为：

```kotlin
MapProvider.AMAP ->
    AmapMapAdapter.execute(
        rootNode = rootNode,
        action = action,
        appVersion = appVersion,
        gestureTap = gestureTapDispatcher::tap
    )
```

百度分支保持当前精确版本手势接入。

- [ ] **Step 8: 编译并运行所有目标测试**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.maps.*" --tests "com.wu.nearbycheckin.platform.accessibility.*" --console=plain
```

Expected: 地图与无障碍执行策略测试全部通过。

- [ ] **Step 9: 检查 Worker 状态传递并提交 Task 3**

Run:

```powershell
rg -n "executeNextAction\\(|waitForTargetWindowBeforeAction\\(|amapCheckInStage|baiduCheckInSubmitted" app/src/main/java/com/wu/nearbycheckin/platform/accessibility/DualMapCommandWorker.kt
git diff --check
git add app/src/main/java/com/wu/nearbycheckin/platform/accessibility/DualMapCommandWorker.kt app/src/main/java/com/wu/nearbycheckin/platform/maps/AmapMapAdapter.kt app/src/main/java/com/wu/nearbycheckin/platform/maps/AccessibilityNodeActions.kt
git commit -m "接入双地图打卡收尾流程"
```

Expected: 每个后台等待和页面循环调用都传递 `amapCheckInStage`，提交只包含 Worker 接线。

### Task 4: 完整构建与小米 14 真机闭环

**Files:**
- Modify (ignored): `.superpowers/sdd/2026-07-26-dual-map-ui-profiles/progress.md`
- Modify (ignored): `.superpowers/sdd/2026-07-26-dual-map-ui-profiles/task-6-report.md`
- Modify (ignored): `.superpowers/sdd/2026-07-26-baidu-semantic-gesture-fallback/progress.md`

**Interfaces:**
- Consumes: Task 3 生成的 Debug APK、结构化日志和当前双地图命令队列。
- Produces: 高德真实路径、百度自动完成和后台恢复的真机验收证据。

- [ ] **Step 1: 运行完整单元测试和构建**

```powershell
$env:GRADLE_USER_HOME='D:\Softwares\Android\GradleUserHome'
$env:ANDROID_HOME='D:\Softwares\Android\Sdk'
$env:ANDROID_SDK_ROOT='D:\Softwares\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL`，无失败测试。

- [ ] **Step 2: 检查差异和工作树**

```powershell
git diff --check
git status --short --branch
git log -5 --oneline
```

Expected: 无空白错误；代码和计划提交均存在；只允许保留明确的忽略诊断文件。

- [ ] **Step 3: 安装同一构建产物**

```powershell
$env:ANDROID_SERIAL='e179ad0b'
.\gradlew.bat :app:installDebug --console=plain
```

如果 Gradle 安装结果与本地 APK 哈希不一致，使用同一产物覆盖安装：

```powershell
D:\Softwares\Android\Sdk\platform-tools\adb.exe -s e179ad0b install -r -t app\build\outputs\apk\debug\app-debug.apk
```

- [ ] **Step 4: 检查无障碍服务和地图版本**

```powershell
D:\Softwares\Android\Sdk\platform-tools\adb.exe -s e179ad0b shell dumpsys accessibility
D:\Softwares\Android\Sdk\platform-tools\adb.exe -s e179ad0b shell dumpsys package com.baidu.BaiduMap
D:\Softwares\Android\Sdk\platform-tools\adb.exe -s e179ad0b shell dumpsys package com.autonavi.minimap
```

Expected:

- NearbyCheckIn 服务同时为 Enabled 和 Bound。
- `Crashed services:{}`。
- 百度版本为 `21.18.50`。
- 高德版本为 `16.21.0.2010`。

不得运行 `uiautomator`，避免抢占并解绑 NearbyCheckIn 无障碍服务。

- [ ] **Step 5: 验收高德真实路径**

保持高德在已连接、亮屏、解锁的双地图窗口中，从首页启动一条高德命令。

Expected 日志顺序：

```text
HOME / CLICK_MINE
MINE / CLICK_CHECK_IN_POINT
CHECK_IN / CLICK_CHECK_IN
等待 2000 毫秒
CHECK_IN / CLICK_CHECK_IN
RATE_LIMITED / WAIT
second_click_rate_limited_success
结果已回写
```

页面必须真实经过“我的 → 打卡点 → 开始打卡”；不得出现旧 `CLICK_FOOTPRINT`，不得使用固定坐标。

- [ ] **Step 6: 验收百度自动关闭成功页**

执行一条百度命令并等待明确成功页。

Expected:

- Profile 返回 `SUCCESS / CLICK_DONE`。
- 日志出现“成功页清理”。
- 点击成功时证据包含 `cleanup=success`，页面离开结果页。
- 随后重新执行一条百度命令，无需人工点击“完成”。

如果清理点击失败：

- 结果仍为 `SUCCESS`。
- 证据包含 `cleanup=failed` 和具体原因。
- 下一条百度命令首先再次识别并清理该成功页。

- [ ] **Step 7: 验收后台恢复和整笔事务**

在高德第一次“开始打卡”后、第二次点击前把双地图切到后台，再恢复双地图窗口。

Expected:

- 当前命令不变。
- Worker 记录后台等待，不写入地图窗口失败。
- 恢复后继续第二次“开始打卡”，不重新从“我的”开始。
- 百度与高德完成后，事务进入 `COMPLETED`。
- NearbyCheckIn 主界面不会与单张地图组成错误的单屏双窗口。

- [ ] **Step 8: 更新本地 SDD 证据并做最终验证**

在三个忽略文件中记录构建命令、APK 哈希、服务状态、高德页面动作序列、两次点击间隔、百度清理结果、事务结果和遗留风险。

最后运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
git diff --check
git status --short --branch
```

Expected: 最终测试与构建成功，工作树没有未解释的跟踪文件改动。
