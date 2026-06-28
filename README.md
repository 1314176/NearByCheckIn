# NearbyCheckIn

NearbyCheckIn 是一个 Android/Kotlin 项目，用于启动巡航定位服务，在前台通知和悬浮窗中展示当前位置状态，并通过百度地图 SDK 检索附近地点。

## 功能概览

- 基于 Jetpack Compose 构建主界面和雷达样式悬浮窗。
- 通过前台服务持续监听 GPS 和网络定位。
- 使用百度地图 SDK 进行周边 POI 地点检索。
- 按需申请定位、通知和系统悬浮窗权限。
- 使用 `local.properties` 注入百度地图开放平台 Android 应用 AK。

## 技术栈

- Kotlin
- Android Gradle Plugin
- Jetpack Compose
- Material 3
- AndroidX Lifecycle
- 百度地图 Android SDK
- Gradle Version Catalog

## 环境要求

- Android Studio
- JDK 11 或以上
- Android SDK，项目当前 `compileSdk` 和 `targetSdk` 为 37
- 百度地图开放平台 Android 应用 AK

## 本地配置

项目通过根目录 `local.properties` 读取百度地图 AK。该文件包含本机配置，已在 `.gitignore` 中忽略，不应提交。

可参考 `local.properties.example` 添加以下配置：

```properties
BAIDU_MAP_AK=你的百度地图AK
```

如果使用 Android Studio 打开项目，`sdk.dir` 通常会由 IDE 自动写入 `local.properties`。

## 构建与运行

在 Windows PowerShell 中执行：

```powershell
.\gradlew.bat assembleDebug
```

运行单元测试：

```powershell
.\gradlew.bat test
```

运行仪器测试需要连接设备或启动模拟器：

```powershell
.\gradlew.bat connectedAndroidTest
```

## 权限说明

应用运行巡航服务时会使用以下权限：

- `ACCESS_COARSE_LOCATION`：获取大致位置。
- `ACCESS_FINE_LOCATION`：获取精确位置。
- `POST_NOTIFICATIONS`：在 Android 13 及以上显示前台服务通知。
- `SYSTEM_ALERT_WINDOW`：显示系统悬浮窗。
- `FOREGROUND_SERVICE` 和 `FOREGROUND_SERVICE_LOCATION`：运行位置类型前台服务。
- `INTERNET` 和 `ACCESS_NETWORK_STATE`：访问百度地图地点检索服务并判断网络状态。

## 目录说明

- `app/src/main/java/com/wu/nearbycheckin`：应用主代码。
- `app/src/main/res`：资源文件和备份规则配置。
- `gradle/libs.versions.toml`：Gradle 依赖和插件版本目录。
- `local.properties.example`：本地敏感配置示例。
- `requirements.txt`：Python 依赖清单；当前项目无 Python 运行依赖。

## 注意事项

- 不要提交真实的 `local.properties`、百度地图 AK 或其他密钥。
- 修改百度地图 AK 后需要重新构建应用。
- 首次启动巡航服务前，需要同意百度地图隐私合规初始化并授予必要权限。
