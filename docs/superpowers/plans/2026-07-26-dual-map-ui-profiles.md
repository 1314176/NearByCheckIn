# R-082/R-083 双地图 UI Profile Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为百度地图 21.18.50 和高德地图 16.21.0.2010 建立版本化、页面感知的 UI Profile，让双地图命令根据当前页面自适应执行并完成整笔打卡事务。

**Architecture:** 使用纯 Kotlin `MapUiSnapshot -> MapUiDecision` 契约隔离页面识别规则，百度和高德各自实现 Profile。Android Adapter 只采集节点和执行一个动作，`DualMapCommandWorker` 循环请求决策并保持现有串行、后台等待和幂等回写行为。

**Tech Stack:** Kotlin、Android Accessibility API、JUnit 4、Gradle、小米 14 真机。

## Global Constraints

- 任务范围只包含 R-082 和 R-083，不修改候选发现、事务排序、重试次数、永久去重和分屏创建规则。
- 百度已验证版本为 `21.18.50`，高德已验证版本为 `16.21.0.2010`。
- 不使用固定屏幕坐标，不自动绕过登录、验证码、权限或风险验证。
- Profile 规则不得 import `android.*`；Android 节点只存在于 Adapter。
- Worker 必须保留命令串行、后台等待、租约和幂等结果回写。
- 新增或修改函数的中文功能注释放在函数定义上一行。
- 不新增第三方依赖。
- 未经用户单独要求，不执行 Git 提交。

---

### Task 1: 纯 Kotlin UI Profile 契约

**Files:**
- Create: `app/src/main/java/com/wu/nearbycheckin/platform/maps/MapUiProfileModels.kt`
- Create: `app/src/test/java/com/wu/nearbycheckin/platform/maps/MapUiProfileModelsTest.kt`

**Interfaces:**
- Produces: `MapUiNodeSnapshot`、`MapUiSnapshot`、`MapUiPage`、`MapUiAction`、`MapUiDecision`、`ProviderUiProfile`。
- Consumers: `BaiduUiProfile`、`AmapUiProfile`、两个地图 Adapter、`DualMapCommandWorker`。

- [ ] **Step 1: 写入快照匹配和指纹的失败测试**

```kotlin
class MapUiProfileModelsTest {

    // 验证标签匹配同时支持文本、描述和可点击过滤。
    @Test
    fun matchesTextDescriptionAndClickableState() {
        val snapshot = MapUiSnapshot(
            packageName = "sample",
            appVersion = "1.0",
            nodes = listOf(
                MapUiNodeSnapshot(text = "我的", clickable = false),
                MapUiNodeSnapshot(
                    description = "打卡，按钮",
                    clickable = true
                )
            )
        )

        assertTrue(snapshot.hasLabel("我的"))
        assertTrue(snapshot.hasClickableLabel("打卡"))
        assertFalse(snapshot.hasClickableLabel("我的"))
    }

    // 验证相同节点内容产生稳定页面指纹。
    @Test
    fun buildsStableFingerprintFromNormalizedNodes() {
        val first = MapUiSnapshot(
            packageName = "sample",
            appVersion = "1.0",
            nodes = listOf(
                MapUiNodeSnapshot(text = " 首页 "),
                MapUiNodeSnapshot(description = "我的")
            )
        )
        val second = first.copy(
            nodes = listOf(
                MapUiNodeSnapshot(text = "首页"),
                MapUiNodeSnapshot(description = "我的")
            )
        )

        assertEquals(first.fingerprint(), second.fingerprint())
    }
}
```

- [ ] **Step 2: 运行测试并确认契约缺失**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.maps.MapUiProfileModelsTest"
```

Expected: FAIL，提示 `Unresolved reference: MapUiSnapshot`。

- [ ] **Step 3: 写入最小契约实现**

```kotlin
data class MapUiNodeSnapshot(
    val text: String = "",
    val description: String = "",
    val viewId: String = "",
    val clickable: Boolean = false
) {

    // 返回去除首尾空白后的全部非空标签。
    fun labels(): List<String> {
        return listOf(text, description, viewId)
            .map(String::trim)
            .filter(String::isNotBlank)
    }
}

