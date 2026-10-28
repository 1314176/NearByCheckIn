# NearbyCheckIn

NearbyCheckIn 是一个 Android/Kotlin 地图自动打卡应用，通过前台定位服务、地图应用自身的候选列表和无障碍操作，持续处理附近未完成的打卡地点。当前采用 **V5.4 源码对齐、百度优先开发基准**：优先完成百度地图连续打卡真机闭环，高德地图保留代码、后置开发。

## 开发基准与当前状态

本说明按以下两份 2026-10-28 基准文件同步；原文件位于本地仓库上一级目录，本次仅更新 README，不将原文件复制进仓库：

- `NearbyCheckIn_开发基准文档_V5.4_源码对齐百度优先版_20261028.docx`
- `NearbyCheckIn_开发进度表_V5.4_源码对齐百度优先版_20261028.xlsx`

| 项目 | 当前基准 |
| --- | --- |
| 规划文档版本 | V5.4，与 APK 版本独立 |
| 应用版本 | v3.0.22，versionCode 30022 |
| 源码审计基线 | `e617536`：完善双地图结果确认与超范围恢复并修复百度面板展开 |
| 对应源码压缩包 | `NearbyCheckIn_202610281058.zip`；旧压缩包不再作为开发或回滚基线 |
| 目标设备 | 小米 14 / HyperOS 分屏场景 |
| 数据快照 | `AutomationSnapshot` schema v7 |
| 开发优先级 | 百度 P0；百度 MVP 通过后再恢复高德专项开发 |
| 当前下一开发任务 | V5-018：集中配置 `BAIDU_ONLY` 运行模式 |

**“基本完成”表示源码已有实现，不等于真机验收通过。** 当前百度候选读取、选择、结果验证、永久去重、额度识别和超范围恢复均已有基础，但百度 MVP Gate 尚未通过。

特别注意：`BAIDU_ONLY` **尚未实现**，当前无障碍服务仍启动百度、高德两个 Provider Worker，静止 heartbeat 仍刷新两端；“高德后置”目前是开发策略，不代表运行时已经隔离高德。

V5.4 审计记录为 81 项任务，其中已完成/基本完成 56 项、待开发/待真机 9 项、冻结后置 6 项，其余为部分完成；平均完成度约 79.5%，不是整体验收通过率。

## 当前架构

```text
Compose UI / MainViewModel
    ↓ UseCase / Repository Flow
CheckInTransactionRepository（SSOT，schema v7）
    ↓
ProviderAutomationCoordinator
    ├─ ProviderStateMachine
    ├─ ProviderMovementPolicy / ProviderRefreshSchedulePolicy
    ├─ ProviderPlaceCandidate / ProviderCheckInTask / ProviderPlaceKey
    └─ providerCompletedKeys / providerRuntime
    ↓
MapAutomationPluginRegistry
    ├─ BaiduCheckInPlugin（当前 P0 主线）
    └─ AmapCheckInPlugin（保留、后置）
    ↓
ProviderAutomationWorker / PluginCommandExecutor
    ↓
DualMapAccessibilityService / Accessibility Window

CruiseDiscoveryController
    └─ LocationSnapshot + heartbeat → ProviderAutomationCoordinator
```

- `:core:common`、`:core:model`、`:core:domain` 为纯 Kotlin 模块，不依赖 Android UI 或无障碍节点类型；Android 界面、服务、平台适配和数据实现位于 `:app`。
- 地图 App UI 候选列表是实际打卡候选来源。百度 SDK 周边 POI 仅用于诊断，`recordPoiDiagnostics()` 不创建 Provider 任务。
- Provider 候选、任务、运行状态和永久完成键统一进入 `CheckInTransactionRepository`，由现有 SharedPreferences 快照实现持久化。
- 旧 `CheckInCoordinator`、`DualMapStateMachine`、`DualMapCommandWorker` 等兼容路径仍保留，新旧内核尚未完全收敛；不能将旧双端事务模型描述为当前唯一执行链路。
- 当前两端 Worker 共用手势执行设施，但最终全局 UI 命令串行、公平轮转及双端端到端 Gate 仍未完成。

