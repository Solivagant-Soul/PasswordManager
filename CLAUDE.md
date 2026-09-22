# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目当前状态

需求/设计阶段已完成，代码阶段已启动：Android 工程位于仓库根目录，`DesignDocument/` 为设计文档目录。

- 技术栈、数据模型与任务拆解见 `DesignDocument/技术方案.md`（任务列表在 §8，当前进度：T01 已完成）。
- 环境基线：JDK 17；minSdk 26 / compileSdk 35 / AGP 8.7.3 / Gradle 8.10.2；Android SDK 位于 `F:/Python_file/ai_coding/tools/android-sdk`（经 `local.properties` 指向，不入库）。
- 网络约定：依赖解析走阿里云镜像（`settings.gradle.kts`），wrapper 下载源为腾讯镜像（`gradle-wrapper.properties`）——本机网络对 maven.google.com 与 services.gradle.org 大文件下载不可达。

## 构建与验证命令

```bash
./gradlew :app:assembleDebug       # 构建 Debug APK
./gradlew :app:testDebugUnitTest   # JVM 单元测试
./gradlew :app:lintDebug           # Android Lint
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug   # 任务验收全套（T01 同款）
```

## 权威文档（唯一事实来源）

- `DesignDocument/密码管理App_需求规格说明书.md`（当前 v0.4）—— 产品需求的唯一规范，开发与验收均以此为准。
- `DesignDocument/features/*.feature` —— Gherkin 验收用例（对应 FR-1 ~ FR-12，覆盖正向/负向/异常/边界）。
- `DesignDocument/openapi.yaml` —— 未来后端预留 OpenAPI 3.0 契约（当前纯本地、无服务端，仅作预留设计）。
- `DesignDocument/架构视图.md` —— Mermaid 架构视图（系统上下文图、组件图、解锁与找回流程图）。
- `DesignDocument/技术方案.md`（当前 v0.3）—— 技术选型、分层架构、数据模型、内部接口、任务拆解、风险与架构决策记录。
- `DesignDocument/技术方案评审意见.md` —— 技术方案评审记录（含 B1/S1/S2/S3 等问题的处置）。

文档结构要点（便于快速定位）：

- §4 功能需求清单（FR-1 ~ FR-12）
- §5 场景枚举（正向/负向/异常/边界）
- §6 输入限制与数据校验规则
- §7 权限校验点
- §8 业务规则与约束（BR-1 ~ BR-10）
- §9 非功能需求（性能/安全/合规/可用性）
- §10 验收标准（Gherkin Given-When-Then）
- §13 决策记录（已闭环的历史待确认项）

## 已定的产品范围（请勿在无用户确认下更改）

这些是需求阶段敲定的结论，后续实现应遵循：

- **形态**：移动端**仅 Android** 原生 App，个人使用。
- **存储**：纯本地存储，无云同步、无登录、无账号体系，**不预留第三方登录接口**。
- **认证**：主密码为根凭证 + 生物识别（指纹/面容）快速解锁；连续输错主密码 5 次锁定 1 分钟。
- **找回**：忘记主密码凭 3 道密保问题全部答对 → **重置**主密码（原主密码本身不可取回）。
- **功能范围**：条目增删改查、密码生成器、分类/标签/搜索、自动填充、TOTP 动态验证码、加密导入导出/备份。
- **关键边界**：条目上限 5000、密码必填且非空、主密码 ≥8 位且至少两类字符、剪贴板 30 秒自动清理、后台 1 分钟自动锁定、删除无回收站。

## 约定

- 每进行一次修改，就提交一次本地 git（`git add` + `git commit`），不需要推送远程。
- 设计阶段相关产物（需求文档、feature、API 契约等）统一存放在 `DesignDocument/` 目录下。
- 需求文档是规范：任何需求变更都必须同步更新该文档，并在 §14 变更记录中追加一行（含版本号、日期、变更内容、确认人）。
- 文档内所有验收标准为 Gherkin 格式、所有不确定项需标注 `[待确认]`；两者均为该文档的既定风格，改动时保持一致。