data class MapUiSnapshot(
    val packageName: String,
    val appVersion: String,
    val nodes: List<MapUiNodeSnapshot>
) {

    // 判断任一节点是否包含指定标签。
    fun hasLabel(label: String): Boolean {
        return nodes.any { node ->
            node.labels().any { value -> value.matchesUiLabel(label) }
        }
    }

    // 判断任一可点击节点是否包含指定标签。
    fun hasClickableLabel(label: String): Boolean {
        return nodes.any { node ->
            node.clickable &&
                    node.labels().any { value ->
                        value.matchesUiLabel(label)
                    }
        }
    }

    // 判断页面是否包含任一指定标签。
    fun hasAnyLabel(labels: List<String>): Boolean {
        return labels.any(::hasLabel)
    }

    // 判断页面是否包含任一可点击标签。
    fun hasAnyClickableLabel(labels: List<String>): Boolean {
        return labels.any(::hasClickableLabel)
    }

    // 判断页面文本、描述或资源 ID 是否包含指定片段。
    fun containsLabelFragment(fragment: String): Boolean {
        return nodes.any { node ->
            node.labels().any { value -> value.contains(fragment) }
        }
    }

    // 判断页面是否包含任一指定片段。
    fun containsAnyLabelFragment(
        fragments: List<String>
    ): Boolean {
        return fragments.any(::containsLabelFragment)
    }

    // 生成用于识别页面是否变化的稳定指纹。
    fun fingerprint(): String {
        return nodes.joinToString(separator = "|") { node ->
            "${node.labels().joinToString("/")}:${node.clickable}"
        }
    }
}

enum class MapUiPage {
    HOME,
    MINE,
    FOOTPRINT,
    CHECK_IN,
    SUCCESS,
    ALREADY_COMPLETED,
    BLOCKED,
    UNKNOWN
}

enum class MapUiAction {
    CLICK_MINE,
    CLICK_FOOTPRINT,
    CLICK_CHECK_IN_POINT,
    CLICK_CHECK_IN,
    CLICK_HOME,
    DISMISS_PROMPT,
    WAIT,
    BLOCK
}

data class MapUiDecision(
    val page: MapUiPage,
    val action: MapUiAction?,
    val evidence: String,
    val reason: String = ""
)

interface ProviderUiProfile {
    val packageName: String
    val supportedAppVersion: String
    val profileVersion: String

    // 根据纯 Kotlin 页面快照返回下一动作或终态。
    fun decide(snapshot: MapUiSnapshot): MapUiDecision
}

// 匹配精确标签或无障碍描述中的按钮后缀。
private fun String.matchesUiLabel(label: String): Boolean {
    return this == label ||
            startsWith("$label,") ||
            startsWith("$label，")
}
```

- [ ] **Step 4: 运行契约测试**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.maps.MapUiProfileModelsTest"
```

Expected: PASS。

---

### Task 2: R-082 百度 21.18.50 Profile

**Files:**
- Create: `app/src/main/java/com/wu/nearbycheckin/platform/maps/BaiduUiProfile.kt`
- Create: `app/src/test/java/com/wu/nearbycheckin/platform/maps/BaiduUiProfileTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `MapUiSnapshot` 和 `MapUiDecision`。
- Produces: `BaiduUiProfile.decide(snapshot: MapUiSnapshot): MapUiDecision`。

- [ ] **Step 1: 写入百度页面决策的失败测试**

```kotlin
class BaiduUiProfileTest {

    private val profile = BaiduUiProfile

    // 验证首页进入“我的”。
    @Test
    fun opensMineFromHome() {
        assertEquals(
            MapUiAction.CLICK_MINE,
            profile.decide(snapshot(clickable = listOf("我的"))).action
        )
    }

    // 验证“我的”页面进入打卡点。
    @Test
    fun opensCheckInPointFromMine() {
        assertEquals(
            MapUiAction.CLICK_CHECK_IN_POINT,
            profile.decide(snapshot(clickable = listOf("打卡点"))).action
        )
    }

    // 验证历史页先安全返回首页。
    @Test
    fun returnsHomeFromHistoryPage() {
        val decision = profile.decide(
            snapshot(
                texts = listOf("我的足迹记录", "已打卡了1256个地点"),
                clickable = listOf("首页")
            )
        )

        assertEquals(MapUiPage.FOOTPRINT, decision.page)
        assertEquals(MapUiAction.CLICK_HOME, decision.action)
    }

    // 验证已知提示先关闭。
    @Test
    fun dismissesKnownFootprintPrompt() {
        assertEquals(
            MapUiAction.DISMISS_PROMPT,
            profile.decide(
                snapshot(
                    texts = listOf("您有1个足迹点待点亮"),
                    clickable = listOf("知道了")
                )
            ).action
        )
    }

    // 验证打卡页执行立即打卡。
    @Test
    fun performsImmediateCheckIn() {
        assertEquals(
            MapUiAction.CLICK_CHECK_IN,
            profile.decide(
                snapshot(clickable = listOf("立即打卡"))
            ).action
        )
    }

    // 验证明确成功组合才进入成功终态。
    @Test
    fun recognizesExplicitSuccessEvidence() {
        val decision = profile.decide(
            snapshot(texts = listOf("打卡成功", "完成"))
        )

        assertEquals(MapUiPage.SUCCESS, decision.page)
        assertNull(decision.action)
    }

