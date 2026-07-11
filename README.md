# NearbyCheckIn

NearbyCheckIn 是一个 Android/Kotlin 应用，用于在前台服务中持续获取定位、检索附近百度地图 POI，并在满足距离和停留条件后保存本地打卡记录。项目当前处于 V2.0 双地图分屏诊断和本地自动打卡阶段。

## 当前状态

- 已接入 Kotlin、Jetpack Compose、Material 3、Android 前台服务和百度地图 Android SDK。
- 已实现定位权限、通知权限、系统悬浮窗权限和无障碍服务入口。
- 已实现 GPS/网络定位监听、百度 POI 周边检索、本地打卡记录保存和今日/全部记录查看。
- 已实现永久去重：同一地点完成本地打卡后，重启应用或服务后仍会跳过。
- 已实现静止心跳扫描：没有新的定位回调时，也会按间隔继续检索附近未完成地点。
- 已实现双地图诊断：识别百度地图和高德地图窗口，统计节点、可点击节点和候选节点摘要。
- 已实现自动尝试双地图分屏：无障碍服务启用后，应用可请求启动百度地图巡航、切换系统分屏，并把高德地图打开到相邻窗口。
- 已接入基础地图端节点打卡流程：百度地图按“我的-足迹-打卡”点击，高德地图按“我的-打卡点-立刻打卡”点击。
- 地图端点击仅按可访问节点文本执行，找不到节点会失败关闭，不使用固定坐标盲点。
- 尚未实现地图端成功结果识别、双地图事务记录和长期耐久验收；当前 NearbyCheckIn 本地记录仍独立保存。

## 功能概览

- 前台服务持续监听定位变化，并通过通知显示当前状态。
- 百度地图 SDK 将系统定位坐标转换为百度坐标，并检索当前位置附近 POI。
- 根据地点距离和停留时间判断是否生成本地自动打卡记录。
- 本地记录使用 `SharedPreferences` 保存，不再设置固定条数截断，并维护永久去重地点集合。
- 主界面支持查看今日记录、全部记录，以及二次确认清空本地记录和永久去重集合。
- 系统悬浮窗显示微光雷达，可拖动、隐藏，并可从前台服务通知恢复。
- 启用无障碍服务后，可读取分屏窗口和节点摘要，并在用户启动请求后执行已配置的地图节点点击流程。
- 应用启动后会在条件满足时自动请求打开双地图分屏；若地图未安装或无障碍未启用，会在界面显示原因。

## 双地图能力边界

- 自动分屏依赖 Android 系统当前是否允许 `GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN` 和相邻窗口启动请求；项目不强制指定百度或高德在上方窗口。
- 百度地图巡航通过 `baidumap://map/navi/cruiser?src=andr.wu.nearbycheckin` URI 尝试启动，失败时回退到包名启动百度地图。
- 高德地图当前通过包名启动到相邻窗口；尚未确认可公开调用的巡航 URI。
- 第三方地图账号内的“打卡记录”写入没有可靠公开接口，当前仅执行已配置节点路径；地图端是否成功写入必须以后续真机节点结果识别为准，不能伪造成功。

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

- `app/src/main/java/com/wu/nearbycheckin/MainActivity.kt`：主界面、权限流程、记录查看、清空确认和自动分屏请求。
- `app/src/main/java/com/wu/nearbycheckin/CruiseService.kt`：前台定位服务、百度 POI 检索调度、本地打卡判断、静止心跳和悬浮窗生命周期。
- `app/src/main/java/com/wu/nearbycheckin/NearbyPoiSearcher.kt`：百度坐标转换和周边 POI 检索。
- `app/src/main/java/com/wu/nearbycheckin/CheckInRepository.kt`：本地打卡记录、永久去重集合和旧记录迁移。
- `app/src/main/java/com/wu/nearbycheckin/DualMapAccessibilityService.kt`：无障碍服务、双地图窗口诊断触发和自动分屏请求执行。
- `app/src/main/java/com/wu/nearbycheckin/AccessibilityNodeActions.kt`：按可访问节点文本查找并点击目标节点。
- `app/src/main/java/com/wu/nearbycheckin/AccessibilityWindowInspector.kt`：窗口包名识别、节点统计和诊断日志。
- `app/src/main/java/com/wu/nearbycheckin/BaiduMapAdapter.kt`：百度地图窗口只读 UI 画像。
- `app/src/main/java/com/wu/nearbycheckin/AmapMapAdapter.kt`：高德地图窗口只读 UI 画像。
- `app/src/main/java/com/wu/nearbycheckin/GlowRadarUI.kt`：Compose 悬浮雷达界面。
- `app/src/main/java/com/wu/nearbycheckin/ui/theme`：Compose 主题配置。
- `app/src/main/res/xml/accessibility_service_config.xml`：无障碍服务配置。
- `gradle/libs.versions.toml`：Gradle 插件和依赖版本目录。
- `local.properties.example`：本地敏感配置示例。
- `requirements.txt`：项目环境与依赖清单；当前项目没有 Python 运行依赖。

## 后续计划

- 真机采集百度地图和高德地图目标页面节点，建立版本化 UI Profile。
- 完善百度地图端成功/失败/超时识别和有限重试。
- 完善高德地图端成功/失败/超时识别和有限重试。
- 实现双地图事务协调：同一地点按百度到高德顺序执行，分别保存两端结果和失败原因。
- 扩展记录模型：统一地点、双地图结果、整体状态、重试次数和失败原因。
- 完成静止、步行、弱网、锁屏、进程重建、地图升级和连续运行测试。

## 注意事项

- 不要提交或分享真实 `local.properties`、百度地图 AK、签名密钥或账号令牌。
- 修改百度地图 AK 后需要重新构建应用。
- 首次启动巡航服务前，需要同意百度地图隐私合规初始化并授予必要权限。
- 无障碍服务必须由用户手动启用；应用不能自动开启该权限。
- 地图端登录、验证码、安全弹窗、广告弹层和版本不兼容时必须失败关闭，不得绕过。
- 归档源码时应排除 `.gradle`、`.idea`、`build`、`app/build` 和 `local.properties`。
