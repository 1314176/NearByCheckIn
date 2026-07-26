# 百度语义边界手势回退实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在百度地图 `21.18.50` 的无障碍标签节点不提供点击动作时，使用唯一语义节点的实时有效边界提交一次受限手势，同时保持高德和事务流程不变。

**Architecture:** 用纯 Kotlin 策略选择唯一、安全的手势目标；百度快照把安全目标标记为 `gestureTarget`，Profile 仍只读取纯 Kotlin 快照。Android Adapter 优先执行节点点击，失败后才请求窄接口提交手势，Worker 继续通过下一轮页面识别判断结果。

**Tech Stack:** Kotlin、Android AccessibilityService、Jetpack/Android Gradle Plugin、JUnit 4、Gradle。

## Global Constraints

- 手势回退仅限百度地图已验证版本 `21.18.50`。
- 仅使用 `MapUiActionLabels` 中当前动作的白名单标签，不使用固定绝对坐标或屏幕比例坐标。
- 匹配节点必须唯一、启用、对用户可见、边界有效，且中心点位于目标根窗口边界内。
- 节点 `ACTION_CLICK` 始终优先；手势每个动作最多提交一次。
- 高德地图继续使用现有节点点击路径。
- 不修改事务仓储、命令串行、重试次数、分屏创建策略或候选地点规则。
- 不新增第三方依赖；项目最低 API 保持 26。
- 新增或修改函数的中文功能注释放在函数定义上方。

---

## 文件结构

- 新建 `app/src/main/java/com/wu/nearbycheckin/platform/maps/MapUiGestureTargetPolicy.kt`：纯 Kotlin 边界、候选节点和目标选择规则。
- 新建 `app/src/test/java/com/wu/nearbycheckin/platform/maps/MapUiGestureTargetPolicyTest.kt`：目标选择正常与拒绝路径。
- 修改 `MapUiProfileModels.kt`：区分节点点击能力和百度语义手势目标。
- 修改 `MapUiActionLabels.kt`：提供单个地图的全部动作白名单标签。
- 修改 `MapUiSnapshotCollector.kt`：从 Android 节点生成纯 Kotlin 手势候选并标记安全目标。
- 修改 `BaiduMapAdapter.kt`、`BaiduUiProfile.kt`：仅在已验证版本启用手势目标识别。
- 新建 `AccessibilityClickResult.kt`：把纯结果类型从 Android 节点执行文件中分离出来。
- 新建 `MapUiGestureFallbackExecutor.kt`：纯 Kotlin 手势提交结果映射。
- 新建 `AccessibilityGestureTapDispatcher.kt`：封装 `AccessibilityService.dispatchGesture`。
- 修改 `AccessibilityNodeActions.kt`：节点点击失败后调用安全目标策略和手势回退。
- 修改 `DualMapCommandWorker.kt`：向百度 Adapter 传递应用版本和手势提交能力。
- 修改 `accessibility_service_config.xml`：声明 `canPerformGestures`。

### Task 1: 纯 Kotlin 手势目标策略

**Files:**
- Create: `app/src/main/java/com/wu/nearbycheckin/platform/maps/MapUiGestureTargetPolicy.kt`
- Create: `app/src/test/java/com/wu/nearbycheckin/platform/maps/MapUiGestureTargetPolicyTest.kt`

**Interfaces:**
- Consumes: 无。
- Produces: `MapUiBounds`、`MapUiGestureCandidate`、`MapUiGestureTarget`、`MapUiGestureTargetSelection`、`MapUiGestureTargetPolicy.select(candidates, windowBounds)`。

- [ ] **Step 1: 写入失败测试**

创建测试，固定唯一目标和全部关闭条件：

