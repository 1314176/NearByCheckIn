# ADR-0001：采用渐进式多模块 Clean Architecture

- 状态：已接受
- 日期：2026-07-26

## 背景

项目已经具备 presentation、application、domain、data 和 platform 分层，但所有代码仍位于 `:app`。包级约定无法阻止 Android 实现反向侵入领域逻辑，也不利于 JVM 快速测试、构建治理和后续数据层演进。

## 候选方案

1. 保持单模块，只通过包名和代码评审维持边界。
2. 一次性拆成完整的数据、功能、UI、质量和性能模块。
3. 按稳定边界逐阶段拆分，第一阶段只建立构建逻辑和纯 Kotlin 核心模块。

## 决策

选择方案 3。第一阶段建立 `build-logic`、`:core:common`、`:core:model` 和 `:core:domain`，将模型、规则、状态机、协调器、Repository 端口、平台网关端口和用例迁出 `:app`。Android UI、Service、地图能力和现有数据实现暂时保留在 `:app`。

## 理由

- Gradle 模块依赖能在编译期强制执行方向约束。
- 纯 Kotlin 核心可以在 JVM 上快速、确定地测试。
- 渐进迁移控制单次改动范围，便于定位回归和恢复。
- 保留现有数据实现，避免架构迁移与持久化迁移同时发生。

## 正面后果

- Android SDK 无法直接进入核心模块。
- 领域规则、状态机和用例获得独立测试入口。
- 构建配置由 Convention Plugin 统一。
- 后续 Room、DataStore、Hilt 和功能模块拆分具有稳定边界。

## 负面后果

- 第一阶段的 `:app` 仍然同时承载 UI、平台和数据实现。
- 模块数量和 Gradle 配置复杂度增加。
- 在后续阶段完成前，整体目录尚未达到最终目标形态。

## 验证方式

- 独立执行三个核心模块的 JVM 测试。
- 执行应用单元测试、Android Lint 和 Debug APK 构建。
- 检查核心模块不存在 `android.*`、`androidx.*` 导入。
- 检查旧 `application`、`domain` 包引用已经清零。
- 使用 `git diff --check` 和 Kotlin 文件头精确格式检查作为提交门禁。
