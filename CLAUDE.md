# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目当前状态

需求/设计阶段已完成，代码阶段进行中：Android 工程位于仓库根目录，`DesignDocument/` 为设计文档目录。

- 技术栈、数据模型与任务拆解见 `DesignDocument/技术方案.md`（任务列表在 §8，里程碑与批次划分见 §9）。
- **当前进度：M0–M2 + V1 成品冲刺（T01–T19）+ V2 功能增强（T32–T47）+ V2.1 UI 重做（T48–T57）均已交付（代码已入库）**。
  - V1 = 补齐 :app 侧接线（FR-8 自动锁定、FR-12 密保找回、P-07 改主密码、FR-2 生物识别）+ 生成器 + 自用签名 APK，见 §9.3。
  - V2 = 设计稿截图的新增需求：分类/收藏/条目类型、安全中心、生成器强度、四标签导航、设置项。技术设计见 `技术方案.md` §10，处置依据见 `DesignDocument/功能点提取与处置清单.md`。
  - V2.1 = 依据 `DesignDocument/V2.1-UI重做设计.md` 的 UI 重做：删除 FR-15 条目类型（编辑页与列表统一用分类）、生成器长度上界收窄为 32、设置页拆为主页 + 五个子页、编辑页生成改零弹窗直填、UI 换为自建主题 + 公共组件包。
- **⚠️ V2.1 尚未完成装机验收**：`:app` 无 `src/androidTest`，六屏 UI 重做**无 Compose UI 测试覆盖**；`V2.1-UI重做设计.md` §8 的 14 条走查清单目前只完成 1 条（F2 设置页可滚到底）。详见 `技术方案.md` §11 交付验证声明——**不要仅凭「编译通过」就认为 UI 改动已验收**。
- **已知待修缺口：无**。此前记载的「列表非响应式（`search()` 用 `.first()`）」「全项目无 `BackHandler`」两条均为 v0.9 时期的历史描述，**与代码不符**，勿再照抄：
  - 列表响应式：`EntryRepositoryImpl.search` 早已返回 `Flow`，底层走 Room `observe()` / `observeByTag()`，`VaultViewModel.applyFilters()` 在 `collect`（`Daos.kt` 的注释「此前 VM 用 `search(...).first()` 一次性取值」就是这段历史的记录）。全仓生产代码中唯一的 `.first()` 在 `DefaultSecurityScanner.kt`（一次性安全扫描，此处正确）与 `EntryType.kt`（字符串处理）。
  - 返回键栈：`AppNav.kt` 有 `BackHandler(enabled = backStack.size > 1)`（T49 引入）。
  - 剩余的「待办」性质事项记录在 `技术方案.md` §11 与 `V2.1-UI重做设计.md` §10，不在 CLAUDE.md 维护。
- **交付形态：个人自用 APK**（不上应用商店，故无隐私政策与商店合规）。自签证书已生成于**仓库外** `F:/Python_file/ai_coding/tools/keystore/`（`vault-release.jks` + `credentials.properties`），出包命令见下方「构建与验证命令」。证书口令**不入库、不外传**；丢了它就无法再给本 App 出可覆盖升级的包，只能卸载重装。
- 环境基线：JDK 17；minSdk 26 / compileSdk 35 / AGP 8.7.3 / Gradle 8.10.2；Android SDK 位于 `F:/Python_file/ai_coding/tools/android-sdk`（经 `local.properties` 指向，不入库）。
- 网络约定：依赖解析走阿里云镜像（`settings.gradle.kts`），wrapper 下载源为腾讯镜像（`gradle-wrapper.properties`）——本机网络对 maven.google.com 与 services.gradle.org 大文件下载不可达。

## 构建与验证命令

```bash
# 阶段验收：构建 + 全部 JVM 单测 + Lint
./gradlew :app:assembleDebug :app:testDebugUnitTest :data:testDebugUnitTest \
          :domain:test :core:test :platform:testDebugUnitTest :app:lintDebug

# 仪器测试（需模拟器/真机；SQLCipher 为 native 库，JVM 单测无法覆盖）
./gradlew :data:connectedDebugAndroidTest :platform:connectedDebugAndroidTest

# 出可安装 APK（自签证书在仓库外，需先注入凭据）
set -a; . <(grep -v '^#' "F:/Python_file/ai_coding/tools/keystore/credentials.properties"); set +a
./gradlew :app:assembleRelease
# 验证签名与 debuggable 标志
"F:/Python_file/ai_coding/tools/android-sdk/build-tools/35.0.0/apksigner.bat" verify --print-certs app/build/outputs/apk/release/app-release.apk
"F:/Python_file/ai_coding/tools/android-sdk/build-tools/35.0.0/aapt2.exe" dump badging app/build/outputs/apk/release/app-release.apk | grep -i debuggable  # 无输出 = 不可调试
```

