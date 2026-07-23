# NearbyCheckIn

NearbyCheckIn 是一个 Android/Kotlin 应用，用于在前台服务中持续获取定位、检索附近百度地图 POI，并通过事务协调器串行完成百度地图和高德地图双端打卡。项目当前采用 V4.0 分层架构。

## 当前状态

- 已接入 Kotlin、Jetpack Compose、Material 3、Android 前台服务和百度地图 Android SDK。
- 已实现定位权限、通知权限、系统悬浮窗权限和无障碍服务入口。
- 已实现 GPS/网络定位监听、百度 POI 周边检索、本地打卡记录保存和今日/全部记录查看。
- 已实现永久去重：同一地点完成本地打卡后，重启应用或服务后仍会跳过。
- 已实现静止心跳扫描：没有新的定位回调时，也会按间隔继续检索附近未完成地点。
- 已实现双地图诊断：识别百度地图和高德地图窗口，统计节点、可点击节点和候选节点摘要。
- 已实现自动双地图分屏：Android 12L 及以上由前台页面把高德打开到相邻窗格，再由百度地图替换应用所在窗格；旧版系统仅在公开分屏全局动作可用时调用该动作。
- 已接入基础地图端节点打卡流程：百度地图按“我的-打卡点-立即打卡”点击，高德地图按“我的-足迹-打卡”点击。
- 地图端点击仅按可访问节点文本执行，找不到节点会失败关闭，不使用固定坐标盲点。
- 已加入地图端基础文本结果探测：最终按钮点击后会识别成功、已打卡、失败、登录/验证码/权限阻断和超时。
- 已建立 Compose 单向数据流、用例层、候选评估器、纯 Kotlin 双地图状态机和事务协调器。
- 已建立 `CheckInTransactionRepository` 唯一事实来源，可观察并持久化会话、候选、事务、命令、结果、诊断、分屏请求、记录和永久去重键。
- 已在“数据与记录”中加入持久化运行日志，可按时间倒序查看信息、警告和错误，并支持单独清空日志。
- `CruiseService` 只提交标准候选；无障碍服务只领取命令、调用地图 Adapter 并回写结果证据。
- 一端成功、另一端失败时只重试失败端；重复候选、重复命令结果和命令重新领取均按稳定 ID 幂等处理。

## 功能概览

- 前台服务持续监听定位变化，并通过通知显示当前状态。
- 百度地图 SDK 将系统定位坐标转换为百度坐标，并检索当前位置附近 POI。
- `CandidateEvaluator` 统一判断距离、停留、定位稳定、位置过期、永久去重和队列冲突。
- `DualMapStateMachine` 以纯 Kotlin reducer 推进百度、高德、部分成功、退避重试、阻断和终态。
- 自动化快照使用 `SharedPreferences` 原子保存，并兼容迁移旧记录和永久去重集合。
- 主界面支持查看今日记录、全部记录和结构化运行日志，以及二次确认清空本地记录、双地图事务记录和永久去重集合。
- 系统悬浮窗显示微光雷达，可拖动、隐藏，并可从前台服务通知恢复。
- 启用无障碍服务后，可读取分屏窗口和节点摘要，并在用户启动请求后执行已配置的地图节点点击流程。
- 应用启动后会在条件满足时自动请求打开双地图分屏；若地图未安装或无障碍未启用，会在界面显示原因。

## 双地图能力边界

- Android 12L 及以上的自动分屏依赖系统接受前台 `FLAG_ACTIVITY_LAUNCH_ADJACENT` 请求；该请求仍由系统和厂商窗口策略决定，应用不能绕过系统限制。
- Android 12L 以下仅在无障碍服务公开系统动作列表包含 `GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN` 时请求旧版分屏，不再盲目调用已被系统移除的动作。
- 百度地图通过普通启动入口接管应用所在窗格，避免巡航页与“我的-打卡点”节点流程冲突；高德地图通过包名进入相邻窗格。
- 高德地图当前通过包名启动到相邻窗口；尚未确认可公开调用的巡航 URI。
- 第三方地图账号内的“打卡记录”写入没有可靠公开接口，当前仅执行已配置节点路径和基础文本探测；地图端是否成功写入必须以后续真机 UI Profile 结果识别为准，不能伪造成功。

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
- JDK 11 或以上
- Android SDK 37
- Android 设备或模拟器，最低系统版本 API 26
- 百度地图开放平台 Android 应用 AK
- 真机测试双地图分屏时，需要安装百度地图和高德地图，并手动启用 NearbyCheckIn 双地图诊断无障碍服务