    // 验证当前打卡页的明确提示可识别为已经完成。
    @Test
    fun recognizesExplicitAlreadyCompletedEvidence() {
        assertEquals(
            MapUiPage.ALREADY_COMPLETED,
            profile.decide(
                snapshot(texts = listOf("当前地点今日已打卡"))
            ).page
        )
    }

    // 验证历史统计中的“已打卡”不会误判终态。
    @Test
    fun doesNotTreatHistoryStatisticsAsCompleted() {
        val decision = profile.decide(
            snapshot(
                texts = listOf("我的足迹记录", "2026年已打卡了1256个地点"),
                clickable = listOf("首页")
            )
        )

        assertEquals(MapUiPage.FOOTPRINT, decision.page)
        assertEquals(MapUiAction.CLICK_HOME, decision.action)
    }

    // 验证登录或验证码页面进入人工阻断。
    @Test
    fun blocksSecurityPages() {
        assertEquals(
            MapUiPage.BLOCKED,
            profile.decide(
                snapshot(texts = listOf("请登录后继续", "验证码"))
            ).page
        )
    }

    // 构造不依赖 Android 的百度页面快照。
    private fun snapshot(
        texts: List<String> = emptyList(),
        clickable: List<String> = emptyList()
    ): MapUiSnapshot {
        return MapUiSnapshot(
            packageName = profile.packageName,
            appVersion = profile.supportedAppVersion,
            nodes = texts.map { text ->
                MapUiNodeSnapshot(text = text)
            } + clickable.map { text ->
                MapUiNodeSnapshot(text = text, clickable = true)
            }
        )
    }
}
```

- [ ] **Step 2: 运行百度测试并确认 Profile 缺失**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.maps.BaiduUiProfileTest"
```

Expected: FAIL，提示 `Unresolved reference: BaiduUiProfile`。

- [ ] **Step 3: 实现百度决策优先级**

```kotlin
object BaiduUiProfile : ProviderUiProfile {
    override val packageName = "com.baidu.BaiduMap"
    override val supportedAppVersion = "21.18.50"
    override val profileVersion = "baidu_21_18_50_v1"

    // 按阻断、成功、弹窗、历史页、打卡页和入口页的顺序规划动作。
    override fun decide(snapshot: MapUiSnapshot): MapUiDecision {
        return when {
            snapshot.containsAnyLabelFragment(BLOCKED_MARKERS) ->
                decision(MapUiPage.BLOCKED, MapUiAction.BLOCK, "人工阻断")
            snapshot.hasLabel("打卡成功") &&
                    snapshot.hasAnyLabel(listOf("完成", "成功")) ->
                decision(MapUiPage.SUCCESS, null, "打卡成功")
            snapshot.containsLabelFragment("当前地点今日已打卡") ->
                decision(
                    MapUiPage.ALREADY_COMPLETED,
                    null,
                    "当前地点今日已打卡"
                )
            snapshot.hasClickableLabel("知道了") &&
                    snapshot.containsAnyLabelFragment(
                        KNOWN_PROMPT_MARKERS
                    ) ->
                decision(
                    MapUiPage.HOME,
                    MapUiAction.DISMISS_PROMPT,
                    "关闭足迹提示"
                )
            snapshot.hasLabel("我的足迹记录") &&
                    snapshot.hasClickableLabel("首页") ->
                decision(
                    MapUiPage.FOOTPRINT,
                    MapUiAction.CLICK_HOME,
                    "历史页返回首页"
                )
            snapshot.hasClickableLabel("立即打卡") ||
                    snapshot.hasClickableLabel("立刻打卡") ->
                decision(
                    MapUiPage.CHECK_IN,
                    MapUiAction.CLICK_CHECK_IN,
                    "执行立即打卡"
                )
            snapshot.hasClickableLabel("打卡点") ->
                decision(
                    MapUiPage.MINE,
                    MapUiAction.CLICK_CHECK_IN_POINT,
                    "进入打卡点"
                )
            snapshot.hasClickableLabel("我的") ->
                decision(
                    MapUiPage.HOME,
                    MapUiAction.CLICK_MINE,
                    "进入我的"
                )
            else ->
                decision(MapUiPage.UNKNOWN, MapUiAction.WAIT, "未知页面")
        }
    }

    // 创建包含页面、动作和证据的百度决策。
    private fun decision(
        page: MapUiPage,
        action: MapUiAction?,
        evidence: String
    ): MapUiDecision {
        return MapUiDecision(
            page = page,
            action = action,
            evidence = evidence
        )
    }

    private val BLOCKED_MARKERS = listOf(
        "请登录后继续",
        "验证码",
        "安全验证",
        "风险验证",
        "需要授权"
    )
    private val KNOWN_PROMPT_MARKERS = listOf(
        "足迹点待点亮"
    )
}
```