## 功能概览

### 百度连续打卡主线

- 通过“我的 → 打卡点/足迹 → 当前打卡入口 → 候选列表 → 未完成地点 → 结果确认 → 下一个”处理地点。
- 新版界面使用“打卡当前位置”对应的“立即打卡”入口，避免误入模板或相机入口。
- 统计面板折叠、真实入口未露出时，使用 `EXPAND_CHECK_IN_PANEL` 安全展开，并在无进展时有限重试、回退重进。
- 读取可见候选的名称、地址、距离、完成标记和节点指纹，绑定具体候选执行，列表滚动有边界保护。
- UI 已完成标记与 `ProviderPlaceKey` / `providerCompletedKeys` 共同去重；重启后的去重仍需纳入真机 Gate。
- 最终成功以打卡数量增加等确定结果证据为核心；仅点击返回 `true` 或仅出现成功页，不直接写入永久成功。
- 已有每日额度耗尽识别、持久化阻断和恢复策略；额度/跨日恢复仍需真机验收，不绕过地图平台限制。
- 已有超范围恢复：撤销该 Provider 的旧位置任务、退出旧列表、等待新定位，再读取候选，避免重复点击过期地点。

### 移动、静止与结果处理

- `LocationSnapshot` 配合移动分类和列表 stale 策略刷新候选；已开始的原子操作不因位置变化随意中断。
- 无新定位回调时，通过 heartbeat 持续检查候选；定位不可用、无新候选或页面无进展时按策略等待或退避。
- 当前有效速度不高于 10 km/h 时，单 Provider 新候选提交最小间隔为 5 秒；高于 10 km/h 时为 2 秒。界面导航、仲裁和结果确认可能延长实际间隔，这不是打卡成功率承诺。
- 高德已有独立插件、地点数量增量验证及“收下”结果页关闭策略，继续保留编译和回归能力；连续成功、不卡住的最终真机 Gate 尚未完成。

### 界面、诊断与日志

- Compose 单向数据流，主界面通过 `MainViewModel`、用例和 Repository Flow 观察状态。
- 支持今日/全部记录、运行日志、双地图窗口与节点摘要诊断，以及可拖动、隐藏和从通知恢复的微光雷达悬浮窗。
- 主页底部显示应用版本和作者；仅同步文档不递增 APK 版本。
- 日志界面展示最近 300 条；完整日志按 25 MiB 分段追加保存至应用私有目录 `files/automation_logs/`，不按条数或时长循环覆盖旧分段，实际保留仍受存储空间、手动清空和卸载等影响。
- 卸载或清空应用数据前，应先导出日志；本地备份目录为 `log/`，压缩包命名为 `log_年月日时分.zip`，不提交 Git，也不上传 Release。

## 双地图能力边界

- Android 12L 及以上的自动分屏依赖系统接受前台 `FLAG_ACTIVITY_LAUNCH_ADJACENT` 请求；该请求仍由系统和厂商窗口策略决定，应用不能绕过系统限制。
- Android 12L 以下仅在无障碍服务公开系统动作列表包含 `GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN` 时请求旧版分屏，不再盲目调用已被系统移除的动作。
- 产品目标仍是百度、高德同时驻留分屏，上下顺序不作为业务条件；百度优先模式不等于删除高德或取消双地图窗口目标。
- 应用不能强行维持系统无障碍授权，也不能在地图窗口不可交互时凭空点击；窗口丢失、锁屏和服务重连需要诊断、恢复或明确等待。
- 自动操作依赖地图应用真实 UI、账号状态和节点可访问性，地图升级后可能需要调整语义 Profile；不依赖固定屏幕坐标盲点。
- 第三方平台成功必须有可解释的 `MapOperationEvidence`，不能以点击成功、结果卡出现或旧历史记录代替新增地点证据。

## 后续开发顺序

以 V5.4 为准，不因旧文档中的计划提前开展大规模重构或高德专项开发：

