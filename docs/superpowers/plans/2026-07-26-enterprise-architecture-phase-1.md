# 企业级架构重构第一阶段实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立企业级 Gradle 构建治理和纯 Kotlin 核心模块，并把现有模型、规则、状态机、协调器、Repository 接口与用例从 `:app` 迁移到稳定模块。

**Architecture:** 第一阶段只建立 `build-logic`、`:core:common`、`:core:model` 和 `:core:domain`，现有 Android UI、Service、数据实现和地图能力继续留在 `:app`。通过 Gradle 依赖方向强制 Android 代码依赖领域抽象，领域模块不依赖 Android SDK。

**Tech Stack:** Kotlin 2.4.0、Gradle 9.4.1、AGP 9.2.1、Java 11、JUnit 4.13.2、Kotlin Coroutines 1.9.0、Gradle Convention Plugins。

## Global Constraints

- 保持 `compileSdk = 37`、`targetSdk = 37`、`minSdk = 26`。
- 架构迁移阶段不升级 AGP、Kotlin、Compose BOM 和百度地图 SDK 大版本。
- 所有大型 Gradle 缓存使用 `GRADLE_USER_HOME=D:\Softwares\GradleUserHome`。
- 不修改或提交 `local.properties`、地图 AK、签名文件、密码和令牌。
- 不覆盖当前未跟踪的设计与计划文档。
- 每个任务结束时必须通过对应测试和 `git diff --check`。
- 本计划中的提交命令只作为建议检查点；未经哥哥单独授权，不实际执行 `git commit`。
- Kotlin 新增函数与关键方法使用准确的中文功能注释，并保持注释位于函数或方法上方。

---

## 计划拆分说明

完整企业级重构包含六个独立阶段。本文件只实施第一阶段；后续阶段分别建立独立计划：

1. 构建治理与领域边界。
2. Room、DataStore 与旧数据迁移。
3. `:feature:cruise` 和 `:feature:automation`。
4. `:feature:home`、`:core:ui` 和应用壳。
5. 质量、安全、CI 与 Release Candidate。
6. Benchmark、真机验收与 Case Study。

第一阶段验收通过后才能开始第二阶段。

---

### Task 1: 建立 Convention Plugin 与核心模块骨架

**Files:**
- Create: `build-logic/settings.gradle.kts`
- Create: `build-logic/convention/build.gradle.kts`
- Create: `build-logic/convention/src/main/kotlin/KotlinLibraryConventionPlugin.kt`
- Create: `build-logic/convention/src/main/kotlin/TestingConventionPlugin.kt`
- Create: `core/common/build.gradle.kts`
- Create: `core/model/build.gradle.kts`
- Create: `core/domain/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `.gitignore`

**Interfaces:**
- Produces: Gradle 插件 `nearbycheckin.kotlin.library`。
- Produces: Gradle 插件 `nearbycheckin.testing`。
- Produces: 模块 `:core:common`、`:core:model`、`:core:domain`。
- Consumes: 当前 Kotlin 2.4.0 与 JUnit 4.13.2 版本目录。

- [ ] **Step 1: 记录迁移前基线**

Run:

```powershell
$env:GRADLE_USER_HOME='D:\Softwares\GradleUserHome'
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
git status --short
```

Expected:

- Gradle 输出 `BUILD SUCCESSFUL`。
- Lint 为 0 error。
- Git 状态只包含已知设计和计划文档。

- [ ] **Step 2: 写入 build-logic 设置**

`build-logic/settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
```

- [ ] **Step 3: 写入 Convention Plugin 工程**

`build-logic/convention/build.gradle.kts`：

```kotlin
plugins {
    `kotlin-dsl`
}

group = "com.wu.nearbycheckin.buildlogic"

dependencies {
    implementation(
        "org.jetbrains.kotlin:kotlin-gradle-plugin:" +
                libs.versions.kotlin.get()
    )
}

gradlePlugin {
    plugins {
        register("kotlinLibrary") {
            id = "nearbycheckin.kotlin.library"
            implementationClass = "KotlinLibraryConventionPlugin"
        }
        register("testing") {
            id = "nearbycheckin.testing"
            implementationClass = "TestingConventionPlugin"
        }
    }
}
```

- [ ] **Step 4: 实现纯 Kotlin 模块约定**

`KotlinLibraryConventionPlugin.kt`：

```kotlin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

class KotlinLibraryConventionPlugin : Plugin<Project> {