- [ ] **Step 4: 运行百度 Profile 测试**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.maps.BaiduUiProfileTest"
```

Expected: PASS。

---

### Task 3: R-083 高德 16.21.0.2010 Profile

**Files:**
- Create: `app/src/main/java/com/wu/nearbycheckin/platform/maps/AmapUiProfile.kt`
- Create: `app/src/test/java/com/wu/nearbycheckin/platform/maps/AmapUiProfileTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `MapUiSnapshot` 和 `MapUiDecision`。
- Produces: `AmapUiProfile.decide(snapshot: MapUiSnapshot): MapUiDecision`。

- [ ] **Step 1: 写入高德页面决策的失败测试**

```kotlin
class AmapUiProfileTest {

    private val profile = AmapUiProfile

    // 验证首页进入“我的”。
    @Test
    fun opensMineFromHome() {
        assertEquals(
            MapUiAction.CLICK_MINE,
            profile.decide(snapshot(clickable = listOf("我的"))).action
        )
    }

    // 验证当前位置页直接点击打卡并跳过足迹入口。
    @Test
    fun checksInDirectlyWhenCurrentLocationPageIsVisible() {
        val decision = profile.decide(
            snapshot(
                texts = listOf("当前位置：", "浦联佳苑"),
                clickable = listOf("打卡")
            )
        )

        assertEquals(MapUiPage.CHECK_IN, decision.page)
        assertEquals(MapUiAction.CLICK_CHECK_IN, decision.action)
    }

    // 验证只有未出现打卡按钮时才进入足迹。
    @Test
    fun opensFootprintOnlyWhenCheckInIsUnavailable() {
        val decision = profile.decide(
            snapshot(clickable = listOf("我的足迹"))
        )

        assertEquals(MapUiPage.MINE, decision.page)
        assertEquals(MapUiAction.CLICK_FOOTPRINT, decision.action)
    }

    // 验证明确成功证据进入成功终态。
    @Test
    fun recognizesExplicitSuccessEvidence() {
        assertEquals(
            MapUiPage.SUCCESS,
            profile.decide(
                snapshot(texts = listOf("打卡成功", "完成"))
            ).page
        )
    }

    // 验证当前地点明确提示已打卡时进入已完成终态。
    @Test
    fun recognizesExplicitAlreadyCompletedEvidence() {
        assertEquals(
            MapUiPage.ALREADY_COMPLETED,
            profile.decide(
                snapshot(texts = listOf("当前地点已打卡"))
            ).page
        )
    }

    // 验证账号安全页面进入人工阻断。
    @Test
    fun blocksSecurityPages() {
        assertEquals(
            MapUiPage.BLOCKED,
            profile.decide(
                snapshot(texts = listOf("登录", "安全验证"))
            ).page
        )
    }

    // 构造不依赖 Android 的高德页面快照。
    private fun snapshot(
        texts: List<String> = emptyList(),
        clickable: List<String> = emptyList()
    ): MapUiSnapshot {
        return MapUiSnapshot(
            packageName = profile.packageName,
            appVersion = profile.supportedAppVersion,
            nodes = texts.map { text ->
                MapUiNodeSnapshot(text = text)
            } + clickable.map { text ->
                MapUiNodeSnapshot(text = text, clickable = true)
            }
        )
    }
}
```

- [ ] **Step 2: 运行高德测试并确认 Profile 缺失**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.maps.AmapUiProfileTest"
```

Expected: FAIL，提示 `Unresolved reference: AmapUiProfile`。

- [ ] **Step 3: 实现高德决策优先级**

```kotlin
object AmapUiProfile : ProviderUiProfile {
    override val packageName = "com.autonavi.minimap"
    override val supportedAppVersion = "16.21.0.2010"
    override val profileVersion = "amap_16_21_0_2010_v1"

    // 优先识别阻断和终态，再识别当前位置打卡、足迹和首页入口。
    override fun decide(snapshot: MapUiSnapshot): MapUiDecision {
        return when {
            snapshot.containsAnyLabelFragment(BLOCKED_MARKERS) ->
                decision(MapUiPage.BLOCKED, MapUiAction.BLOCK, "人工阻断")
            snapshot.hasLabel("打卡成功") &&
                    snapshot.hasAnyLabel(listOf("完成", "成功")) ->
                decision(MapUiPage.SUCCESS, null, "打卡成功")
            snapshot.containsLabelFragment("当前地点已打卡") ->
                decision(
                    MapUiPage.ALREADY_COMPLETED,
                    null,
                    "当前地点已打卡"
                )
            snapshot.containsLabelFragment("当前位置") &&
                    snapshot.hasClickableLabel("打卡") ->
                decision(
                    MapUiPage.CHECK_IN,
                    MapUiAction.CLICK_CHECK_IN,
                    "当前位置直接打卡"
                )
            snapshot.hasAnyClickableLabel(listOf("足迹", "我的足迹")) ->
                decision(
                    MapUiPage.MINE,
                    MapUiAction.CLICK_FOOTPRINT,
                    "进入足迹"
                )
            snapshot.hasClickableLabel("我的") ->
                decision(
                    MapUiPage.HOME,
                    MapUiAction.CLICK_MINE,
                    "进入我的"
                )
            else ->
                decision(MapUiPage.UNKNOWN, MapUiAction.WAIT, "未知页面")
        }
    }

