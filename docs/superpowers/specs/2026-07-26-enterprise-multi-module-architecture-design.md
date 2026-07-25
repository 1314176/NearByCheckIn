# NearbyCheckIn 企业级多模块架构设计

## 1. 文档状态

- 日期：2026-07-26
- 状态：设计已完成，等待最终文档审阅
- 目标渠道：按国内应用市场可上线标准设计，但本阶段不实际提交审核或发布
- 目标用途：可持续维护的 Android 应用，以及可用于求职展示的软件工程案例

## 2. 背景与目标

NearbyCheckIn 已完成一轮 V4 单模块分层重构，当前代码具备
`presentation`、`application`、`domain`、`data` 和 `platform` 分层，
并已实现候选判断、事务协调器、双地图状态机和基础单元测试。

本次重构不推翻已有业务成果，而是在现有 V4 基础上建立可由构建系统强制执行的模块边界，
补齐结构化持久化、进程恢复、依赖注入、质量门禁、安全合规、性能验证和发布工程。

### 2.1 核心目标

1. 将项目演进为渐进式多模块 Clean Architecture。
2. 让领域规则和状态机不依赖 Android SDK，可在 JVM 中快速测试。
3. 使用 Room 作为业务数据唯一事实来源，支持事务一致性和进程重建恢复。
4. 使用 DataStore 保存轻量设置，不再使用 SharedPreferences 承载业务事务。
5. 使用 Hilt 管理依赖图，消除组件内部直接构造 Repository 的行为。
6. 使用 Coroutines、Flow 和不可变 UI 状态实现单向数据流。
7. 为无障碍自动化建立版本化、确定性、失败关闭的 Provider Profile。
8. 建立静态检查、测试、覆盖率、Release 构建和真机验收质量门禁。
9. 生成架构图、ADR、质量报告和案例文档，形成可复现的求职案例。
10. 将大型 Gradle 缓存放到 D 盘，避免大量依赖和 Wrapper 文件占用 C 盘。

### 2.2 非目标

1. 本阶段不实际提交任何国内应用市场。
2. 本阶段不建设后端、账号系统、远端配置中心或云同步。
3. 本阶段不把地图自动化规则改造成可远端下发的动态脚本。
4. 本阶段不引入插件化框架、热修复框架或多产品壳。
5. 架构迁移期间不同时升级 AGP、Kotlin 和 Compose 大版本。
6. 不绕过地图登录、验证码、安全验证、权限或系统限制。

## 3. 固定工程约束

- `compileSdk = 37`
- `targetSdk = 37`
- `minSdk = 26`
- Java 源码与目标兼容级别继续使用 Java 11。
- 架构迁移期保留当前 AGP 9.2.1、Kotlin 2.4.0 和现有 Compose BOM。
- 新增依赖必须集中声明在 `gradle/libs.versions.toml`。
- 新增依赖必须选择与当前 AGP、Kotlin 和 Compose 兼容的稳定版本。
- 所有生产代码、文档、代码注释和 Git Commit 使用简体中文。
- 真实地图 AK、签名文件、签名密码和本机路径不得提交到 Git。
- 推荐设置 `GRADLE_USER_HOME=D:\Softwares\GradleUserHome`。
- Android SDK 继续由未提交的 `local.properties` 指向非 C 盘目录。
- 构建产物保留在 E 盘项目工作区。

## 4. 总体架构决策

采用“渐进式多模块 Clean Architecture”，保留一个 Android 应用，
按稳定业务边界拆分核心模块、功能模块和质量模块。

```text
:app
  ├── :feature:home
  ├── :feature:cruise
  ├── :feature:automation
  └── :core:data

:feature:*
  ├── :core:domain
  ├── :core:model
  ├── :core:common
  └── :core:ui

:core:data
  ├── :core:database
  ├── :core:domain
  ├── :core:model
  └── :core:common

:core:domain
  ├── :core:model
  └── :core:common

:benchmark
  └── :app
```

### 4.1 依赖规则

1. `:app` 只负责应用装配、Manifest、导航入口和 Release 配置。
2. 功能模块不得互相直接依赖，跨功能协作通过 `:core:domain` 接口完成。
3. `:core:domain` 和 `:core:model` 不得依赖 Android SDK。
4. `:core:data` 实现领域 Repository 接口，但领域模块不知道具体数据实现。
5. `:core:database` 只暴露 DAO 和数据库能力，不向 UI 暴露 Entity。
6. Android Service、Activity 和 AccessibilityService 只作为平台入口，不保存业务事实。
7. 功能模块不得直接使用 SharedPreferences、Room DAO 或其他功能模块的内部类。
8. 每个模块只公开消费者真正需要的类型，其余实现保持 `internal`。