## 工程约定

- **签名材料不入库**：`*.jks` / `*.keystore` / `credentials.properties` 已在 `.gitignore`，
  且实际存放于仓库外；改签名配置时不要把它们拷进仓库。
- **源根只用 `src/{main,test,androidTest}/kotlin`**：历史上 `java/` 与 `kotlin/` 双源根并存，导致同包同名类被编译两次、KSP 重复生成 Hilt 注入器（`:app:kspDebugKotlin` FileAlreadyExistsException）。
- **androidTest 禁用反引号方法名**：`minSdk 26` 对应 DEX 038，不接受 SimpleName 中的空格，方法名必须 camelCase。JVM 单测（`src/test`）不受此限。
- **数据层自持调度器**：Argon2 派生走 `Dispatchers.Default`、SQLCipher 与 Room 走 `Dispatchers.IO`（均在用例/仓库内部 `withContext`），调用方（ViewModel）不应选择调度器。
- **Room 迁移**：`data/schemas/` 已导出并入库，且经 `sourceSets.androidTest.assets.srcDir` 挂载供 `MigrationTestHelper` 读取；改 schema 必须同时提交新 JSON 与迁移测试。
- **设备走查**：App 开了 `FLAG_SECURE`，`screencap` 截出来是全黑（符合预期），
  故 UI 验证改用 `adb shell uiautomator dump` 读无障碍树；用 `adb input` 输注时
  软键盘会顶起布局，**每次操作前重新 dump 取坐标**，否则会点错字段。

## 权威文档（唯一事实来源）

- `DesignDocument/密码管理App_需求规格说明书.md`（§14 变更记录当前至 v0.11；文首「文档版本」字段未同步维护）—— 产品需求的唯一规范，开发与验收均以此为准。
- `DesignDocument/features/*.feature` —— Gherkin 验收用例（对应 FR-1 ~ FR-12，覆盖正向/负向/异常/边界）。
- `DesignDocument/openapi.yaml` —— 未来后端预留 OpenAPI 3.0 契约（当前纯本地、无服务端，仅作预留设计）。
- `DesignDocument/架构视图.md` —— Mermaid 架构视图（系统上下文图、组件图、解锁与找回流程图）。
- `DesignDocument/技术方案.md`（含 §9 批次划分与 §9.3 v1 交付记录、§11 V2.1 交付记录与验证边界）—— 技术选型、分层架构、数据模型、内部接口、任务拆解、风险与架构决策记录。
- `DesignDocument/V2.1-UI重做设计.md` —— V2.1 UI 重做的产品决策、视觉体系、各页改造与 §8 装机走查清单、§10 实施实况风险表。
- `DesignDocument/技术方案评审意见.md` —— 技术方案评审记录（含 B1/S1/S2/S3 等问题的处置）。
- `DesignDocument/M1安全评审报告.md` —— M1 安全评审门禁（S-1 已修复；G-A~G-G 遗留项）。
- `DesignDocument/遗留项与决策记录.md` —— 各里程碑遗留问题的处置状态与归属里程碑（含推迟理由）。
- `DesignDocument/功能点提取与处置清单.md` —— 设计稿截图的功能点提取、新增/冲突分类与 C1–C7 收口结论。

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
- **功能范围**：条目增删改查、密码生成器、分类/标签/搜索、加密导入导出/备份。
- **交付批次（2026-09-24 用户拍板，v0.10 更新）**：**TOTP 动态码、自动填充、加密备份导出/导入、root 检测、无障碍与文案外置，属二期**；**分类与标签管理已拉回一期**（设计稿的 chips 筛选依赖分类，标签存储链路 v1 已就绪）。
- **已彻底删除的需求（v0.10，不再延期）**：CSV 导入（T28）、商店发布准备（T31 发布部分）。成品为个人自用 APK，不上架。
  需求与 Gherkin 场景均保留（`@phase-2` 标签），一期只交付其相关基础设施；
  预留设计与约束见 `技术方案.md` §9.2——**改数据模型或备份容器前先读该节**。
- **关键边界**：条目上限 5000、密码必填且非空、主密码 ≥8 位且至少两类字符、剪贴板 30 秒自动清理、后台 1 分钟自动锁定、删除无回收站。

## 约定

- 每进行一次修改，就提交一次本地 git（`git add` + `git commit`），不需要推送远程。
- 设计阶段相关产物（需求文档、feature、API 契约等）统一存放在 `DesignDocument/` 目录下。
- 需求文档是规范：任何需求变更都必须同步更新该文档，并在 §14 变更记录中追加一行（含版本号、日期、变更内容、确认人）。
- 文档内所有验收标准为 Gherkin 格式、所有不确定项需标注 `[待确认]`；两者均为该文档的既定风格，改动时保持一致。