    // 创建包含页面、动作和证据的高德决策。
    private fun decision(
        page: MapUiPage,
        action: MapUiAction?,
        evidence: String
    ): MapUiDecision {
        return MapUiDecision(
            page = page,
            action = action,
            evidence = evidence
        )
    }

    private val BLOCKED_MARKERS = listOf(
        "请登录后继续",
        "验证码",
        "安全验证",
        "风险验证",
        "需要授权"
    )
}
```

- [ ] **Step 4: 运行高德 Profile 测试**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.maps.AmapUiProfileTest"
```

Expected: PASS。

---

### Task 4: Android Adapter 快照与动作桥接

**Files:**
- Create: `app/src/main/java/com/wu/nearbycheckin/platform/maps/MapUiSnapshotCollector.kt`
- Create: `app/src/main/java/com/wu/nearbycheckin/platform/maps/MapUiActionLabels.kt`
- Create: `app/src/test/java/com/wu/nearbycheckin/platform/maps/MapUiActionLabelsTest.kt`
- Modify: `app/src/main/java/com/wu/nearbycheckin/platform/maps/BaiduMapAdapter.kt:75-135`
- Modify: `app/src/main/java/com/wu/nearbycheckin/platform/maps/AmapMapAdapter.kt:76-136`

**Interfaces:**
- Consumes: `AccessibilityNodeInfo`、`MapUiAction`、地图包版本。
- Produces: `captureSnapshot(rootNode, appVersion): MapUiSnapshot` 和 `execute(rootNode, action): AccessibilityClickResult`。

- [ ] **Step 1: 写入动作标签映射的失败测试**

```kotlin
class MapUiActionLabelsTest {

    // 验证百度动作映射包含当前版本实际标签。
    @Test
    fun mapsBaiduActionsToVerifiedLabels() {
        assertEquals(
            listOf("首页"),
            MapUiActionLabels.forProvider(
                MapProvider.BAIDU,
                MapUiAction.CLICK_HOME
            )
        )
        assertEquals(
            listOf("立即打卡", "立刻打卡"),
            MapUiActionLabels.forProvider(
                MapProvider.BAIDU,
                MapUiAction.CLICK_CHECK_IN
            )
        )
    }

    // 验证高德动作映射允许直接打卡。
    @Test
    fun mapsAmapCheckInActionToVerifiedLabels() {
        assertEquals(
            listOf("打卡", "点击打卡"),
            MapUiActionLabels.forProvider(
                MapProvider.AMAP,
                MapUiAction.CLICK_CHECK_IN
            )
        )
    }
}
```

- [ ] **Step 2: 运行测试并确认映射缺失**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.maps.MapUiActionLabelsTest"
```

Expected: FAIL，提示 `Unresolved reference: MapUiActionLabels`。

- [ ] **Step 3: 实现动作标签映射**

```kotlin
object MapUiActionLabels {

    // 返回指定地图和动作允许点击的当前版本标签。
    fun forProvider(
        provider: MapProvider,
        action: MapUiAction
    ): List<String> {
        return when (provider to action) {
            MapProvider.BAIDU to MapUiAction.CLICK_MINE ->
                listOf("我的")
            MapProvider.BAIDU to MapUiAction.CLICK_CHECK_IN_POINT ->
                listOf("打卡点")
            MapProvider.BAIDU to MapUiAction.CLICK_CHECK_IN ->
                listOf("立即打卡", "立刻打卡")
            MapProvider.BAIDU to MapUiAction.CLICK_HOME ->
                listOf("首页")
            MapProvider.BAIDU to MapUiAction.DISMISS_PROMPT ->
                listOf("知道了")
            MapProvider.AMAP to MapUiAction.CLICK_MINE ->
                listOf("我的")
            MapProvider.AMAP to MapUiAction.CLICK_FOOTPRINT ->
                listOf("足迹", "我的足迹")
            MapProvider.AMAP to MapUiAction.CLICK_CHECK_IN ->
                listOf("打卡", "点击打卡")
            else -> emptyList()
        }
    }
}
```

- [ ] **Step 4: 实现节点快照采集**

```kotlin
object MapUiSnapshotCollector {