## 5. 完整项目目录

```text
NearbyCheckIn/
├── app/
├── benchmark/
├── build-logic/
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   └── convention/src/main/kotlin/
│       ├── AndroidApplicationConventionPlugin.kt
│       ├── AndroidLibraryConventionPlugin.kt
│       ├── AndroidComposeConventionPlugin.kt
│       ├── AndroidFeatureConventionPlugin.kt
│       ├── KotlinLibraryConventionPlugin.kt
│       ├── TestingConventionPlugin.kt
│       └── QualityConventionPlugin.kt
├── core/
│   ├── common/
│   ├── model/
│   ├── domain/
│   ├── database/
│   ├── data/
│   ├── ui/
│   └── testing/
├── feature/
│   ├── home/
│   ├── cruise/
│   └── automation/
├── config/
│   ├── detekt/detekt.yml
│   ├── lint/lint.xml
│   └── quality/quality-thresholds.properties
├── docs/
│   ├── architecture/
│   ├── adr/
│   ├── quality/
│   ├── privacy/
│   ├── release/
│   └── case-study/
├── scripts/
│   ├── setup-dev-environment.ps1
│   ├── verify.ps1
│   ├── build-release-candidate.ps1
│   └── collect-device-evidence.ps1
├── gradle/
│   ├── libs.versions.toml
│   ├── verification-metadata.xml
│   └── wrapper/
├── .editorconfig
├── .gitignore
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── local.properties.example
├── keystore.properties.example
└── README.md
```

## 6. 模块职责

### 6.1 `:app`

负责：

- `NearbyCheckInApplication`
- `MainActivity`
- Hilt 应用入口
- 应用级导航
- 应用 Manifest 合并与权限声明
- Debug、Benchmark、Release 构建变体
- 签名占位、R8、资源压缩和网络安全配置

禁止承载候选判断、事务协调、Repository 实现和地图节点操作。

### 6.2 `:core:common`

保存跨模块稳定基础抽象：

- `AppResult`
- `AppError`
- `AppDispatchers`
- `AppClock`
- `AppLogger`
- Hilt 调度器限定符

该模块保持纯 Kotlin。

### 6.3 `:core:model`

保存不可变领域模型：

- `NearbyPlaceCandidate`
- `CheckInTransaction`
- `MapCommand`
- `MapOperationResult`
- `CheckInRecord`
- `AccessibilityDiagnostics`
- `AutomationLogEntry`

模型不得引用 `Context`、`Location`、`AccessibilityNodeInfo` 或 Room Entity。

### 6.4 `:core:domain`

保存领域接口和纯业务逻辑：

- Repository 接口
- 候选评价器
- 打卡事务协调器
- 双地图状态机
- 重试策略
- 状态恢复规则
- 启停巡航、提交候选、观察状态、查询记录和清空记录用例

所有状态迁移必须可通过 JVM 单元测试验证。

### 6.5 `:core:database`

使用 Room 保存：

- 地点
- 打卡事务
- 地图命令
- 地图操作结果
- 有限运行日志

每次数据库版本变化必须提供显式 Migration 和 Migration Test，
不得使用 destructive migration。

### 6.6 `:core:data`

负责：

- 领域 Repository 实现
- Room Entity 与领域模型映射
- DataStore 设置存储
- 旧 SharedPreferences JSON 数据迁移
- Hilt 数据层绑定
- 数据保留与清理策略

### 6.7 `:core:ui`

负责：

- Compose 主题
- 通用状态组件
- 雷达、背景、记录弹窗等可复用组件
- 预览数据

不得依赖具体功能 ViewModel。

### 6.8 `:core:testing`

负责测试共用能力：

- Fake Repository
- Fake Clock
- Fake Dispatchers
- Test Data Builder
- Main Dispatcher Rule

该模块只通过测试依赖进入生产模块，不进入 Release 运行时依赖。

### 6.9 `:feature:home`

负责：

- `HomeRoute`
- `HomeScreen`
- `HomeViewModel`
- `HomeUiState`
- `HomeUiEvent`
- `HomeUiEffect`
- 权限请求和系统设置跳转的 UI 协调

使用单向数据流：

```text
UiEvent → ViewModel → UseCase → Repository
Repository Flow → ViewModel → StateFlow<HomeUiState> → Compose
```

### 6.10 `:feature:cruise`

负责：

- 位置监听
- 百度 POI 检索
- 连续扫描
- 前台服务通知
- 系统悬浮窗
- 将候选提交到领域协调器