    // 统一纯 Kotlin 模块的插件和 Java 工具链。
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            extensions.configure<KotlinJvmProjectExtension> {
                jvmToolchain(11)
            }
        }
    }
}
```

- [ ] **Step 5: 实现测试约定**

`TestingConventionPlugin.kt`：

```kotlin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

class TestingConventionPlugin : Plugin<Project> {

    // 统一 JVM 测试依赖和日志输出。
    override fun apply(target: Project) {
        with(target) {
            val libs = extensions
                .getByType<VersionCatalogsExtension>()
                .named("libs")
            dependencies.add(
                "testImplementation",
                libs.findLibrary("junit").get()
            )
            tasks.withType<Test>().configureEach {
                useJUnit()
                testLogging {
                    events("failed", "skipped")
                }
            }
        }
    }
}
```

- [ ] **Step 6: 注册显式 Coroutines 依赖**

在 `gradle/libs.versions.toml` 增加：

```toml
[versions]
coroutines = "1.9.0"

[libraries]
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
```

保持现有条目顺序，不重复创建 `[versions]` 或 `[libraries]` 区块。

- [ ] **Step 7: 创建三个核心模块构建文件**

`core/common/build.gradle.kts` 与 `core/model/build.gradle.kts`：

```kotlin
plugins {
    id("nearbycheckin.kotlin.library")
    id("nearbycheckin.testing")
}
```

`core/domain/build.gradle.kts`：

```kotlin
plugins {
    id("nearbycheckin.kotlin.library")
    id("nearbycheckin.testing")
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
}
```

- [ ] **Step 8: 在根设置中加载构建逻辑和模块**

在 `settings.gradle.kts` 的 `pluginManagement` 首行增加：

```kotlin
includeBuild("build-logic")
```

将模块声明改为：

```kotlin
include(
    ":app",
    ":core:common",
    ":core:model",
    ":core:domain"
)
```

- [ ] **Step 9: 让 app 显式依赖核心模块**

在 `app/build.gradle.kts` 的 `dependencies` 首部增加：

```kotlin
implementation(project(":core:common"))
implementation(project(":core:model"))
implementation(project(":core:domain"))
```

- [ ] **Step 10: 忽略可视化临时目录**

在 `.gitignore` 增加：

```gitignore
# Superpowers 设计会话临时文件
.superpowers/
```

- [ ] **Step 11: 验证构建逻辑和空模块**

Run:

```powershell
$env:GRADLE_USER_HOME='D:\Softwares\GradleUserHome'
.\gradlew.bat :core:common:test :core:model:test :core:domain:test :app:assembleDebug
git diff --check
```

Expected: 所有任务显示 `BUILD SUCCESSFUL`，且无空白错误。

- [ ] **Step 12: 提交检查点**

仅在哥哥显式授权提交后执行：

```powershell
git add .gitignore settings.gradle.kts gradle/libs.versions.toml app/build.gradle.kts build-logic core
git commit -m "构建：建立核心模块与统一构建约定"
```

---

### Task 2: 提取可测试的统一时钟

**Files:**
- Create: `core/common/src/main/kotlin/com/wu/nearbycheckin/core/common/time/AppClock.kt`
- Modify: `app/src/main/java/com/wu/nearbycheckin/domain/coordinator/CheckInCoordinator.kt`
- Modify: `app/src/test/java/com/wu/nearbycheckin/domain/coordinator/CheckInCoordinatorTest.kt`
- Modify: `app/src/main/java/com/wu/nearbycheckin/app/AppContainer.kt`

**Interfaces:**
- Produces: `fun interface AppClock { fun nowMillis(): Long }`。
- Produces: `object SystemAppClock : AppClock`。
- Consumes: `CheckInCoordinator(clock: AppClock)`。

- [ ] **Step 1: 先把协调器测试切换到尚不存在的 AppClock**

在 `CheckInCoordinatorTest.kt`：

```kotlin
import com.wu.nearbycheckin.core.common.time.AppClock
```

将 `MutableAutomationClock` 改为：

```kotlin
private class MutableAppClock(
    var nowMillis: Long
) : AppClock {

    // 返回测试控制的当前时间。
    override fun nowMillis(): Long {
        return nowMillis
    }
}
```

并把测试中的构造调用同步改为 `MutableAppClock`。

- [ ] **Step 2: 运行测试并确认失败**

Run:

```powershell
$env:GRADLE_USER_HOME='D:\Softwares\GradleUserHome'
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.domain.coordinator.CheckInCoordinatorTest"
```

Expected: FAIL，提示 `Unresolved reference: AppClock`。

- [ ] **Step 3: 实现统一时钟**

`AppClock.kt`：

```kotlin
package com.wu.nearbycheckin.core.common.time