    // 把 Android 节点树转换为不含 Android 类型的只读快照。
    fun collect(
        rootNode: AccessibilityNodeInfo?,
        packageName: String,
        appVersion: String
    ): MapUiSnapshot {
        val nodes = mutableListOf<MapUiNodeSnapshot>()
        collectNodes(rootNode, depth = 0, result = nodes)
        return MapUiSnapshot(
            packageName = packageName,
            appVersion = appVersion,
            nodes = nodes
        )
    }

    // 递归采集页面节点并限制遍历深度和总量。
    private fun collectNodes(
        node: AccessibilityNodeInfo?,
        depth: Int,
        result: MutableList<MapUiNodeSnapshot>
    ) {
        if (
            node == null ||
            depth > MAX_DEPTH ||
            result.size >= MAX_NODE_COUNT
        ) {
            return
        }
        result += MapUiNodeSnapshot(
            text = node.text?.toString().orEmpty(),
            description =
                node.contentDescription?.toString().orEmpty(),
            viewId = node.viewIdResourceName.orEmpty(),
            clickable = node.hasClickableAncestor()
        )
        for (childIndex in 0 until node.childCount) {
            collectNodes(
                node = node.getChild(childIndex),
                depth = depth + 1,
                result = result
            )
        }
    }

    // 判断当前节点或有限层级父节点是否可执行点击。
    private fun AccessibilityNodeInfo.hasClickableAncestor(): Boolean {
        var current: AccessibilityNodeInfo? = this
        var level = 0
        while (current != null && level <= MAX_PARENT_DEPTH) {
            if (current.isEnabled && current.isClickable) {
                return true
            }
            current = current.parent
            level += 1
        }
        return false
    }

    private const val MAX_DEPTH = 12
    private const val MAX_NODE_COUNT = 300
    private const val MAX_PARENT_DEPTH = 6
}
```

- [ ] **Step 5: 为两个 Adapter 增加统一入口**

```kotlin
// 采集当前百度窗口的纯 Kotlin 页面快照。
fun captureSnapshot(
    rootNode: AccessibilityNodeInfo?,
    appVersion: String
): MapUiSnapshot {
    return MapUiSnapshotCollector.collect(
        rootNode = rootNode,
        packageName = BaiduUiProfile.packageName,
        appVersion = appVersion
    )
}

// 执行百度 Profile 返回的单个动作。
fun execute(
    rootNode: AccessibilityNodeInfo?,
    action: MapUiAction
): AccessibilityClickResult {
    return AccessibilityNodeActions.clickFirstMatchingLabel(
        rootNode = rootNode,
        labels = MapUiActionLabels.forProvider(
            MapProvider.BAIDU,
            action
        )
    )
}
```

高德 Adapter 使用相同签名，provider 改为 `MapProvider.AMAP`。保留现有 `inspect()` 供诊断使用，删除只被旧固定步骤调用的公开点击函数。

- [ ] **Step 6: 运行动作映射测试和全量地图规则测试**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.maps.*"
```

Expected: PASS。

---

### Task 5: Worker 自适应页面循环与终态回写

**Files:**
- Create: `app/src/main/java/com/wu/nearbycheckin/platform/accessibility/MapUiExecutionPolicy.kt`
- Create: `app/src/test/java/com/wu/nearbycheckin/platform/accessibility/MapUiExecutionPolicyTest.kt`
- Modify: `app/src/main/java/com/wu/nearbycheckin/platform/accessibility/DualMapCommandWorker.kt:87-275`
- Modify: `app/src/main/java/com/wu/nearbycheckin/platform/accessibility/MapOperationResultProbe.kt:102-215`

**Interfaces:**
- Consumes: `MapUiDecision`、页面指纹、动作次数、重复页面次数。
- Produces: `MapUiExecutionOutcome` 和最终 `MapOperationResult`。

- [ ] **Step 1: 写入循环保护和终态映射的失败测试**

```kotlin
class MapUiExecutionPolicyTest {

    private val policy = MapUiExecutionPolicy(
        maxActionCount = 12,
        maxRepeatedUnknownCount = 3
    )

    // 验证明确成功映射为成功结果。
    @Test
    fun mapsSuccessPageToSuccessOutcome() {
        assertEquals(
            MapUiExecutionOutcome.SUCCESS,
            policy.outcomeFor(
                page = MapUiPage.SUCCESS,
                actionCount = 2,
                repeatedUnknownCount = 0
            )
        )
    }

    // 验证阻断页面要求人工处理。
    @Test
    fun mapsBlockedPageToBlockedOutcome() {
        val outcome =
            policy.outcomeFor(
                page = MapUiPage.BLOCKED,
                actionCount = 1,
                repeatedUnknownCount = 0
            )

        assertEquals(
            MapUiExecutionOutcome.BLOCKED,
            outcome
        )
        assertEquals(
            MapOperationStatus.BLOCKED,
            outcome.toOperationStatus()
        )
    }

    // 验证未知页面重复三次后明确失败。
    @Test
    fun stopsAfterRepeatedUnknownPages() {
        assertEquals(
            MapUiExecutionOutcome.UNSUPPORTED,
            policy.outcomeFor(
                page = MapUiPage.UNKNOWN,
                actionCount = 2,
                repeatedUnknownCount = 3
            )
        )
    }

    // 验证动作次数达到上限后超时。
    @Test
    fun stopsAfterMaximumActions() {
        assertEquals(
            MapUiExecutionOutcome.TIMEOUT,
            policy.outcomeFor(
                page = MapUiPage.HOME,
                actionCount = 12,
                repeatedUnknownCount = 0
            )
        )
    }
}
```