```kotlin
class MapUiGestureTargetPolicyTest {

    private val window = MapUiBounds(0, 100, 1080, 2200)

    // 验证唯一可见且有效的候选节点生成中心点。
    @Test
    fun selectsCenterOfSingleSafeCandidate() {
        val result = MapUiGestureTargetPolicy.select(
            candidates = listOf(
                MapUiGestureCandidate(
                    enabled = true,
                    visible = true,
                    bounds = MapUiBounds(900, 2000, 1080, 2200)
                )
            ),
            windowBounds = window
        )

        assertEquals(MapUiGestureTarget(990, 2100), result.target)
        assertEquals("", result.rejectionReason)
    }

    // 验证零个或多个匹配节点均关闭回退。
    @Test
    fun rejectsNonUniqueCandidates() {
        assertEquals(
            "匹配节点数量不是一个",
            MapUiGestureTargetPolicy.select(emptyList(), window)
                .rejectionReason
        )
        val candidate = MapUiGestureCandidate(
            enabled = true,
            visible = true,
            bounds = MapUiBounds(900, 2000, 1080, 2200)
        )
        assertEquals(
            "匹配节点数量不是一个",
            MapUiGestureTargetPolicy.select(
                listOf(candidate, candidate),
                window
            ).rejectionReason
        )
    }

    // 验证禁用、不可见、空边界和中心越界均关闭回退。
    @Test
    fun rejectsUnsafeCandidateStateAndBounds() {
        fun rejection(
            candidate: MapUiGestureCandidate,
            windowBounds: MapUiBounds = window
        ) = MapUiGestureTargetPolicy.select(
            listOf(candidate),
            windowBounds
        ).rejectionReason

        assertEquals(
            "节点未启用",
            rejection(
                MapUiGestureCandidate(
                    false,
                    true,
                    MapUiBounds(900, 2000, 1080, 2200)
                )
            )
        )
        assertEquals(
            "节点对用户不可见",
            rejection(
                MapUiGestureCandidate(
                    true,
                    false,
                    MapUiBounds(900, 2000, 1080, 2200)
                )
            )
        )
        assertEquals(
            "节点边界无效",
            rejection(
                MapUiGestureCandidate(
                    true,
                    true,
                    MapUiBounds(900, 2000, 900, 2200)
                )
            )
        )
        assertEquals(
            "节点中心不在目标窗口内",
            rejection(
                MapUiGestureCandidate(
                    true,
                    true,
                    MapUiBounds(1080, 2000, 1280, 2200)
                )
            )
        )
    }
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
$env:GRADLE_USER_HOME='D:\Softwares\Android\GradleUserHome'
$env:ANDROID_HOME='D:\Softwares\Android\Sdk'
$env:ANDROID_SDK_ROOT='D:\Softwares\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.maps.MapUiGestureTargetPolicyTest" --console=plain
```

Expected: 因 `MapUiBounds` 和 `MapUiGestureTargetPolicy` 尚不存在而编译失败。

- [ ] **Step 3: 实现最小纯 Kotlin 策略**

```kotlin
data class MapUiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    // 判断边界是否包含指定屏幕点。
    fun contains(x: Int, y: Int): Boolean {
        return x >= left && x < right && y >= top && y < bottom
    }
}

data class MapUiGestureCandidate(
    val enabled: Boolean,
    val visible: Boolean,
    val bounds: MapUiBounds
)

data class MapUiGestureTarget(val x: Int, val y: Int)

data class MapUiGestureTargetSelection(
    val target: MapUiGestureTarget? = null,
    val rejectionReason: String = ""
)

object MapUiGestureTargetPolicy {

    // 从唯一安全候选节点生成运行时边界中心点。
    fun select(
        candidates: List<MapUiGestureCandidate>,
        windowBounds: MapUiBounds
    ): MapUiGestureTargetSelection {
        if (candidates.size != 1) {
            return MapUiGestureTargetSelection(
                rejectionReason = "匹配节点数量不是一个"
            )
        }
        val candidate = candidates.single()
        if (!candidate.enabled) {
            return MapUiGestureTargetSelection(
                rejectionReason = "节点未启用"
            )
        }
        if (!candidate.visible) {
            return MapUiGestureTargetSelection(
                rejectionReason = "节点对用户不可见"
            )
        }
        if (candidate.bounds.width <= 0 || candidate.bounds.height <= 0) {
            return MapUiGestureTargetSelection(
                rejectionReason = "节点边界无效"
            )
        }
        if (windowBounds.width <= 0 || windowBounds.height <= 0) {
            return MapUiGestureTargetSelection(
                rejectionReason = "目标窗口边界无效"
            )
        }
        val target = MapUiGestureTarget(
            x = candidate.bounds.left + candidate.bounds.width / 2,
            y = candidate.bounds.top + candidate.bounds.height / 2
        )
        if (!windowBounds.contains(target.x, target.y)) {
            return MapUiGestureTargetSelection(
                rejectionReason = "节点中心不在目标窗口内"
            )
        }
        return MapUiGestureTargetSelection(target = target)
    }
}
```

