# Task 2：R-082 百度 21.18.50 UI Profile 报告

## 状态

已完成。`BaiduUiProfile` 位于 `:app/platform/maps`，未移动模块边界，且保持纯 Kotlin 实现。

## 修改文件

- `app/src/main/java/com/wu/nearbycheckin/platform/maps/BaiduUiProfile.kt`
- `app/src/test/java/com/wu/nearbycheckin/platform/maps/BaiduUiProfileTest.kt`

## RED

命令：

```powershell
$env:GRADLE_USER_HOME='D:\Softwares\Android\GradleUserHome'; $env:ANDROID_HOME='D:\Softwares\Android\Sdk'; $env:ANDROID_SDK_ROOT='D:\Softwares\Android\Sdk'; .\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.maps.BaiduUiProfileTest" --console=plain
```

输出摘要：失败于 `:app:compileDebugUnitTestKotlin`，提示 `Unresolved reference 'BaiduUiProfile'`，符合预期。

## GREEN

命令：

```powershell
$env:GRADLE_USER_HOME='D:\Softwares\Android\GradleUserHome'; $env:ANDROID_HOME='D:\Softwares\Android\Sdk'; $env:ANDROID_SDK_ROOT='D:\Softwares\Android\Sdk'; .\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.platform.maps.BaiduUiProfileTest" --console=plain
```

输出摘要：`BUILD SUCCESSFUL`；目标 `BaiduUiProfileTest` 通过。

## 提交 SHA

`cfdaacc5f2cdb6ffe88942237b4c9e4c4532ffcb`（实现百度地图 UI Profile）。

## 自查

- 已执行 `git diff --check`，无空白错误。
- 已检查新增生产与测试文件，不含 `import android.*`。
- Profile 仅依赖 Task 1 的 `MapUiSnapshot`、`MapUiDecision` 与关联模型。
- 测试覆盖入口、历史页、已知弹窗、立即打卡、成功、已完成及安全阻断优先级。

## 关注事项

- 构建存在既有 `android.enableJetifier=true` 弃用警告；与本任务修改无关。