- [ ] **Step 2: 运行测试并确认执行策略缺失**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.accessibility.MapUiExecutionPolicyTest"
```

Expected: FAIL，提示 `Unresolved reference: MapUiExecutionPolicy`。

- [ ] **Step 3: 实现最小执行策略**

```kotlin
enum class MapUiExecutionOutcome {
    CONTINUE,
    SUCCESS,
    ALREADY_COMPLETED,
    BLOCKED,
    UNSUPPORTED,
    TIMEOUT
}

class MapUiExecutionPolicy(
    private val maxActionCount: Int = 12,
    private val maxRepeatedUnknownCount: Int = 3
) {

    // 根据页面和循环计数决定继续执行或结束命令。
    fun outcomeFor(
        page: MapUiPage,
        actionCount: Int,
        repeatedUnknownCount: Int
    ): MapUiExecutionOutcome {
        return when {
            page == MapUiPage.SUCCESS ->
                MapUiExecutionOutcome.SUCCESS
            page == MapUiPage.ALREADY_COMPLETED ->
                MapUiExecutionOutcome.ALREADY_COMPLETED
            page == MapUiPage.BLOCKED ->
                MapUiExecutionOutcome.BLOCKED
            repeatedUnknownCount >= maxRepeatedUnknownCount ->
                MapUiExecutionOutcome.UNSUPPORTED
            actionCount >= maxActionCount ->
                MapUiExecutionOutcome.TIMEOUT
            else ->
                MapUiExecutionOutcome.CONTINUE
        }
    }
}

// 把页面循环终态转换成领域地图结果。
fun MapUiExecutionOutcome.toOperationStatus(): MapOperationStatus {
    return when (this) {
        MapUiExecutionOutcome.SUCCESS ->
            MapOperationStatus.SUCCESS
        MapUiExecutionOutcome.ALREADY_COMPLETED ->
            MapOperationStatus.ALREADY_COMPLETED
        MapUiExecutionOutcome.BLOCKED ->
            MapOperationStatus.BLOCKED
        MapUiExecutionOutcome.UNSUPPORTED ->
            MapOperationStatus.UNSUPPORTED
        MapUiExecutionOutcome.TIMEOUT ->
            MapOperationStatus.TIMEOUT
        MapUiExecutionOutcome.CONTINUE ->
            MapOperationStatus.UNKNOWN
    }
}
```

- [ ] **Step 4: 把固定步骤替换为页面循环**

`DualMapCommandWorker` 使用以下循环状态替换 `stepIndex`：

```kotlin
private fun executeNextAction(
    command: MapCommand,
    startedAtMillis: Long,
    actionCount: Int = 0,
    lastFingerprint: String = "",
    repeatedUnknownCount: Int = 0,
    windowWaitAttempt: Int = 0
)
```

每轮必须：

1. 查找目标窗口，不存在时调用新的 `waitForTargetWindowBeforeAction()`，原样保留后台等待语义和退避策略。
2. 获取当前地图版本并调用对应 Adapter 的 `captureSnapshot()`。
3. 调用 `BaiduUiProfile` 或 `AmapUiProfile`。
4. 把 provider、应用版本、profileVersion、page、action 和 evidence 组合进现有 `evidenceText`，不新增 Repository 字段或 schema 版本。
5. 先使用 `MapUiExecutionPolicy` 处理终态。
6. `WAIT` 时延迟重新识别；可点击动作成功后将 `actionCount + 1` 并重新识别页面。
7. 页面指纹未变化且页面为 `UNKNOWN` 时累计 `repeatedUnknownCount`；页面变化后清零。

Worker 构造参数中的 `service` 改为私有属性，并使用以下辅助函数读取版本：

```kotlin
// 在地图位于后台时保留完整页面循环状态并等待窗口恢复。
private fun waitForTargetWindowBeforeAction(
    command: MapCommand,
    startedAtMillis: Long,
    actionCount: Int,
    lastFingerprint: String,
    repeatedUnknownCount: Int,
    windowWaitAttempt: Int
) {
    reportBackgroundWaitingIfNeeded(windowWaitAttempt)
    mainHandler.postDelayed(
        {
            executeNextAction(
                command = command,
                startedAtMillis = startedAtMillis,
                actionCount = actionCount,
                lastFingerprint = lastFingerprint,
                repeatedUnknownCount = repeatedUnknownCount,
                windowWaitAttempt = windowWaitAttempt + 1
            )
        },
        windowRecoveryPolicy.nextWaitDelayMillis(windowWaitAttempt)
    )
}