- [ ] **Step 4: 运行目标测试并确认 GREEN**

Run: Task 1 Step 2 的相同命令。

Expected: `MapUiGestureTargetPolicyTest` 全部通过。

- [ ] **Step 5: 提交 Task 1**

```powershell
git add app/src/main/java/com/wu/nearbycheckin/platform/maps/MapUiGestureTargetPolicy.kt app/src/test/java/com/wu/nearbycheckin/platform/maps/MapUiGestureTargetPolicyTest.kt
git commit -m "新增地图语义手势目标策略"
```

### Task 2: 百度快照与 Profile 手势目标语义

**Files:**
- Modify: `app/src/main/java/com/wu/nearbycheckin/platform/maps/MapUiProfileModels.kt`
- Modify: `app/src/main/java/com/wu/nearbycheckin/platform/maps/MapUiActionLabels.kt`
- Modify: `app/src/main/java/com/wu/nearbycheckin/platform/maps/MapUiSnapshotCollector.kt`
- Modify: `app/src/main/java/com/wu/nearbycheckin/platform/maps/BaiduMapAdapter.kt`
- Modify: `app/src/main/java/com/wu/nearbycheckin/platform/maps/BaiduUiProfile.kt`
- Modify: `app/src/test/java/com/wu/nearbycheckin/platform/maps/BaiduUiProfileTest.kt`
- Modify: `app/src/test/java/com/wu/nearbycheckin/platform/maps/AmapUiProfileTest.kt`
- Modify: `app/src/test/java/com/wu/nearbycheckin/platform/maps/MapUiActionLabelsTest.kt`
- Modify: `app/src/test/java/com/wu/nearbycheckin/platform/maps/MapUiProfileModelsTest.kt`

**Interfaces:**
- Consumes: `MapUiGestureTargetPolicy.select(...)`。
- Produces: `MapUiNodeSnapshot.gestureTarget`、`MapUiSnapshot.hasActionableLabel(label)`、`MapUiActionLabels.allForProvider(provider)`，以及 `MapUiSnapshotCollector.collect(..., gestureLabels)`。

- [ ] **Step 1: 写入失败的快照/Profile 契约测试**

在相应测试类加入：

```kotlin
// 验证百度允许由安全语义手势目标进入“我的”。
@Test
fun opensMineFromGestureTarget() {
    val decision = profile.decide(
        MapUiSnapshot(
            packageName = profile.packageName,
            appVersion = profile.supportedAppVersion,
            nodes = listOf(
                MapUiNodeSnapshot(
                    text = "我的",
                    gestureTarget = true
                )
            )
        )
    )

    assertEquals(MapUiAction.CLICK_MINE, decision.action)
}
```

```kotlin
// 验证手势目标属于可执行标签但不伪装成节点点击能力。
@Test
fun distinguishesNodeClickAndGestureTarget() {
    val snapshot = MapUiSnapshot(
        packageName = "sample",
        appVersion = "1.0",
        nodes = listOf(
            MapUiNodeSnapshot(
                text = "我的",
                clickable = false,
                gestureTarget = true
            )
        )
    )

    assertFalse(snapshot.hasClickableLabel("我的"))
    assertTrue(snapshot.hasActionableLabel("我的"))
}
```