/**
 * 为领域和用例提供可替换的当前时间。
 */
fun interface AppClock {

    // 返回当前 Unix 时间戳，单位为毫秒。
    fun nowMillis(): Long
}

/**
 * 使用系统时间实现生产环境时钟。
 */
object SystemAppClock : AppClock {

    // 返回系统当前时间。
    override fun nowMillis(): Long {
        return System.currentTimeMillis()
    }
}
```

- [ ] **Step 4: 替换协调器内部时钟**

在 `CheckInCoordinator.kt`：

- 删除 `AutomationClock` 定义。
- 导入 `AppClock`。
- 将构造参数改为：

```kotlin
private val clock: AppClock
```

- [ ] **Step 5: 替换应用装配时钟**

在 `AppContainer.kt` 导入 `SystemAppClock`，并改为：

```kotlin
clock = SystemAppClock
```

- [ ] **Step 6: 验证协调器行为**

Run:

```powershell
$env:GRADLE_USER_HOME='D:\Softwares\GradleUserHome'
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.domain.coordinator.CheckInCoordinatorTest"
git diff --check
```

Expected: PASS。

- [ ] **Step 7: 提交检查点**

仅在哥哥显式授权提交后执行：

```powershell
git add core/common app/src/main/java/com/wu/nearbycheckin/domain/coordinator app/src/main/java/com/wu/nearbycheckin/app/AppContainer.kt app/src/test/java/com/wu/nearbycheckin/domain/coordinator
git commit -m "重构：提取领域统一时钟"
```

---

### Task 3: 迁移不可变领域模型到 core:model

**Files:**
- Move: `app/src/main/java/com/wu/nearbycheckin/domain/model/AutomationModels.kt` → `core/model/src/main/kotlin/com/wu/nearbycheckin/core/model/AutomationModels.kt`
- Move: `app/src/main/java/com/wu/nearbycheckin/domain/model/CheckInRecord.kt` → `core/model/src/main/kotlin/com/wu/nearbycheckin/core/model/CheckInRecord.kt`
- Move: `app/src/main/java/com/wu/nearbycheckin/domain/model/DualMapCheckInModels.kt` → `core/model/src/main/kotlin/com/wu/nearbycheckin/core/model/DualMapCheckInModels.kt`
- Modify: 所有导入 `com.wu.nearbycheckin.domain.model.*` 的生产和测试 Kotlin 文件

**Interfaces:**
- Produces: `com.wu.nearbycheckin.core.model.*`。
- Preserves: 全部数据类字段、枚举成员、默认值和行为。
- Removes: `com.wu.nearbycheckin.domain.model.*`。

- [ ] **Step 1: 先把一个模型消费者切换到新包**

将 `CandidateEvaluatorTest.kt` 的模型导入改为：

```kotlin
import com.wu.nearbycheckin.core.model.AutomationSnapshot
import com.wu.nearbycheckin.core.model.CheckInTransaction
import com.wu.nearbycheckin.core.model.DualMapState
import com.wu.nearbycheckin.core.model.DualMapTransactionStatus
import com.wu.nearbycheckin.core.model.NearbyPlaceCandidate
```

- [ ] **Step 2: 运行测试并确认新包尚不存在**

Run:

```powershell
$env:GRADLE_USER_HOME='D:\Softwares\GradleUserHome'
.\gradlew.bat :app:testDebugUnitTest --tests "com.wu.nearbycheckin.domain.evaluator.CandidateEvaluatorTest"
```

Expected: FAIL，提示 `Unresolved reference: core.model`。

- [ ] **Step 3: 移动模型文件并修改 package**

三个目标文件统一使用：

```kotlin
package com.wu.nearbycheckin.core.model
```

除 package 与同模块导入外，不修改任何字段、枚举、默认值或方法。

- [ ] **Step 4: 更新全部模型导入**

将生产与测试代码中的：

```kotlin
com.wu.nearbycheckin.domain.model
```

统一替换为：

```kotlin
com.wu.nearbycheckin.core.model
```

- [ ] **Step 5: 验证旧包引用已经清零**

Run:

```powershell
rg -n "com\\.wu\\.nearbycheckin\\.domain\\.model|package com\\.wu\\.nearbycheckin\\.domain\\.model" app core
```

Expected: 无输出。

- [ ] **Step 6: 验证模型模块和现有应用**

Run:

```powershell
$env:GRADLE_USER_HOME='D:\Softwares\GradleUserHome'
.\gradlew.bat :core:model:test :app:testDebugUnitTest :app:assembleDebug
git diff --check
```

Expected: PASS。

- [ ] **Step 7: 提交检查点**

仅在哥哥显式授权提交后执行：

```powershell
git add core/model app/src/main app/src/test
git commit -m "重构：迁移不可变领域模型"
```

---

### Task 4: 迁移候选规则与双地图状态机

**Files:**
- Move: `app/src/main/java/com/wu/nearbycheckin/domain/evaluator/CandidateEvaluator.kt` → `core/domain/src/main/kotlin/com/wu/nearbycheckin/core/domain/evaluator/CandidateEvaluator.kt`
- Move: `app/src/main/java/com/wu/nearbycheckin/domain/statemachine/DualMapStateMachine.kt` → `core/domain/src/main/kotlin/com/wu/nearbycheckin/core/domain/statemachine/DualMapStateMachine.kt`
- Move: `app/src/test/java/com/wu/nearbycheckin/domain/evaluator/CandidateEvaluatorTest.kt` → `core/domain/src/test/kotlin/com/wu/nearbycheckin/core/domain/evaluator/CandidateEvaluatorTest.kt`
- Move: `app/src/test/java/com/wu/nearbycheckin/domain/statemachine/DualMapStateMachineTest.kt` → `core/domain/src/test/kotlin/com/wu/nearbycheckin/core/domain/statemachine/DualMapStateMachineTest.kt`
- Modify: 所有 evaluator 与 statemachine 导入

**Interfaces:**
- Produces: `com.wu.nearbycheckin.core.domain.evaluator.*`。
- Produces: `com.wu.nearbycheckin.core.domain.statemachine.*`。
- Consumes: `:core:model`。
- Preserves: 当前策略默认值、事件、命令、效果和状态迁移。

- [ ] **Step 1: 先移动测试并切换 package**

测试 package 分别改为：

```kotlin
package com.wu.nearbycheckin.core.domain.evaluator
```

```kotlin
package com.wu.nearbycheckin.core.domain.statemachine
```

模型导入使用 `com.wu.nearbycheckin.core.model`。

- [ ] **Step 2: 运行 core:domain 测试并确认失败**

Run:

```powershell
$env:GRADLE_USER_HOME='D:\Softwares\GradleUserHome'
.\gradlew.bat :core:domain:test
```

Expected: FAIL，提示 `CandidateEvaluator` 和 `DualMapStateMachine` 不存在。

- [ ] **Step 3: 移动生产文件并切换 package**

目标 package：

```kotlin
package com.wu.nearbycheckin.core.domain.evaluator
```

```kotlin
package com.wu.nearbycheckin.core.domain.statemachine
```

除包名和模型导入外，不修改领域行为。

- [ ] **Step 4: 更新所有消费者导入**

生产和测试代码统一使用：

```kotlin
com.wu.nearbycheckin.core.domain.evaluator
com.wu.nearbycheckin.core.domain.statemachine
```

- [ ] **Step 5: 运行领域规则测试**

Run:

```powershell
$env:GRADLE_USER_HOME='D:\Softwares\GradleUserHome'
.\gradlew.bat :core:domain:test --tests "com.wu.nearbycheckin.core.domain.evaluator.CandidateEvaluatorTest"
.\gradlew.bat :core:domain:test --tests "com.wu.nearbycheckin.core.domain.statemachine.DualMapStateMachineTest"
```

Expected: 两条命令均 PASS。

- [ ] **Step 6: 运行应用回归**

Run:

```powershell
$env:GRADLE_USER_HOME='D:\Softwares\GradleUserHome'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
git diff --check
```

Expected: PASS。

- [ ] **Step 7: 提交检查点**

仅在哥哥显式授权提交后执行：

```powershell
git add core/domain app/src/main app/src/test
git commit -m "重构：迁移候选规则与双地图状态机"
```

---

### Task 5: 迁移 Repository 接口与事务协调器

**Files:**
- Move: `app/src/main/java/com/wu/nearbycheckin/domain/repository/CheckInTransactionRepository.kt` → `core/domain/src/main/kotlin/com/wu/nearbycheckin/core/domain/repository/CheckInTransactionRepository.kt`
- Move: `app/src/main/java/com/wu/nearbycheckin/domain/coordinator/CheckInCoordinator.kt` → `core/domain/src/main/kotlin/com/wu/nearbycheckin/core/domain/coordinator/CheckInCoordinator.kt`
- Move: `app/src/main/java/com/wu/nearbycheckin/domain/coordinator/CheckInCoordinatorSnapshotReducer.kt` → `core/domain/src/main/kotlin/com/wu/nearbycheckin/core/domain/coordinator/CheckInCoordinatorSnapshotReducer.kt`
- Move: `app/src/test/java/com/wu/nearbycheckin/domain/coordinator/CheckInCoordinatorTest.kt` → `core/domain/src/test/kotlin/com/wu/nearbycheckin/core/domain/coordinator/CheckInCoordinatorTest.kt`
- Create: `core/domain/src/test/kotlin/com/wu/nearbycheckin/core/domain/testing/TestCheckInTransactionRepository.kt`
- Modify: 所有 repository 与 coordinator 导入

**Interfaces:**
- Produces: `CheckInTransactionRepository`。
- Produces: `CheckInCoordinator`。
- Produces: 测试专用 `TestCheckInTransactionRepository`。
- Consumes: `AppClock`、领域模型、候选评价器和状态机。

- [ ] **Step 1: 移动协调器测试并切换包名**

使用：

```kotlin
package com.wu.nearbycheckin.core.domain.coordinator
```

并将导入切换到 `core.common`、`core.model`、`core.domain`。

- [ ] **Step 2: 抽取共享测试 Repository**

把当前 `CheckInCoordinatorTest.kt` 中
`FakeTransactionRepository` 的实现完整移动到
`TestCheckInTransactionRepository.kt`，类签名改为：

```kotlin
internal class TestCheckInTransactionRepository(
    initialSnapshot: AutomationSnapshot = AutomationSnapshot()
) : CheckInTransactionRepository
```

内部 `MutableStateFlow` 使用 `initialSnapshot` 初始化，其余领取、更新、诊断、分屏请求和清理逻辑保持不变。

- [ ] **Step 3: 运行 core:domain 测试并确认生产类缺失**

Run:

```powershell
$env:GRADLE_USER_HOME='D:\Softwares\GradleUserHome'
.\gradlew.bat :core:domain:test
```

Expected: FAIL，提示 Repository 或 Coordinator 新包不存在。

- [ ] **Step 4: 移动 Repository 与协调器**

目标 package：

```kotlin
com.wu.nearbycheckin.core.domain.repository
com.wu.nearbycheckin.core.domain.coordinator
```

`CheckInCoordinator` 使用：

```kotlin
import com.wu.nearbycheckin.core.common.time.AppClock
```

并保持构造签名：

```kotlin
class CheckInCoordinator(
    private val repository: CheckInTransactionRepository,
    private val candidateEvaluator: CandidateEvaluator,
    private val stateMachine: DualMapStateMachine,
    private val clock: AppClock
)
```

- [ ] **Step 5: 更新全部消费者导入**

更新 AppContainer、数据实现、平台 Service、Worker、ViewModel 和测试，
统一使用 `core.domain.repository` 与 `core.domain.coordinator`。

- [ ] **Step 6: 验证领域协调测试**

Run:

```powershell
$env:GRADLE_USER_HOME='D:\Softwares\GradleUserHome'
.\gradlew.bat :core:domain:test --tests "com.wu.nearbycheckin.core.domain.coordinator.CheckInCoordinatorTest"
```

Expected: PASS。

- [ ] **Step 7: 验证应用回归**

Run:

```powershell
$env:GRADLE_USER_HOME='D:\Softwares\GradleUserHome'
.\gradlew.bat :core:domain:test :app:testDebugUnitTest :app:assembleDebug
git diff --check
```

Expected: PASS。

- [ ] **Step 8: 提交检查点**

仅在哥哥显式授权提交后执行：

```powershell
git add core/domain app/src/main app/src/test
git commit -m "重构：迁移事务协调器与仓库契约"
```

---

### Task 6: 迁移平台网关与应用用例

**Files:**
- Move: `app/src/main/java/com/wu/nearbycheckin/application/gateway/CruisePlatformGateway.kt` → `core/domain/src/main/kotlin/com/wu/nearbycheckin/core/domain/gateway/CruisePlatformGateway.kt`
- Move: `app/src/main/java/com/wu/nearbycheckin/application/usecase/StartCruiseUseCase.kt` → `core/domain/src/main/kotlin/com/wu/nearbycheckin/core/domain/usecase/StartCruiseUseCase.kt`
- Move: `app/src/main/java/com/wu/nearbycheckin/application/usecase/StopCruiseUseCase.kt` → `core/domain/src/main/kotlin/com/wu/nearbycheckin/core/domain/usecase/StopCruiseUseCase.kt`
- Move: `app/src/main/java/com/wu/nearbycheckin/application/usecase/SubmitCandidateUseCase.kt` → `core/domain/src/main/kotlin/com/wu/nearbycheckin/core/domain/usecase/SubmitCandidateUseCase.kt`
- Move: `app/src/main/java/com/wu/nearbycheckin/application/usecase/QueryRecordsUseCase.kt` → `core/domain/src/main/kotlin/com/wu/nearbycheckin/core/domain/usecase/QueryRecordsUseCase.kt`
- Move: `app/src/main/java/com/wu/nearbycheckin/application/usecase/ClearRecordsUseCase.kt` → `core/domain/src/main/kotlin/com/wu/nearbycheckin/core/domain/usecase/ClearRecordsUseCase.kt`
- Create: `core/domain/src/main/kotlin/com/wu/nearbycheckin/core/domain/repository/LegacyDataCleaner.kt`
- Create: `core/domain/src/test/kotlin/com/wu/nearbycheckin/core/domain/usecase/StartCruiseUseCaseTest.kt`
- Create: `core/domain/src/test/kotlin/com/wu/nearbycheckin/core/domain/usecase/QueryRecordsUseCaseTest.kt`
- Create: `core/domain/src/test/kotlin/com/wu/nearbycheckin/core/domain/usecase/ClearRecordsUseCaseTest.kt`
- Create: `core/domain/src/test/kotlin/com/wu/nearbycheckin/core/domain/testing/FakeCruisePlatformGateway.kt`
- Modify: `app/src/main/java/com/wu/nearbycheckin/data/repository/LegacyCheckInDataMigrator.kt`
- Modify: `app/src/main/java/com/wu/nearbycheckin/app/AppContainer.kt`
- Modify: 所有 application gateway 与 usecase 导入

**Interfaces:**
- Produces: `CruisePlatformGateway` 与 `CruisePrecheck`。
- Produces: `LegacyDataCleaner`。
- Produces: 五个纯 Kotlin 用例。
- Consumes: `AppClock`、领域协调器和 Repository 接口。

- [ ] **Step 1: 写入旧数据清理端口**

`LegacyDataCleaner.kt`：

```kotlin
package com.wu.nearbycheckin.core.domain.repository