// 读取当前安装地图应用的版本名并在读取失败时返回空字符串。
private fun appVersion(packageName: String): String {
    return runCatching {
        service.packageManager
            .getPackageInfo(packageName, 0)
            .versionName
            .orEmpty()
    }.getOrDefault("")
}

// 生成可持久化的 Profile 页面与动作证据。
private fun buildProfileEvidence(
    provider: MapProvider,
    snapshot: MapUiSnapshot,
    profile: ProviderUiProfile,
    decision: MapUiDecision
): String {
    return listOf(
        "provider=${provider.name}",
        "package=${snapshot.packageName}",
        "appVersion=${snapshot.appVersion}",
        "profileVersion=${profile.profileVersion}",
        "page=${decision.page.name}",
        "action=${decision.action?.name.orEmpty()}",
        "evidence=${decision.evidence}"
    ).joinToString(separator = "；")
}
```

- [ ] **Step 5: 收紧旧结果探测**

`MapOperationResultProbe` 不再把普通“成功”或任意“已打卡”作为跨页面终态：

```kotlin
val SUCCESS_TEXT_MARKERS = listOf(
    "打卡成功",
    "签到成功"
)
val ALREADY_COMPLETED_TEXT_MARKERS = listOf(
    "今日已打卡",
    "当前地点已打卡",
    "今天已签到"
)
```

Worker 的主要终态来源改为 provider Profile；旧 Probe 仅保留为兼容证据采集，不得覆盖 Profile 的页面判断。

- [ ] **Step 6: 运行执行策略测试、全量单元测试和 Debug 构建**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.accessibility.MapUiExecutionPolicyTest"
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

Expected: 三条命令均显示 `BUILD SUCCESSFUL`。

---

### Task 6: 小米 14 双端真机验收

**Files:**
- Verify only: `app/build/outputs/apk/debug/app-debug.apk`
- Inspect: Android `dumpsys activity activities`、`dumpsys accessibility` 和 Logcat。

**Interfaces:**
- Consumes: Task 1-5 的 Debug APK。
- Produces: R-082、R-083 和双端事务闭环的真机证据。

- [ ] **Step 1: 安装已验证构建**

Run:

```powershell
.\gradlew.bat :app:installDebug
```

Expected: `Installed on 1 device`。

- [ ] **Step 2: 验证百度历史页恢复路径**

1. 手动把百度停留在“我的足迹记录”页。
2. 启动一条百度命令。
3. 检查日志依次出现 `FOOTPRINT/CLICK_HOME`、`HOME/CLICK_MINE`、`MINE/CLICK_CHECK_IN_POINT`、`CHECK_IN/CLICK_CHECK_IN`。
4. 确认历史统计中的“已打卡”没有提前结束命令。

- [ ] **Step 3: 验证百度提示弹窗**

1. 返回百度首页并触发“足迹点待点亮”提示。
2. 确认日志出现 `DISMISS_PROMPT`。
3. 确认弹窗关闭后继续页面识别，不重复创建地图命令。

- [ ] **Step 4: 验证高德直接打卡路径**

1. 从高德首页启动命令。
2. 确认点击“我的”后，页面包含“当前位置”和可点击“打卡”时直接执行 `CLICK_CHECK_IN`。
3. 确认没有出现旧流程的“未找到足迹/我的足迹”失败。

- [ ] **Step 5: 验证整笔事务完成**

检查 Repository 运行日志和主页状态：

- 百度结果为 `SUCCESS` 或明确的 `ALREADY_COMPLETED`。
- 高德结果为 `SUCCESS` 或明确的 `ALREADY_COMPLETED`。
- 事务进入 `COMPLETED`。
- 记录包含 package、appVersion、profileVersion、page、action、commandId、transactionId 和证据。

- [ ] **Step 6: 验证后台恢复不重复成功端**

1. 在地图命令执行中把双地图切到后台。
2. 确认 Worker 保留当前命令且不启动单独地图任务。
3. 恢复双窗口后从当前页面重新识别。
4. 已经成功的地图端不得生成第二条成功操作。

- [ ] **Step 7: 检查工作区并等待用户验收**

Run:

```powershell
git diff --check
git status --short
```

Expected: 无空白错误；只包含本计划列出的代码、测试和文档改动，且未提交。
