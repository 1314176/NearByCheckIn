# R-082/R-083 双地图 UI Profile 设计

## 1. 范围

本批次同时完成：

- R-082：百度地图 `21.18.50` UI Profile。
- R-083：高德地图 `16.21.0.2010` UI Profile。

目标是在小米 14 的系统分屏中，让两张地图根据当前真实页面选择下一动作，替换 `DualMapCommandWorker` 中写死的固定三步流程。本批次不修改候选发现、事务排序、重试次数、永久去重和分屏创建规则。

## 2. 当前问题

现有 Worker 使用固定步骤索引：

- 百度：我的 → 打卡点 → 立即打卡。
- 高德：我的 → 足迹 → 打卡。

真实地图页面并不保证每次从首页开始：

- 百度可能停留在“我的足迹记录”等历史页，历史页没有“我的”入口。
- 百度首页可能出现“足迹点待点亮”提示，需要先关闭才能继续。
- 高德当前版本点击“我的”后可能直接进入包含“当前位置”和“打卡”的页面，不再出现独立“足迹”入口。
- 现有结果探测只要扫描到宽泛的“已打卡”文本就可能误判历史页为终态。

因此，只增加标签或调整固定步骤顺序不能形成稳定闭环。

## 3. 架构

### 3.1 纯 Kotlin 页面决策

新增不依赖 Android 的 UI Profile 决策模型：

```text
MapUiSnapshot
    ↓
ProviderUiProfile.classify(snapshot)
    ↓
MapUiPage + MapUiAction
```

核心模型：

- `MapUiSnapshot`：当前窗口中的规范化文本、内容描述、资源 ID 和可点击性，不保存 `AccessibilityNodeInfo`。
- `MapUiPage`：`HOME`、`MINE`、`FOOTPRINT`、`CHECK_IN`、`SUCCESS`、`ALREADY_COMPLETED`、`BLOCKED`、`UNKNOWN`。
- `MapUiAction`：`CLICK_MINE`、`CLICK_FOOTPRINT`、`CLICK_CHECK_IN_POINT`、`CLICK_CHECK_IN`、`CLICK_HOME`、`DISMISS_PROMPT`、`WAIT`、`BLOCK`。
- `ProviderUiProfile`：按地图包名和版本号识别页面并选择下一动作。

分类与动作规划必须能够使用普通 JVM 单元测试覆盖，不把 `AccessibilityNodeInfo` 引入规则测试。

### 3.2 Adapter 边界

`BaiduMapAdapter` 和 `AmapMapAdapter` 只负责：

1. 把节点树转换为 `MapUiSnapshot`。
2. 执行 Profile 返回的单个 `MapUiAction`。
3. 返回本次动作的节点证据和失败原因。

Adapter 不决定重试次数、不推进事务状态、不访问 Repository。

### 3.3 Worker 编排

`DualMapCommandWorker` 不再根据 `stepIndex` 写死地图流程，而是循环执行：

1. 获取目标地图根节点。
2. 生成 `MapUiSnapshot`。
3. 由当前版本 Profile 返回页面与下一动作。
4. Adapter 执行一个动作。
5. 页面变化后重新识别，直到成功、已打卡、阻断、明确失败或超时。

Worker 必须保留现有命令串行、后台等待和幂等回写行为。每条命令设置最大动作次数和总超时，防止 `UNKNOWN` 页面或页面未变化导致无限循环。

## 4. 百度 21.18.50 Profile

页面与动作规则：

- 首页存在“我的”时，执行 `CLICK_MINE`。
- “我的”页面存在“打卡点”时，执行 `CLICK_CHECK_IN_POINT`。
- “我的足迹记录”等历史页存在“首页”时，执行 `CLICK_HOME`，重新从首页规划。
- 出现“足迹点待点亮”等已知提示且存在“知道了”时，执行 `DISMISS_PROMPT`。
- 打卡页存在“立即打卡”或“立刻打卡”时，执行 `CLICK_CHECK_IN`。
- 只有出现明确的“打卡成功”页面组合证据时返回 `SUCCESS`。
- 只有当前打卡页明确显示目标已经完成时返回 `ALREADY_COMPLETED`；历史统计中的普通“已打卡”文本不能作为终态。
- 登录、验证码、风险验证或权限阻断返回 `BLOCKED`，不自动绕过。

## 5. 高德 16.21.0.2010 Profile

页面与动作规则：

- 首页存在“我的”时，执行 `CLICK_MINE`。
- 页面同时出现“当前位置”和可点击“打卡”时，直接执行 `CLICK_CHECK_IN`，跳过不存在的足迹步骤。
- 只有页面确实存在“足迹”或“我的足迹”入口且尚未出现可点击“打卡”时，执行 `CLICK_FOOTPRINT`。
- 打卡完成页出现明确成功组合证据时返回 `SUCCESS`。
- 当前地点明确显示已经打卡时返回 `ALREADY_COMPLETED`。
- 登录、验证码、风险验证或权限阻断返回 `BLOCKED`。

## 6. 版本与未知页面处理

- Profile 标识中保存地图包名、已验证版本名和规则版本。
- 当前安装版本与已验证版本不一致时仍可进行只读页面识别，但必须记录版本不匹配诊断。
- 页面无法识别时返回 `UNKNOWN` 和完整节点摘要，不执行固定坐标或猜测点击。
- 连续多次得到相同 `UNKNOWN` 页面后，以明确失败结果结束命令，保留证据供更新 Profile。

## 7. 结果与证据

每次动作和终态结果至少保存：

- 地图包名和应用版本。
- Profile 规则版本。
- 识别出的 `MapUiPage`。
- 执行的 `MapUiAction`。
- 命中的文本、内容描述或资源 ID。
- 失败时的候选节点摘要。
- `transactionId`、`commandId` 和 attempt；`sessionId` 通过对应的 `CheckInTransaction` 关联获得，避免为本批次额外扩展命令与结果模型。

终态判断优先由当前地图 Profile 给出，避免 `MapOperationResultProbe` 使用跨页面宽泛关键词产生误判。

## 8. 测试与验收

### 8.1 JVM 测试

- 百度：首页、我的、历史页、提示弹窗、打卡页、成功页、阻断页和未知页。
- 高德：首页、直接打卡页、足迹入口页、成功页、阻断页和未知页。
- 历史页出现“已打卡”时不得判为成功或已完成。
- 相同页面连续出现时受最大动作次数限制。
- 两张地图的规则互不识别对方标签。

### 8.2 真机测试

- 在百度首页启动命令，完成百度打卡并保存证据。
- 在百度历史页启动命令，安全返回首页后完成流程。
- 在高德首页启动命令，点击“我的”后根据实际页面直接打卡或进入足迹。
- 百度成功后生成高德命令，高德成功后整笔事务进入 `COMPLETED`。
- 双窗口切到后台再恢复后，从当前页面重新识别，不重复已完成地图端。

### 8.3 完成门槛

- `testDebugUnitTest` 和 `assembleDebug` 通过。
- 小米 14 上 R-082、R-083 分别完成一次真实流程。
- 事务记录包含两端步骤、终态和证据。
- 未观察到固定坐标点击、跨地图规则混用或历史页误判成功。