```kotlin
// 验证高德 Profile 不把手势目标作为可点击动作。
@Test
fun doesNotUseGestureTargetForAmap() {
    val decision = AmapUiProfile.decide(
        MapUiSnapshot(
            packageName = AmapUiProfile.packageName,
            appVersion = AmapUiProfile.supportedAppVersion,
            nodes = listOf(
                MapUiNodeSnapshot(
                    text = "我的",
                    gestureTarget = true
                )
            )
        )
    )

    assertEquals(MapUiPage.UNKNOWN, decision.page)
    assertEquals(MapUiAction.WAIT, decision.action)
}
```

扩展 `MapUiActionLabelsTest`，断言百度全部白名单按首次出现顺序为：

```kotlin
assertEquals(
    listOf(
        "我的",
        "打卡点",
        "立即打卡",
        "立刻打卡",
        "首页",
        "知道了"
    ),
    MapUiActionLabels.allForProvider(MapProvider.BAIDU)
)
```

- [ ] **Step 2: 运行目标测试并确认 RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.maps.BaiduUiProfileTest" --tests "com.wu.nearbycheckin.platform.maps.AmapUiProfileTest" --tests "com.wu.nearbycheckin.platform.maps.MapUiProfileModelsTest" --tests "com.wu.nearbycheckin.platform.maps.MapUiActionLabelsTest" --console=plain
```

Expected: 因 `gestureTarget`、`hasActionableLabel` 和 `allForProvider` 尚不存在而编译失败。

- [ ] **Step 3: 实现纯快照契约**

在 `MapUiNodeSnapshot` 增加：

```kotlin
val gestureTarget: Boolean = false
```

在 `MapUiSnapshot` 增加：

```kotlin
// 判断任一节点是否能通过节点点击或安全语义手势执行指定标签。
fun hasActionableLabel(label: String): Boolean {
    return nodes.any { node ->
        (node.clickable || node.gestureTarget) &&
                node.labels().any { value ->
                    value.matchesUiLabel(label)
                }
    }
}
```

指纹加入 `gestureTarget`，避免页面动作能力变化被视为相同页面：

```kotlin
"${node.labels().joinToString("/")}:${node.clickable}:${node.gestureTarget}"
```

在 `MapUiActionLabels` 增加：

```kotlin
// 返回指定地图所有动作的去重白名单标签。
fun allForProvider(provider: MapProvider): List<String> {
    return MapUiAction.entries
        .flatMap { action -> forProvider(provider, action) }
        .distinct()
}
```

仅把 `BaiduUiProfile` 的交互标签判断替换为 `hasActionableLabel`；成功、已完成和阻断证据保持原判断。`AmapUiProfile` 不修改。

- [ ] **Step 4: 实现 Android 节点到安全手势目标的快照桥**

把 `MapUiSnapshotCollector.collect` 扩展为：

```kotlin
fun collect(
    rootNode: AccessibilityNodeInfo?,
    packageName: String,
    appVersion: String,
    gestureLabels: List<String> = emptyList()
): MapUiSnapshot
```

遍历时为每个节点同时保留：

```kotlin
private data class CollectedNode(
    val snapshot: MapUiNodeSnapshot,
    val candidate: MapUiGestureCandidate
)
```

用 `Rect` 转换为纯 Kotlin 边界：

```kotlin
// 把节点屏幕边界转换为纯 Kotlin 边界。
private fun AccessibilityNodeInfo.toMapUiBounds(): MapUiBounds {
    val rect = Rect().also { bounds ->
        getBoundsInScreen(bounds)
    }
    return MapUiBounds(rect.left, rect.top, rect.right, rect.bottom)
}
```

对每个 `gestureLabel`：

1. 按现有 `matchesUiLabel` 规则筛选节点。
2. 把候选传给 `MapUiGestureTargetPolicy.select`。
3. 仅当结果包含目标点时，把唯一命中节点复制为 `gestureTarget = true`。

根窗口为空或根边界无效时不产生手势目标。现有深度 12、节点总数 300 和六层可执行祖先判断保持不变。

`BaiduMapAdapter.captureSnapshot` 仅在版本完全匹配时传入白名单：

```kotlin
gestureLabels = if (
    appVersion == BaiduUiProfile.supportedAppVersion
) {
    MapUiActionLabels.allForProvider(MapProvider.BAIDU)
} else {
    emptyList()
}
```

高德调用不传 `gestureLabels`。

- [ ] **Step 5: 运行 Profile 契约测试并确认 GREEN**

Run: Task 2 Step 2 的相同命令。

Expected: 四个测试类全部通过，高德手势目标测试返回 `UNKNOWN/WAIT`。

- [ ] **Step 6: 运行地图模块全部单元测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.maps.*" --console=plain
```