/**
 * 隔离清理用例与旧版 Android 数据实现。
 */
fun interface LegacyDataCleaner {

    // 清理旧版本兼容数据。
    fun clearLegacyData(): Boolean
}
```

- [ ] **Step 2: 先写启动用例的失败测试**

`StartCruiseUseCaseTest.kt` 至少包含：

```kotlin
package com.wu.nearbycheckin.core.domain.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import com.wu.nearbycheckin.core.common.time.AppClock
import com.wu.nearbycheckin.core.domain.coordinator.CheckInCoordinator
import com.wu.nearbycheckin.core.domain.evaluator.CandidateEvaluator
import com.wu.nearbycheckin.core.domain.statemachine.DualMapStateMachine
import com.wu.nearbycheckin.core.domain.testing.FakeCruisePlatformGateway
import com.wu.nearbycheckin.core.domain.testing.TestCheckInTransactionRepository
import com.wu.nearbycheckin.core.model.AccessibilityDiagnostics
import com.wu.nearbycheckin.core.model.AutomationSnapshot

class StartCruiseUseCaseTest {

    // 验证诊断新鲜时不会重复请求分屏。
    @Test
    fun doesNotRequestSplitWhenDiagnosticsAreFresh() {
        val nowMillis = 100_000L
        val repository = TestCheckInTransactionRepository(
            AutomationSnapshot(
                diagnostics = AccessibilityDiagnostics(
                    baiduReady = true,
                    amapReady = true,
                    lastUpdatedMillis = 99_000L
                )
            )
        )
        val platform = FakeCruisePlatformGateway()
        val clock = AppClock { nowMillis }
        val coordinator = CheckInCoordinator(
            repository = repository,
            candidateEvaluator = CandidateEvaluator(),
            stateMachine = DualMapStateMachine(),
            clock = clock
        )
        val useCase = StartCruiseUseCase(
            platformGateway = platform,
            coordinator = coordinator,
            repository = repository,
            clock = clock
        )

        val result = useCase()

        assertTrue(result is StartCruiseResult.Started)
        assertFalse(platform.autoSplitRequested)
        assertTrue(platform.serviceStarted)
    }
}
```

- [ ] **Step 3: 写入确定性日期查询测试**

`QueryRecordsUseCaseTest.kt` 使用固定 `AppClock`，
创建今天和前一天各一条记录，断言 `TODAY` 只返回今天记录，
`ALL` 按 `checkInTimeMillis` 倒序返回两条记录。

构造用例必须使用：

```kotlin
QueryRecordsUseCase(
    repository = repository,
    clock = AppClock { fixedNowMillis },
    zoneId = ZoneId.of("Asia/Shanghai")
)
```

- [ ] **Step 4: 写入清理确认与旧数据边界测试**

`ClearRecordsUseCaseTest.kt` 至少验证：

```kotlin
// 验证未确认时不清理新旧数据。
@Test
fun doesNotClearWithoutConfirmation()