候选规则和事务状态不在该模块实现。

### 6.11 `:feature:automation`

负责：

- 无障碍服务入口
- 分屏与相邻窗口启动
- 窗口识别与恢复
- 百度、高德 Provider Adapter
- 版本化 UI Profile
- 地图命令执行
- 脱敏证据采集

该模块只执行确定性规则，不接收远端脚本，不进行通用应用控制。

### 6.12 `:benchmark`

负责：

- Macrobenchmark
- 冷启动测试
- 关键用户路径测试
- Baseline Profile 生成

## 7. 数据模型与持久化

### 7.1 `PlaceEntity`

关键字段：

- `id`
- `placeKey`
- `name`
- `address`
- `latitude`
- `longitude`
- `baiduPoiId`
- `amapPoiId`
- `firstSeenAt`
- `lastSeenAt`
- `completedAt`

`placeKey` 建立唯一索引。

### 7.2 `CheckInTransactionEntity`

关键字段：

- `transactionId`
- `placeId`
- `status`
- `createdAt`
- `startedAt`
- `completedAt`
- `failureCategory`
- `failureMessage`
- `version`

`version` 用于乐观状态迁移，更新时必须携带期望旧版本。

### 7.3 `AutomationCommandEntity`

关键字段：

- `commandId`
- `transactionId`
- `provider`
- `status`
- `claimToken`
- `claimedAt`
- `heartbeatAt`
- `attemptCount`
- `nextRetryAt`
- `createdAt`
- `completedAt`

`transactionId + provider` 建立唯一约束。

### 7.4 `MapOperationEntity`

关键字段：

- `operationId`
- `commandId`
- `provider`
- `status`
- `appVersion`
- `profileVersion`
- `page`
- `action`
- `attempt`
- `evidence`
- `startedAt`
- `completedAt`

证据写入前必须脱敏并限制长度。

### 7.5 DataStore

DataStore 只保存：

- 隐私同意状态
- 雷达显示状态与位置
- 非敏感功能开关
- 用户主动选择的调试级别

DataStore 不保存事务、命令、地图操作或完整记录列表。

## 8. 端到端数据流

1. `AndroidLocationSource` 输出定位。
2. `BaiduNearbyPoiDataSource` 检索附近 POI。
3. `CandidateEvaluator` 使用距离、精度、速度、停留时长和历史完成状态进行纯规则判断。
4. `CheckInCoordinator` 在单个数据库事务中写入地点、总事务和地图命令。
5. 无障碍服务原子领取下一条待执行命令。
6. Provider Profile 根据当前页面快照返回确定性动作。
7. Worker 执行动作并回写操作证据。
8. 协调器聚合百度与高德结果并推进总事务。
9. 只有两端均为 `SUCCESS` 或明确的 `ALREADY_COMPLETED`，总事务才进入 `COMPLETED`。
10. Repository 暴露 Flow，ViewModel 转换为不可变 UI 状态。

## 9. 状态模型

### 9.1 总事务状态

```text
DISCOVERED
  → QUALIFYING
  → PENDING_AUTOMATION
  → RUNNING
      ├── COMPLETED
      ├── RETRYABLE_FAILURE
      ├── BLOCKED
      └── CANCELLED
```

### 9.2 命令状态

```text
PENDING
  → CLAIMED
  → RUNNING
      ├── SUCCEEDED
      ├── RETRYABLE_FAILED
      └── TERMINAL_FAILED
```

状态迁移由 Repository 条件更新和数据库事务保证，
内存变量或 Mutex 不作为一致性依据。

## 10. 并发、幂等与恢复

1. 命令领取使用条件更新，只允许 `PENDING → CLAIMED`。
2. 命令租约初始设计为 30 秒，Worker 每 5 秒更新心跳。
3. 心跳超过 45 秒未更新时，恢复器可以回收命令租约。
4. 同一事务、同一地图端只允许一条有效命令。
5. 重复定位、重复无障碍事件和重复回调返回现有事务。
6. 已成功的地图端不得再次执行。
7. 永久去重只在总事务完成后写入。
8. 应用进程重建后从 Room 恢复状态，不从 Service 字段恢复。
9. 系统重启后不自动发起外部地图操作，需用户重新启动巡航。
10. 清空记录在单个数据库事务中处理记录、命令、结果和去重状态。

## 11. 错误模型与恢复策略

统一错误类型：

- `Transient`
- `Permission`
- `UserActionRequired`
- `Incompatible`
- `Data`
- `Fatal`

处理规则：