Expected: 全部通过。

- [ ] **Step 7: 提交 Task 2**

```powershell
git add app/src/main/java/com/wu/nearbycheckin/platform/maps app/src/test/java/com/wu/nearbycheckin/platform/maps
git commit -m "支持百度语义手势页面识别"
```

### Task 3: 节点点击后的受限手势执行桥

**Files:**
- Create: `app/src/main/java/com/wu/nearbycheckin/platform/maps/AccessibilityClickResult.kt`
- Create: `app/src/main/java/com/wu/nearbycheckin/platform/maps/MapUiGestureFallbackExecutor.kt`
- Create: `app/src/main/java/com/wu/nearbycheckin/platform/accessibility/AccessibilityGestureTapDispatcher.kt`
- Create: `app/src/test/java/com/wu/nearbycheckin/platform/maps/MapUiGestureFallbackExecutorTest.kt`
- Modify: `app/src/main/java/com/wu/nearbycheckin/platform/maps/AccessibilityNodeActions.kt`
- Modify: `app/src/main/java/com/wu/nearbycheckin/platform/maps/BaiduMapAdapter.kt`
- Modify: `app/src/main/java/com/wu/nearbycheckin/platform/accessibility/DualMapCommandWorker.kt`
- Modify: `app/src/main/res/xml/accessibility_service_config.xml`

**Interfaces:**
- Consumes: Task 1 的 `MapUiGestureTargetSelection`。
- Produces: `MapUiGestureFallbackExecutor.execute(selection, tap)`、`AccessibilityGestureTapDispatcher.tap(x, y)`，以及百度 Adapter 的版本受限手势执行参数。

- [ ] **Step 1: 写入失败的纯手势提交测试**

```kotlin
class MapUiGestureFallbackExecutorTest {

    // 验证已接受目标只提交一次手势。
    @Test
    fun submitsAcceptedTargetOnce() {
        var callCount = 0
        val result = MapUiGestureFallbackExecutor.execute(
            selection = MapUiGestureTargetSelection(
                target = MapUiGestureTarget(990, 2100)
            ),
            tap = { _, _ ->
                callCount += 1
                true
            }
        )

        assertTrue(result.success)
        assertEquals(1, callCount)
        assertEquals("语义边界手势已提交", result.message)
    }

    // 验证拒绝目标不调用手势。
    @Test
    fun doesNotTapRejectedTarget() {
        var called = false
        val result = MapUiGestureFallbackExecutor.execute(
            selection = MapUiGestureTargetSelection(
                rejectionReason = "匹配节点数量不是一个"
            ),
            tap = { _, _ ->
                called = true
                true
            }
        )

        assertFalse(result.success)
        assertFalse(called)
        assertEquals(
            "语义边界手势已拒绝：匹配节点数量不是一个",
            result.message
        )
    }

    // 验证平台拒绝手势提交时返回失败。
    @Test
    fun reportsPlatformSubmissionFailure() {
        val result = MapUiGestureFallbackExecutor.execute(
            selection = MapUiGestureTargetSelection(
                target = MapUiGestureTarget(990, 2100)
            ),
            tap = { _, _ -> false }
        )

        assertFalse(result.success)
        assertEquals("语义边界手势提交失败", result.message)
    }
}
```