1. **V5-018**：集中引入运行模式，默认 `BAIDU_ONLY`；只启动百度 Worker、只对百度执行自动 heartbeat，高德 Plugin/Registry/代码保留。
2. **V5-019**：重新执行 Build/Test Gate，单元测试不少于 255 项且无失败、无错误。
3. **V5-049**：执行小米 14 百度 MVP 真机 Gate；按证据定点修复 **V5-040～048** 的入口、候选、滚动、结果、额度及超范围问题。
4. **V5-070～076**：补齐移动/静止持续运行，重点完成 V5-075 步行与 V5-076 静止验收。
5. **V5-083～089**：完善百度队列、当前任务、完成计数和阻断原因在 UI、悬浮窗、诊断中的一致展示。
6. **BAIDU MVP PASS**：冻结百度功能主线，仅做问题修复，再恢复 **V5-050～059** 高德专项开发。
7. **V5-061～069**：完成最终双 Provider UI 原子操作串行、公平调度和双地图端到端 Gate。
8. **V5-090～099**：长时稳定、数据升级、旧内核退役、核心类职责拆分、干净归档及正式 V5 基线。

### 百度 MVP 真机验收门槛

以下为待完成的验收要求，不是已通过结果：

| Gate | 验收要求 |
| --- | --- |
| B-01 启动/权限 | 定位、无障碍、悬浮权限正常，百度窗口可读取 |
| B-02 静止连续 | 同一位置连续完成至少 5 个未完成候选，无重复 |
| B-03 静止耐久 | 静止 10 分钟仍有 heartbeat，无新候选时退避、不死循环 |
| B-04 移动连续 | 步行 15～30 分钟持续处理，旧列表 stale 后不误点过期地点 |
| B-05 永久去重 | 重启 App/Service 后已完成地点不再次点击 |
| B-06 结果证据 | 每个 COMPLETED 均有明确、可解释的结果证据 |
| B-07 超范围恢复 | 撤销旧队列，等待新定位并恢复，不重复旧候选 |
| B-08 每日额度 | 额度耗尽后暂停，跨日恢复策略可执行 |
| B-09 异常恢复 | 窗口丢失、未知页、回退可诊断并恢复或明确阻断 |
| B-10 1 小时稳定 | 连续运行 1 小时无崩溃、重复成功记录或失控连点 |

当前技术债包括新旧自动化内核并存、Worker/Coordinator/插件类偏大、真机异常与长时运行证据不足；优先完成百度 MVP，再按职责收敛，避免提前推倒已工作的实现。

## 技术栈

- Kotlin 2.4.0
- Android Gradle Plugin 9.2.1
- Gradle Wrapper 9.4.1
- Jetpack Compose
- Material 3
- AndroidX Activity / Core / Lifecycle
- 百度地图 Android SDK 8.1.0
- Gradle Version Catalog

## 环境要求

- Android Studio 2026.1.1 或兼容版本
- 使用与当前 Gradle/AGP 兼容的 Gradle JDK，可在 Android Studio 中配置；代码 Java 目标与纯 Kotlin 模块工具链为 11，不能据此认定构建进程使用 JDK 11 即可
- Android SDK 37
- Android 设备或模拟器，最低系统版本 API 26
- 百度地图开放平台 Android 应用 AK；本地示例也预留高德 AK 配置
- 真机测试双地图分屏时，需要安装百度地图和高德地图，并手动启用 NearbyCheckIn 双地图诊断无障碍服务

## 本地配置

项目通过根目录 `local.properties` 读取本机 SDK 路径和百度地图 AK。该文件包含本机配置和密钥，已被忽略，不应提交或归档。

可参考 `local.properties.example` 添加：

```properties
BAIDU_MAP_AK=你的百度地图AK
AMAP_MAP_AK=你的高德地图AK
```

如果使用 Android Studio 打开项目，`sdk.dir` 通常由 IDE 自动写入 `local.properties`。

## 构建与验证

在 Windows PowerShell 中执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