// 验证 ALL 同时清理新仓库和旧数据实现。
@Test
fun clearsNewAndLegacyDataWhenConfirmed()
```

使用：

```kotlin
val legacyCleaner = RecordingLegacyDataCleaner()
```

并断言未确认时 `legacyCleaner.called == false`，
确认 `ALL` 后 `repository.currentSnapshot() == AutomationSnapshot()` 且
`legacyCleaner.called == true`。

- [ ] **Step 5: 运行测试并确认用例新包尚不存在**

Run:

```powershell
$env:GRADLE_USER_HOME='D:\Softwares\GradleUserHome'
.\gradlew.bat :core:domain:test
```

Expected: FAIL，提示 usecase 或 gateway 新包不存在。

- [ ] **Step 6: 迁移平台网关和五个用例**

目标 package：

```kotlin
com.wu.nearbycheckin.core.domain.gateway
com.wu.nearbycheckin.core.domain.usecase
```

`StartCruiseUseCase` 构造函数增加：

```kotlin
private val clock: AppClock
```

诊断新鲜度统一使用：

```kotlin
clock.nowMillis() - diagnostics.lastUpdatedMillis <=
        DIAGNOSTICS_STALE_MILLIS
```

`QueryRecordsUseCase` 构造函数改为：

```kotlin
class QueryRecordsUseCase(
    private val repository: CheckInTransactionRepository,
    private val clock: AppClock,
    private val zoneId: ZoneId = ZoneId.systemDefault()
)
```

今天日期改为：

```kotlin
val today = Instant.ofEpochMilli(clock.nowMillis())
    .atZone(zoneId)
    .toLocalDate()
