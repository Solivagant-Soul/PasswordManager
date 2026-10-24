# 密码管理 · PasswordManager

一个纯本地的 Android 密码管理器：密码库只存在设备上，不联网、无账号、无云同步，主密码是唯一的根凭证。

- 平台：Android 8.0（API 26）及以上
- 当前版本：`versionName` 2.0.0
- 形态：个人自用 APK，不上架应用商店

> 需求与设计文档在 [`DesignDocument/`](DesignDocument/)，是需求与架构的唯一事实来源；[`CLAUDE.md`](CLAUDE.md) 记录工程约定与当前交付状态。

## 功能

**密码库**
- 条目增删改查、搜索、分类与收藏筛选，条目上限 5000
- 编辑页「⚡ 生成」零弹窗直填，生成器页与编辑页共用同一套全局参数
- 详情页复制字段，剪贴板 30 秒后自动清空
- 安全中心：自动扫描弱密码与重复使用的密码（按摘要分组比对，不在内存中留存明文清单）

**解锁与会话**
- 主密码解锁：至少 8 位且包含两类以上字符
- 生物识别快捷解锁（`androidx.biometric`，API 26+）
- 密保找回：3 道密保问题全部答对 → 重置主密码（原密码不可取回）
- 修改主密码：重新包裹 DEK 并重写 Header
- 连续 5 次输错锁定 1 分钟
- 自动锁定档位：立即 / 30 秒 / 1 分钟 / 5 分钟

**系统级自动填充（v1.2.0）**
- 实现 `AutofillService`，按 WebView 域名与目标包名打分排序候选
- 候选列表不含密码；用户选中某条后才拉起独立认证页做二次认证，单条取用明文
- 无匹配时明确提示，而不是猜一条填入
- 本 App 自身被排除为填充目标（`importantForAutofill="noExcludeDescendants"`）

**加密备份（v1.3.0）**
- 导出/导入经 SAF，二进制容器（magic `PMBK`，容器版本 1）+ JSON 载荷
- 整个容器 AES-256-GCM 加密，帧内显式长度用于截断检测
- 备份密钥由主密码经「域分离盐」再派生一次，不与解锁密钥共用
- ⚠️ 改主密码或密保重置会重新生成 `saltKdf`，此前的备份将永久无法解开（设置页有明确提示）

**其他**
- 可关闭的 `FLAG_SECURE`（默认禁止截屏与录屏）
- root / 越狱环境检测（v2.0.0，仅提示，不阻断使用，也不进入解锁路径）
- 单 Activity + Compose 自绘导航，底部四标签

## 安全设计

| 环节 | 做法 |
| --- | --- |
| 口令派生 | Argon2id（BouncyCastle 纯 Java 实现），默认 3 迭代 / 64 MiB / 4 并行 |
| 分层密钥 | 主密码 → KEK → 包裹随机 DEK；DEK 加密实际数据 |
| 字段加密 | AES-256-GCM，nonce 12 字节每次随机，密文布局 `nonce ‖ ciphertext ‖ tag` |
| 数据库 | Room + SQLCipher 整库加密（native 库，故需仪器测试覆盖） |
| Header | 独立存储，带 HMAC 校验；盐与 KDF 参数随 Header 持久化 |
| 生物识别 | DEK 由 Android Keystore 密钥包裹，`setUserAuthenticationRequired` + auth-per-use |
| 备份密钥 | `saltKdf ‖ 0x00 ‖ "pmw-backup-v1"` 域分离后再跑一次 Argon2id |
| 密钥清零 | 中间字节数组用后立即 `fill(0)`；`CharArray → String` 的 JVM 中间副本无法清零，属已记录的局限 |

## 技术栈

Kotlin 2.0.21 · JDK 17 · AGP 8.7.3 · Gradle 8.10.2 · compileSdk/targetSdk 35 · minSdk 26

- **UI**：Jetpack Compose（BOM 2024.12.01）+ Material 3
- **DI**：Hilt 2.52（KSP）
- **存储**：Room 2.6.1 + SQLCipher 4.6.1、DataStore Preferences 1.1.1
- **加密**：BouncyCastle 1.78.1（Argon2id）+ JCA（AES-GCM）
- **生物识别**：androidx.biometric 1.1.0
- **测试**：JUnit4、MockK、Turbine、coroutines-test、Robolectric

## 模块结构