运行本地单元测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest :core:common:test :core:model:test :core:domain:test
```

运行 Lint：

```powershell
.\gradlew.bat :app:lintDebug
```

运行仪器测试需要连接设备或启动模拟器：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

生成签名信息用于配置百度地图 AK：

```powershell
.\gradlew.bat :app:signingReport
```

Debug APK 默认输出到 `app/build/outputs/apk/debug/app-debug.apk`。发布 APK 与源码 ZIP 是不同产物；更换签名可能影响覆盖安装和地图 AK 授权，不应自动更换或提交签名密钥。

V5.4 记录的历史验证证据：255 项单元测试、0 失败、0 错误（42 个 XML suite，结果时间 2026-10-26 01:10）；Lint 为 0 error / 12 warning（报告时间 2026-09-05）。这些是审计基线，不表示本次文档同步重新运行了测试或 Lint，也不能替代真机 Gate。后续功能开发需重新构建、回归并补充真机证据。

## 权限说明

应用运行巡航服务和双地图诊断时会使用以下权限和系统能力：

- `ACCESS_COARSE_LOCATION`：获取大致位置。
- `ACCESS_FINE_LOCATION`：获取精确位置。
- `POST_NOTIFICATIONS`：在 Android 13 及以上显示前台服务通知。
- `SYSTEM_ALERT_WINDOW`：显示系统悬浮窗。
- `FOREGROUND_SERVICE` 和 `FOREGROUND_SERVICE_LOCATION`：运行位置类型前台服务。
- `INTERNET` 和 `ACCESS_NETWORK_STATE`：访问百度地图地点检索服务并判断网络状态。
- `AccessibilityService`：由用户在系统设置中手动启用，用于读取交互窗口、采集节点诊断，并按用户请求尝试触发系统分屏和启动地图应用。

## 目录说明

- `build-logic/`：统一纯 Kotlin 模块和测试配置的 Gradle Convention Plugin。
- `core/common/`：跨模块稳定基础抽象和统一时钟。
- `core/model/`：无 Android 依赖的 Provider、候选、地点键、任务、命令、结果、诊断、日志和自动化快照模型。
- `core/domain/`：Provider 协调器、状态机、移动/刷新策略、插件契约与注册表、Repository/平台端口和应用用例；同时保留旧兼容流程。
- `app/`：Android 应用入口、界面、平台能力、数据实现和依赖装配。
- `app/src/main/java/com/wu/nearbycheckin/` 下按职责划分：
  - `app/`：`AppContainer` 依赖装配。
  - `data/repository/`：SharedPreferences 快照、JSON 编解码、迁移和完整日志分段存储。
  - `data/legacy/`：迁移期旧记录与诊断仓储。
  - `presentation/main/`：`MainActivity`、`MainScreen`、`MainViewModel` 和 UI 契约。
  - `presentation/components/`：Compose 背景与雷达视觉组件。
  - `platform/cruise/`：定位快照、heartbeat、SDK POI 诊断和前台通知。
  - `platform/accessibility/`：Provider Worker、命令执行、手势安全、结果回写和窗口诊断。
  - `platform/maps/`：百度/高德插件、UI Profile、节点查找和窗口检查。
  - `platform/multiwindow/`：双地图启动与系统分屏控制。
  - `platform/overlay/`：系统悬浮窗控制。
  - `ui/theme/`：Compose 主题配置。
- `app/src/main/res/xml/accessibility_service_config.xml`：无障碍服务配置。
- `gradle/libs.versions.toml`：Gradle 插件和依赖版本目录。
- `local.properties.example`：本地敏感配置示例。
- `requirements.txt`：项目环境与依赖清单；当前项目没有 Python 运行依赖。

## 注意事项

- 不要提交或分享真实 `local.properties`、百度地图 AK、签名密钥或账号令牌。
- 修改百度地图 AK 后需要重新构建应用。
- 首次启动巡航服务前，需要同意百度地图隐私合规初始化并授予必要权限。
- 无障碍服务必须由用户手动启用；应用不能自动开启该权限。
- 地图端登录、验证码、安全弹窗、广告弹层和版本不兼容时必须失败关闭，不得绕过。
- 日志备份留在本地，不随提交或 Release 上传；暂存时显式选择文件，避免 `git add .` 将 `log/` 纳入。
- 正式源码归档应排除 `.git`、`.gradle`、`.idea`、所有 `build` 目录、`local.properties`、签名密钥和日志压缩包；V5.4 指出的旧归档内容问题不能视为已经清理完成。