```

记录日期使用同一个 `zoneId` 计算。

`ClearRecordsUseCase` 构造参数改为：

```kotlin
private val legacyDataCleaner: LegacyDataCleaner
```

并调用：

```kotlin
repository.clearAll() &&
        legacyDataCleaner.clearLegacyData()
```

- [ ] **Step 7: 实现测试平台网关**

`FakeCruisePlatformGateway.kt`：

```kotlin
package com.wu.nearbycheckin.core.domain.testing

import com.wu.nearbycheckin.core.domain.gateway.CruisePlatformGateway
import com.wu.nearbycheckin.core.domain.gateway.CruisePrecheck

internal class FakeCruisePlatformGateway(
    var precheck: CruisePrecheck = CruisePrecheck(
        privacyConsentGranted = true,
        locationPermissionGranted = true,
        notificationPermissionGranted = true,
        overlayPermissionGranted = true,
        accessibilityServiceEnabled = true,
        baiduInstalled = true,
        amapInstalled = true
    ),
    var mapSdkInitialized: Boolean = true
) : CruisePlatformGateway {

    var serviceStarted = false
    var serviceStopped = false
    var autoSplitRequested = false

    // 返回测试配置的平台预检。
    override fun inspectPrecheck(): CruisePrecheck = precheck

    // 测试中直接记录隐私授权。
    override fun grantPrivacyConsent() {
        precheck = precheck.copy(privacyConsentGranted = true)
    }

    // 返回测试配置的 SDK 初始化结果。
    override fun initializeMapSdk(): Boolean = mapSdkInitialized

    // 记录服务启动。
    override fun startCruiseService() {
        serviceStarted = true
    }

    // 记录服务停止。
    override fun stopCruiseService() {
        serviceStopped = true
    }

    // 记录分屏请求。
    override fun requestAutoSplitLaunch() {
        autoSplitRequested = true
    }

    // 测试实现无需保存历史分屏请求。
    override fun clearPendingAutoSplitLaunchRequest() = Unit
}
```

- [ ] **Step 8: 让旧数据迁移器实现领域端口**

`LegacyCheckInDataMigrator` 增加：

```kotlin
import com.wu.nearbycheckin.core.domain.repository.LegacyDataCleaner
```

类声明改为：

```kotlin
class LegacyCheckInDataMigrator(
    context: Context
) : LegacyDataCleaner
```

方法改为：

```kotlin
override fun clearLegacyData(): Boolean
```

- [ ] **Step 9: 更新应用装配**

`AppContainer` 只创建一个：

```kotlin
private val appClock = SystemAppClock
```

并把 `appClock` 传给 `CheckInCoordinator`、`StartCruiseUseCase` 和
`QueryRecordsUseCase`。

- [ ] **Step 10: 更新全部消费者导入**

MainViewModel、CruiseDiscoveryController、AndroidCruisePlatformGateway、
AppContainer 和所有测试统一使用：

```kotlin
com.wu.nearbycheckin.core.domain.gateway
com.wu.nearbycheckin.core.domain.usecase
```

- [ ] **Step 11: 运行领域和应用回归测试**

Run:

```powershell
$env:GRADLE_USER_HOME='D:\Softwares\GradleUserHome'
.\gradlew.bat :core:domain:test
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
git diff --check
```

Expected: 全部 PASS。

- [ ] **Step 12: 检查旧 application/domain 包残留**

Run:

```powershell
rg -n "package com\\.wu\\.nearbycheckin\\.(application|domain)|import com\\.wu\\.nearbycheckin\\.(application|domain)" app core
```

Expected: 无输出。

- [ ] **Step 13: 提交检查点**

仅在哥哥显式授权提交后执行：

```powershell
git add core/domain app/src/main app/src/test
git commit -m "重构：迁移平台网关与应用用例"
```

---

### Task 7: 第一阶段全量验证与架构文档

**Files:**
- Create: `docs/architecture/module-boundaries.md`
- Create: `docs/adr/0001-multi-module-clean-architecture.md`
- Modify: `README.md`

**Interfaces:**
- Documents: 当前已经落地的模块边界和禁止依赖。
- Documents: 选择渐进式多模块架构的背景、决策和后果。
- Verifies: 第一阶段没有行为回归。

- [ ] **Step 1: 写入模块边界文档**

文档必须准确列出：

```text
:app → :core:common + :core:model + :core:domain
:core:domain → :core:common + :core:model
:core:common → 无项目模块依赖
:core:model → 无项目模块依赖
```

并明确：

- `:core:common`、`:core:model`、`:core:domain` 禁止 Android SDK。
- `:app` 暂时保留 Android、数据与平台实现。
- 第二阶段才引入 Room 和 `:core:data`。

- [ ] **Step 2: 写入 ADR-0001**

ADR 使用“背景、候选方案、决策、理由、正面后果、负面后果、验证方式”结构，
记录选择渐进式多模块而不是单模块加固或大型插件化平台。

- [ ] **Step 3: 更新 README**

README 当前状态改为：

- 企业级重构第一阶段。
- 已落地 `build-logic` 与三个核心模块。
- 当前数据实现和 Android 功能仍在 `:app`。
- 后续阶段为 Room/DataStore 和功能模块拆分。

- [ ] **Step 4: 运行第一阶段完整门禁**

Run:

```powershell
$env:GRADLE_USER_HOME='D:\Softwares\GradleUserHome'
.\gradlew.bat clean
.\gradlew.bat :core:common:test :core:model:test :core:domain:test
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
git diff --check
```

Expected:

- 所有 Gradle 任务 `BUILD SUCCESSFUL`。
- Lint 0 error。
- `git diff --check` 无输出。

- [ ] **Step 5: 检查模块边界和 Android 引用**

Run:

```powershell
rg -n "^import android\\.|^import androidx\\." core/common core/model core/domain
rg -n "com\\.wu\\.nearbycheckin\\.(application|domain)" app core
```

Expected: 两条命令均无输出。

- [ ] **Step 6: 检查工作区范围**

Run:

```powershell
git status --short
```

Expected:

- 只包含本计划列出的构建、核心模块、导入、测试和文档变更。
- 不包含 `local.properties`、密钥、构建产物或 `.superpowers`。

- [ ] **Step 7: 提交检查点**

仅在哥哥显式授权提交后执行：

```powershell
git add README.md docs/architecture docs/adr
git commit -m "文档：记录第一阶段模块边界"
```

---

## 第一阶段验收条件

1. `build-logic` 可被根工程稳定加载。
2. `:core:common`、`:core:model`、`:core:domain` 均可独立测试。
3. 领域模型、规则、状态机、协调器、Repository 接口和用例不再位于 `:app`。
4. 三个核心模块没有 Android 或 AndroidX 导入。
5. 应用仍可编译，现有单元测试和 Lint 通过。
6. 时间相关用例使用 `AppClock`，日期测试不依赖执行当天。
7. `ClearRecordsUseCase` 不再依赖 Android 数据实现。
8. 模块边界和 ADR 文档与实际代码一致。
9. 未执行未经授权的 Git Commit。