```
app         Compose UI、导航、ViewModel、AutofillService、应用入口
 ├─ data        Room/SQLCipher、Argon2、Vault Header、备份、安全扫描
 ├─ platform    Keystore、生物识别、剪贴板、root 环境探测
 ├─ domain      纯 Kotlin 领域模型与策略（零 Android 依赖）
 └─ core        通用结果类型与基础件
```

依赖方向单向：`app → data / platform → domain → core`。方向由各模块的架构边界测试（`DataBoundaryTest`、`PlatformBoundaryTest`）与独立 JVM 单测守住。

## 构建与运行

前置：JDK 17、Android SDK 35。SDK 路径写入 `local.properties` 的 `sdk.dir`（该文件不入库）。

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows 下用 `gradlew.bat`。

出 release 包需要自签证书，凭据不入库，通过 Gradle 属性（`-P...`，优先）或环境变量注入：

```bash
export VAULT_KEYSTORE=/path/to/vault-release.jks
export VAULT_KEYSTORE_PASSWORD=...
export VAULT_KEY_ALIAS=...
export VAULT_KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

未提供 `VAULT_KEYSTORE` 时会回落到 debug 签名 —— 包能装，但 APK 可调试，只适合本机试装。验签与 `debuggable` 标志：

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
aapt2 dump badging app/build/outputs/apk/release/app-release.apk | grep -i debuggable   # 无输出 = 不可调试
```

## 测试与 CI

```bash
# 全模块构建 + Lint + 全部 JVM 单测
./gradlew :app:assembleDebug :app:lintDebug \
          :app:testDebugUnitTest :core:test :domain:test \
          :data:testDebugUnitTest :platform:testDebugUnitTest

# 仪器测试：需模拟器/真机（SQLCipher 为 native 库，JVM 单测覆盖不到）
./gradlew :data:connectedDebugAndroidTest :platform:connectedDebugAndroidTest
```

`.github/workflows/android-ci.yml` 在每次 push / PR 上执行全模块构建、Lint 与 JVM 单测。

## 项目约定（摘要）

- 签名材料（`*.jks`、`*.keystore`、`credentials.properties`）一律不入库，实际存放在仓库外
- 源根只用 `src/{main,test,androidTest}/kotlin`；`androidTest` 方法名必须 camelCase（minSdk 26 对应 DEX 038，不接受含空格的方法名）
- 数据层/平台层自持调度器：Argon2 走 `Dispatchers.Default`，SQLCipher 与 Room 走 `Dispatchers.IO`
- 改 Room schema 必须同时提交 `data/schemas/*.json` 与迁移测试
- 依赖解析走阿里云镜像、Gradle wrapper 走腾讯镜像（开发机对 maven.google.com 与 services.gradle.org 大文件不可达）

## 已知边界

- 个人自用，不上架：无隐私政策、无商店合规、无多用户与云同步
- `versionCode` 刻意固定为 1：设备上已装的包就是 vc=1，抬高会让 `adb install -r` 拒绝覆盖
- V2.1 的六屏 UI 重做尚无 Compose UI 测试覆盖，装机走查清单未全部完成（见 `DesignDocument/V2.1-UI重做设计.md` §8、§10）
- root 检测只提示不阻断；API 30+ 未声明 `<queries>` 时可能探测不到已安装的 root 管理应用，属已记录局限
- TOTP 动态码为二期功能，详情页入口当前置灰
- CSV 导入与商店发布已明确删除，不再排期

## 文档索引

| 文档 | 内容 |
| --- | --- |
| `DesignDocument/密码管理App_需求规格说明书.md` | 需求规范：FR-1~FR-12、业务规则、校验规则、Gherkin 验收标准 |
| `DesignDocument/技术方案.md` | 技术选型、分层架构、数据模型、任务拆解、交付验证声明 |
| `DesignDocument/features/*.feature` | Gherkin 验收用例（正向/负向/异常/边界） |
| `DesignDocument/架构视图.md` | Mermaid 系统上下文图、组件图、解锁与找回流程图 |
| `DesignDocument/M1安全评审报告.md` | M1 安全评审门禁与遗留项 |
| `DesignDocument/遗留项与决策记录.md` | 各里程碑遗留问题的处置状态 |
| `DesignDocument/V2.1-UI重做设计.md` | UI 重做的产品决策、视觉体系与走查清单 |
| `CLAUDE.md` | 工程约定、环境基线、构建与验证命令 |

## 许可

仓库未附开源许可证，版权保留。个人项目，暂不授权他人分发或商用。