1. 弱网、网络超时和短暂窗口丢失允许有限重试。
2. 自动重试初始最多 3 次，延迟为 2 秒、5 秒、15 秒。
3. 相同地图版本连续出现不兼容错误后进入本地熔断。
4. 权限撤销立即停止相关能力，不循环弹出授权。
5. 登录、验证码、安全验证和账号风险进入 `BLOCKED`。
6. 未知地图版本或无法识别的页面进入 `TERMINAL_FAILED`。
7. 数据库迁移失败时保留旧数据并阻止继续写入，不静默清库。
8. 所有状态迁移记录时间、原因、旧状态、新状态和关联 ID。

## 12. 安全与隐私设计

1. Release 禁止明文网络流量。
2. 地图 AK 从本机属性或 CI 密钥注入。
3. Release 日志不得输出精确坐标、地址、POI ID、完整节点文本或密钥。
4. 备份规则排除事务、命令、诊断证据和隐私设置。
5. 无障碍服务只处理百度和高德包名。
6. 无障碍服务只执行版本化 Provider Profile 的确定性动作。
7. 禁止处理登录密码、验证码或任意应用输入。
8. 用户可随时停止巡航并清理待执行命令。
9. 默认不保存完整位置轨迹。
10. 运行日志最多保留 500 条或 7 天，以先达到的限制为准。
11. 提供隐私政策入口、权限用途说明、第三方 SDK 清单和数据删除路径。
12. 模拟发布产物包含权限清单、SDK 清单和网络域名清单。

## 13. 依赖与工具

生产依赖：

- Kotlin Coroutines 与 Flow
- Lifecycle ViewModel Compose
- Navigation Compose
- Room
- DataStore
- Hilt
- KSP

质量与构建工具：

- Android Lint
- Detekt
- Ktlint
- Kover
- Gradle Dependency Verification
- Macrobenchmark
- Baseline Profile
- CycloneDX SBOM

所有版本集中在 Version Catalog，架构迁移期不顺带升级现有大版本。

## 14. 构建与发布配置

### 14.1 Debug

- 可调试
- 使用脱敏详细日志
- 开启 StrictMode
- 不使用生产签名

### 14.2 Benchmark

- 不可调试
- 使用接近 Release 的优化配置
- 用于 Macrobenchmark 和 Baseline Profile

### 14.3 Release

- `isDebuggable = false`
- `isMinifyEnabled = true`
- `isShrinkResources = true`
- 明文流量关闭
- 日志最小化
- 签名从环境变量或未提交配置读取
- 生成 AAB、R8 mapping、SBOM 和校验和

## 15. 测试策略

### 15.1 JVM 单元测试

覆盖：

- 候选判断
- 状态机
- Reducer
- 重试策略
- Mapper
- UseCase
- ViewModel

### 15.2 集成测试

覆盖：

- Room DAO
- 所有数据库 Migration
- Repository 契约
- DataStore
- Hilt 依赖图

### 15.3 Android 测试

覆盖：

- Compose 交互
- 权限拒绝
- Service 生命周期
- 进程恢复
- Provider Profile
- 应用启动

### 15.4 真机测试

主要设备为小米 14，覆盖：

- 静止、步行和驾车
- 弱网和断网
- 锁屏
- 横竖屏
- 进程回收
- 系统重启
- 地图版本升级
- 连续运行

条件允许时补充华为、OPPO 或 vivo 任一真机。

## 16. 质量门禁

1. 编译、单元测试、Lint、Detekt、Ktlint 和依赖校验必须成功。
2. 新增或修改的 `:core:domain` 分支覆盖率不低于 80%。
3. 其他新增业务代码行覆盖率不低于 70%。
4. 所有状态和合法状态迁移必须有测试。
5. 每个数据库版本升级必须有 Migration Test。
6. 不允许新增 High 或 Critical 静态安全问题。
7. 不允许提交真实密钥和本机敏感配置。
8. `git diff --check` 必须成功。
9. Release Candidate 必须成功生成 AAB。
10. Release Candidate 必须完成至少一次 2 小时真机稳定性测试。

## 17. CI 流水线

### 17.1 Pull Request

- 格式检查
- Detekt
- Android Lint
- JVM 单元测试
- 覆盖率
- Debug 构建
- 依赖校验

### 17.2 Nightly

- 仪器测试
- Room Migration Test
- Compose UI 测试
- API 26、33、37 兼容测试

### 17.3 Release Candidate

- Release Lint
- R8 与资源压缩
- AAB
- SBOM
- 权限、SDK 与网络域名清单
- 产物 SHA-256
- Git SHA 与依赖版本记录