- [ ] **Step 2: 运行测试并确认 RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.maps.MapUiGestureFallbackExecutorTest" --console=plain
```

Expected: 因 `MapUiGestureFallbackExecutor` 尚不存在而编译失败。

- [ ] **Step 3: 分离结果类型并实现纯回退执行器**

从 `AccessibilityNodeActions.kt` 删除原定义，并在不导入 Android 类型的 `AccessibilityClickResult.kt` 写入：

```kotlin
package com.wu.nearbycheckin.platform.maps

/**
 * 保存一次无障碍节点或语义手势点击结果。
 *
 * @property success 点击是否成功提交。
 * @property message 点击结果或失败原因。
 */
data class AccessibilityClickResult(
    val success: Boolean,
    val message: String
)
```

```kotlin
object MapUiGestureFallbackExecutor {

    // 根据已校验目标提交一次手势并返回确定结果。
    fun execute(
        selection: MapUiGestureTargetSelection,
        tap: (Int, Int) -> Boolean
    ): AccessibilityClickResult {
        val target = selection.target
            ?: return AccessibilityClickResult(
                success = false,
                message =
                    "语义边界手势已拒绝：${selection.rejectionReason}"
            )
        val submitted = tap(target.x, target.y)
        return AccessibilityClickResult(
            success = submitted,
            message = if (submitted) {
                "语义边界手势已提交"
            } else {
                "语义边界手势提交失败"
            }
        )
    }
}
```

- [ ] **Step 4: 扩展节点执行器并保持节点点击优先**

扩展签名：

```kotlin
fun clickFirstMatchingLabel(
    rootNode: AccessibilityNodeInfo?,
    labels: List<String>,
    gestureTap: ((Int, Int) -> Boolean)? = null
): AccessibilityClickResult
```

保持现有匹配节点和六层可执行祖先逻辑。如果找到 `clickableNode`，立即调用 `ACTION_CLICK` 并返回，不调用手势。

仅当没有可执行节点且 `gestureTap != null` 时：

```kotlin
val windowBounds = rootNode.toMapUiBounds()
val selection = MapUiGestureTargetPolicy.select(
    candidates = matchingNodes.map { node ->
        MapUiGestureCandidate(
            enabled = node.isEnabled,
            visible = node.isVisibleToUser,
            bounds = node.toMapUiBounds()
        )
    },
    windowBounds = windowBounds
)
return MapUiGestureFallbackExecutor.execute(selection, gestureTap)
```

无 `gestureTap` 时保留原“节点不可点击”失败结果。

- [ ] **Step 5: 实现 Android 手势分发器**

```kotlin
class AccessibilityGestureTapDispatcher(
    private val service: AccessibilityService
) {

    // 向系统提交一次短时屏幕点击手势。
    fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    TAP_DURATION_MILLIS
                )
            )
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private companion object {
        const val TAP_DURATION_MILLIS = 80L
    }
}
```

在 `accessibility_service_config.xml` 增加：

```xml
android:canPerformGestures="true"
```

- [ ] **Step 6: 只把手势能力接入百度已验证版本**

`BaiduMapAdapter.execute` 新签名：

```kotlin
fun execute(
    rootNode: AccessibilityNodeInfo?,
    action: MapUiAction,
    appVersion: String,
    gestureTap: (Int, Int) -> Boolean
): AccessibilityClickResult
```

只有 `appVersion == BaiduUiProfile.supportedAppVersion` 时把 `gestureTap` 传给 `AccessibilityNodeActions`；其他版本传 `null`。`AmapMapAdapter.execute` 不增加手势参数。

在 Worker 中创建：

```kotlin
private val gestureTapDispatcher =
    AccessibilityGestureTapDispatcher(service)
