# 第一阶段模块边界

## 目标

第一阶段通过 Gradle 多模块依赖方向，把稳定的纯 Kotlin 业务核心与 Android 平台实现隔离。此阶段保持现有功能和持久化行为不变，不提前引入 Room、DataStore 或依赖注入框架。

## 依赖方向

```text
:app -> :core:common + :core:model + :core:domain
:core:domain -> :core:common + :core:model
:core:common -> 无项目模块依赖
:core:model -> 无项目模块依赖
```

依赖只能沿箭头方向建立，核心模块不得反向依赖 `:app`。

## 模块职责

### `:app`

- Android Application、Activity、Service、AccessibilityService 和 Compose 界面。
- 地图 SDK、定位、系统权限、分屏、悬浮窗和节点自动化适配。
- SharedPreferences 快照实现、JSON 编解码和旧数据迁移实现。
- 应用级依赖装配。

### `:core:common`

- 跨模块稳定基础抽象。
- 当前包含可替换的 `AppClock` 及系统实现。
- 不依赖 Android SDK 或 AndroidX。

### `:core:model`

- 不可变领域模型、枚举、快照和记录。
- 不包含 Android 类型、存储 Entity 或平台对象。
- 不依赖其他项目模块。

### `:core:domain`

- 候选评估规则、双地图状态机和事务协调器。
- Repository、平台网关及旧数据清理端口。
- 启动、停止、候选提交、记录查询和数据清理用例。
- 只依赖 `:core:common`、`:core:model` 和纯 JVM 依赖。

## 禁止依赖

- `:core:common`、`:core:model`、`:core:domain` 禁止导入 `android.*` 或 `androidx.*`。
- 领域模块禁止引用 SharedPreferences、Context、地图 SDK 和具体 Repository 实现。
- `:core:model` 禁止引用 `:core:domain`。
- 平台实现不得把 Android 类型暴露给领域接口。

## 后续演进

第二阶段再建立 `:core:database` 与 `:core:data`，引入 Room、DataStore 和受测试保护的旧数据迁移。功能模块和 UI 模块将在后续独立阶段拆分，避免一次性迁移扩大回归范围。