## 18. 性能与稳定性

1. 使用 Macrobenchmark 建立冷启动、主页首帧和记录列表基线。
2. Release Candidate 不得较已确认基线回退超过 10%。
3. 生成覆盖启动、主页和记录列表的 Baseline Profile。
4. 数据库和旧数据迁移不得在主线程执行。
5. Debug 使用 StrictMode 检测磁盘和网络违规。
6. 连续巡航检查内存增长、重复协程、定位监听和无障碍节点回收。
7. 案例文档记录峰值内存、恢复次数、失败分类和电量影响。

## 19. 迁移阶段

整个架构重构拆为六个可独立构建和验收的阶段。

### 阶段一：构建治理与领域边界

- 建立 `build-logic`
- 建立 `:core:common`、`:core:model`、`:core:domain`
- 迁移现有纯 Kotlin 规则和测试
- 保持应用功能不变

### 阶段二：Room 与数据迁移

- 建立 `:core:database`、`:core:data`
- 实现 Room、DataStore 和 Repository
- 从旧 SharedPreferences JSON 一次性迁移
- 建立数据库 Migration Test

### 阶段三：功能模块拆分

- 建立 `:feature:cruise`
- 建立 `:feature:automation`
- 迁移 Service、地图 SDK、无障碍和分屏能力
- 保持 Manifest 能力和外部行为不变

### 阶段四：应用壳与主页

- 建立 `:feature:home`、`:core:ui`
- 迁移 ViewModel、Compose 和导航
- 缩小 `:app` 到装配职责

### 阶段五：质量、安全与发布工程

- 接入 Detekt、Ktlint、Kover 和依赖校验
- 启用 R8、资源压缩和网络安全配置
- 建立 CI、Release Candidate 脚本和隐私材料

### 阶段六：性能、真机证据与案例文档

- 建立 `:benchmark`
- 生成 Baseline Profile
- 执行设备矩阵和 2 小时稳定性测试
- 输出 ADR、指标和 Case Study

## 20. 风险与缓解

### 20.1 一次性移动文件导致功能回归

缓解：按阶段迁移，每个阶段保持可编译、可测试、可回滚，不同时修改无关业务行为。

### 20.2 Room 迁移损坏旧数据

缓解：保存旧数据快照，提供显式 Migration Test，迁移失败时不删除旧数据。

### 20.3 多模块导致构建变慢

缓解：使用 Convention Plugin、配置缓存、并行构建和稳定模块边界，并记录迁移前后构建时间。

### 20.4 Hilt 与 KSP 增加构建复杂度

缓解：依赖版本集中管理，Hilt 仅用于装配边界，领域代码不依赖 Hilt。

### 20.5 第三方地图 UI 升级导致规则失效

缓解：Provider Profile 绑定应用版本，未知版本失败关闭，并通过脱敏证据更新 Profile。

### 20.6 无障碍能力带来合规风险

缓解：限定包名、限定确定性动作、显著说明用途、允许随时停止，并禁止验证码和账号安全自动化。

## 21. 案例交付物

- 架构总览图
- Gradle 模块依赖图
- Room ER 图
- 双地图状态机图
- 三份核心 ADR
- 测试策略与质量门禁文档
- 隐私数据清单和权限说明
- Release Candidate 检查表
- 重构前后工程指标
- 完整 Case Study
- 可复现构建与验证命令

## 22. 完成定义

只有同时满足以下条件，企业级架构重构才算完成：

1. 所有目标模块已建立并遵守依赖方向。
2. 旧业务状态已迁移到 Room，设置已迁移到 DataStore。
3. Activity、Service 和 AccessibilityService 不再保存业务事实。
4. 双地图事务可在进程重建后恢复，并保证已成功端不重复执行。
5. 所有质量门禁通过。
6. Release AAB 可生成，但不实际上传。
7. 小米 14 核心链路和 2 小时稳定性验收通过。
8. 架构、ADR、质量、隐私、发布和 Case Study 文档齐全。

## 23. 官方设计依据

- Android 应用架构指南：
  https://developer.android.com/topic/architecture
- Android 架构建议：
  https://developer.android.com/topic/architecture/recommendations
- Android 安全与隐私设计：
  https://developer.android.com/quality/privacy-and-security
- Android 安全检查清单：
  https://developer.android.com/privacy-and-security/security-tips
- Baseline Profile：
  https://developer.android.com/topic/performance/baselineprofiles/overview
- Google Play 无障碍服务政策参考：
  https://support.google.com/googleplay/android-developer/answer/10964491