```

把当前已读取的 `currentAppVersion` 传入 `MapProvider.execute`，百度分支调用：

```kotlin
BaiduMapAdapter.execute(
    rootNode = rootNode,
    action = action,
    appVersion = appVersion,
    gestureTap = gestureTapDispatcher::tap
)
```

高德分支继续调用原执行方法。手势提交成功后仍使用现有步骤延时重新识别页面，不直接写入打卡成功。

- [ ] **Step 7: 运行目标测试并确认 GREEN**

Run: Task 3 Step 2 的相同命令。

Expected: 三个回退执行测试全部通过。

- [ ] **Step 8: 运行全部地图测试、App 测试和构建**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.maps.*" :app:assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 9: 检查差异并提交 Task 3**

```powershell
git diff --check
git status --short
git add app/src/main/java/com/wu/nearbycheckin/platform app/src/main/res/xml/accessibility_service_config.xml app/src/test/java/com/wu/nearbycheckin/platform/maps
git commit -m "实现百度语义边界手势回退"
```

### Task 4: 小米 14 真机闭环与记录

**Files:**
- Modify (not tracked): `.superpowers/sdd/2026-07-26-dual-map-ui-profiles/baidu-unknown-diagnosis.md`
- Modify (not tracked): `.superpowers/sdd/2026-07-26-dual-map-ui-profiles/progress.md`
- Modify (not tracked): `.superpowers/sdd/2026-07-26-dual-map-ui-profiles/task-6-report.md`

**Interfaces:**
- Consumes: Task 3 的 Debug APK 和结构化 Worker 日志。
- Produces: 百度、高德和整笔事务的真机验收结论。

- [ ] **Step 1: 安装本工作树构建产物**

```powershell
$env:GRADLE_USER_HOME='D:\Softwares\Android\GradleUserHome'
$env:ANDROID_HOME='D:\Softwares\Android\Sdk'
$env:ANDROID_SDK_ROOT='D:\Softwares\Android\Sdk'
$env:ANDROID_SERIAL='e179ad0b'
.\gradlew.bat :app:installDebug --console=plain
```

如果 Gradle 安装后设备 APK 哈希与本地制品不一致，使用同一产物覆盖安装：

```powershell
D:\Softwares\Android\Sdk\platform-tools\adb.exe -s e179ad0b install -r -t app\build\outputs\apk\debug\app-debug.apk
```

- [ ] **Step 2: 确认无障碍服务能力与状态**

```powershell
D:\Softwares\Android\Sdk\platform-tools\adb.exe -s e179ad0b shell dumpsys accessibility
```

Expected:

- NearbyCheckIn 服务同时出现在 Enabled 和 Bound。
- `Crashed services:{}`。
- 服务配置报告手势能力。

不得使用 `uiautomator`，避免临时测试服务抢占并解绑 NearbyCheckIn。

- [ ] **Step 3: 从百度首页执行一条新巡航**

保持设备解锁、亮屏，并停留百度 `21.18.50` 首页。启动或恢复 NearbyCheckIn 巡航，只从 Repository 解析以下安全字段：

- `automationStatus`
- 包含 `页面识别`、`动作执行`、`语义边界手势` 的运行日志消息
- 事务状态和地图命令状态

Expected:

- 首页识别为 `HOME/CLICK_MINE`。
- 首次动作结果包含“语义边界手势已提交”。
- 页面离开首页并继续识别“打卡点”或实际后续页面。
- 不再出现首页三次 `UNKNOWN/WAIT` 后的 `BAIDU_PROFILE_UNSUPPORTED`。

- [ ] **Step 4: 完成百度和高德完整验收**

Expected:

- 百度完成打卡或识别当前地点已完成。
- 高德命令随后被领取并完成。
- 整笔事务进入 `COMPLETED`。
- 后台切换和恢复不改变当前命令，不生成 NearbyCheckIn 与单张地图组成的错误单窗口。

若后续百度页面也暴露“标签存在但节点不可点击”，必须先查看该动作的唯一性和边界日志；只有同样满足本设计全部安全条件时才允许现有通用白名单回退执行。

- [ ] **Step 5: 更新诊断与 SDD 记录**

在三个本地报告中记录：

- 原六层祖先假设已被 `not_found_within_20` 否定。
- 真机手势目标中心只来源于唯一语义节点的实时边界。
- 每张地图和整笔事务的实际结果。
- 任何阻塞、失败码和未覆盖场景。

- [ ] **Step 6: 最终验证**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
git diff --check
git status --short --branch
```

Expected: 构建和测试成功；工作树只包含明确保留的未提交诊断文件，或完全干净。
