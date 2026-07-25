# 百度地图接管当前分屏窗格 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让百度地图稳定替换 NearbyCheckIn 所在分屏窗格，最终形成“百度地图 + 高德地图”。

**Architecture:** 新增纯 Kotlin 的窗格启动标记策略，集中生成百度当前窗格接管所需标记。`MainActivity` 只负责把策略结果写入百度启动 Intent，现有高德相邻窗格和后台任务保留流程保持不变。

**Tech Stack:** Kotlin、Android Activity/Intent、JUnit 4、Gradle。

## Global Constraints

- 只修改百度当前窗格接管链路，不修改打卡命令、窗口诊断和巡航逻辑。
- 百度启动必须包含 `NEW_TASK`、`REORDER_TO_FRONT`。
- 百度启动不得包含 `LAUNCH_ADJACENT`、`MULTIPLE_TASK`。
- 不新增第三方依赖。
- 未经用户单独要求，不执行 Git 提交。

---

### Task 1: 百度当前窗格启动标记策略

**Files:**
- Create: `app/src/main/java/com/wu/nearbycheckin/platform/multiwindow/DualMapPaneLaunchPolicy.kt`
- Create: `app/src/test/java/com/wu/nearbycheckin/platform/multiwindow/DualMapPaneLaunchPolicyTest.kt`
- Modify: `app/src/main/java/com/wu/nearbycheckin/presentation/main/MainActivity.kt`

**Interfaces:**
- Consumes: `Intent.flags` 和 Android `Intent.FLAG_ACTIVITY_*` 常量。
- Produces: `DualMapPaneLaunchPolicy.currentPaneReplacementFlags(existingFlags: Int): Int`。

- [x] **Step 1: 写入失败测试**

```kotlin
class DualMapPaneLaunchPolicyTest {

    @Test
    fun `百度接管当前窗格时保留独立任务与任务重排标记`() {
        val flags = DualMapPaneLaunchPolicy.currentPaneReplacementFlags(0)

        assertTrue(flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
    }

    @Test
    fun `百度接管当前窗格时移除相邻窗格与多任务标记`() {
        val flags = DualMapPaneLaunchPolicy.currentPaneReplacementFlags(
            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        )

        assertEquals(0, flags and Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        assertEquals(0, flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }
}
```

- [x] **Step 2: 运行测试并确认因策略缺失而失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.multiwindow.DualMapPaneLaunchPolicyTest"`

Expected: FAIL，提示 `Unresolved reference: DualMapPaneLaunchPolicy`。

- [x] **Step 3: 写入最小策略实现**

```kotlin
internal object DualMapPaneLaunchPolicy {

    // 生成百度地图替换当前分屏窗格所需的启动标记。
    fun currentPaneReplacementFlags(existingFlags: Int): Int {
        val excludedFlags =
            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        return (existingFlags and excludedFlags.inv()) or
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
    }
}
```

- [x] **Step 4: 将策略应用到百度启动 Intent**

```kotlin
val baiduIntent = Intent(baiduLaunchIntent).apply {
    flags = DualMapPaneLaunchPolicy.currentPaneReplacementFlags(flags)
}
```

- [x] **Step 5: 运行策略测试、全量单元测试和 Debug 构建**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.multiwindow.DualMapPaneLaunchPolicyTest"`

Expected: PASS。

Run: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`

Expected: `BUILD SUCCESSFUL`。

- [x] **Step 6: 安装并进行设备验证**

Run: `.\gradlew.bat :app:installDebug`

Expected: 安装成功。启动双地图后，`dumpsys activity activities` 显示百度和高德分别位于同一个分屏根任务的两个子任务中；无障碍诊断显示百度和高德均为 `true`。

- [x] **Step 7: 保留修改等待用户验收**

检查 `git status --short`，确认未提交且没有覆盖其他现有修改。