## 本地配置

项目通过根目录 `local.properties` 读取本机 SDK 路径和百度地图 AK。该文件包含本机配置和密钥，已被忽略，不应提交或归档。

可参考 `local.properties.example` 添加：

```properties
BAIDU_MAP_AK=你的百度地图AK
```

如果使用 Android Studio 打开项目，`sdk.dir` 通常由 IDE 自动写入 `local.properties`。

## 构建与验证

在 Windows PowerShell 中执行：

```powershell
.\gradlew.bat assembleDebug
```

运行本地单元测试：

```powershell
.\gradlew.bat test
```

运行 Lint：

```powershell
.\gradlew.bat lintDebug
```

运行仪器测试需要连接设备或启动模拟器：

```powershell
.\gradlew.bat connectedAndroidTest
```

生成签名信息用于配置百度地图 AK：

```powershell
.\gradlew.bat signingReport
```

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

- `app/`：`NearbyCheckInApplication` 与 `AppContainer`，集中创建进程级共享依赖。
- `application/usecase/`：启动、停止、提交候选、查询记录和清理记录等业务入口。
- `domain/model/`：无 Android 依赖的候选、事务、命令、结果、诊断、运行日志和自动化快照模型。
- `domain/evaluator/`：纯 Kotlin 候选距离、停留、定位稳定、过期和去重规则。
- `domain/statemachine/`：纯 Kotlin 双地图事务 reducer。
- `domain/coordinator/`：串行协调候选、事务、状态机、命令、结果、重试和恢复。
- `domain/repository/`：`CheckInTransactionRepository` 唯一事实来源契约。
- `data/repository/`：SharedPreferences 快照实现、JSON 编解码和旧数据迁移。
- `data/legacy/`：迁移期只读兼容的旧记录与诊断仓储。
- `presentation/main/`：`MainActivity`、`MainScreen`、`MainViewModel` 和 UI 契约。
- `presentation/components/`：Compose 背景与悬浮雷达视觉组件。
- `platform/cruise/`：定位、POI 检索、前台通知和悬浮窗平台适配。
- `platform/accessibility/`：命令领取、地图 Adapter 调用、证据回写和窗口诊断。
- `platform/maps/`：百度/高德 UI Profile、节点查找、点击和窗口检查。
- `platform/multiwindow/`：双地图启动与系统分屏中转。
- `app/src/main/java/com/wu/nearbycheckin/ui/theme`：Compose 主题配置。
- `app/src/main/res/xml/accessibility_service_config.xml`：无障碍服务配置。
- `gradle/libs.versions.toml`：Gradle 插件和依赖版本目录。
- `local.properties.example`：本地敏感配置示例。
- `requirements.txt`：项目环境与依赖清单；当前项目没有 Python 运行依赖。

## 后续计划

- 真机采集百度地图“我的-打卡点-立即打卡”和高德地图“我的-足迹-打卡”目标页面节点，建立版本化 UI Profile。
- 用真机 UI Profile 细化百度地图端成功、已打卡、失败、阻断和超时识别。
- 用真机 UI Profile 细化高德地图端成功、已打卡、失败、阻断和超时识别。
- 在小米 14 真机验证进程强杀后的命令租约恢复、部分成功重试和永久去重。
- 完成静止、步行、弱网、锁屏、进程重建、地图升级和连续运行测试。

## 注意事项

- 不要提交或分享真实 `local.properties`、百度地图 AK、签名密钥或账号令牌。
- 修改百度地图 AK 后需要重新构建应用。
- 首次启动巡航服务前，需要同意百度地图隐私合规初始化并授予必要权限。
- 无障碍服务必须由用户手动启用；应用不能自动开启该权限。
- 地图端登录、验证码、安全弹窗、广告弹层和版本不兼容时必须失败关闭，不得绕过。
- 归档源码时应排除 `.gradle`、`.idea`、`build`、`app/build` 和 `local.properties`。
