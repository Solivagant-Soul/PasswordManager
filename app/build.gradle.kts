plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 自签 keystore：优先 Gradle 属性（-PVAULT_KEYSTORE=…），其次环境变量。
// 口令同理。两者都**不入库**，本机用 local.properties 的 sdk.dir 之外另配或用环境变量。
fun vaultKeystoreValue(name: String): String? =
    providers.gradleProperty(name).orNull ?: providers.environmentVariable(name).orNull

val vaultKeystorePath: String? = vaultKeystoreValue("VAULT_KEYSTORE")?.takeIf { file(it).exists() }

android {
    namespace = "com.zz.passwordmanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zz.passwordmanager"
        minSdk = 26        // D1 已定：Android 8.0+
        targetSdk = 35     // Q3 决策：保守组合，与 AGP 8.7.3 成对
        versionCode = 1
        // versionCode 刻意留在 1：设备上已装的包就是 versionCode 1，
        // 跟着 versionName 一起抬到 2 会让 `adb install -r` 拒绝升级，只能卸载重装。
        versionName = "1.1.0"
    }

    signingConfigs {
        // v1 个人自用签名（2026-09-24 决策：不上应用商店，故只求「可安装 + 不可调试」）。
        // keystore 路径与口令经 Gradle 属性或环境变量传入，**不入库**；
        // 未配置时下方 buildTypes 回落到 debug 签名，以免阻塞首次出包。
        create("selfSigned") {
            val storePath = vaultKeystorePath
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = vaultKeystoreValue("VAULT_KEYSTORE_PASSWORD")
                keyAlias = vaultKeystoreValue("VAULT_KEY_ALIAS")
                keyPassword = vaultKeystoreValue("VAULT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 暂不开 R8：Compose/Hilt/序列化规则尚未跑通，开混淆会在 v1 引入无法排查的崩溃
            isMinifyEnabled = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (vaultKeystorePath != null) {
                signingConfigs.getByName("selfSigned")
            } else {
                // 无自签 keystore 时用 debug 签名保证可安装；此时 APK 是 debuggable 的，
                // 正式自用请务必配置 VAULT_KEYSTORE 后重新出包
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // 分层模块（T02）：:core 通用件；:domain 领域层（纯 Kotlin）；:data/:platform 为实现层
    implementation(project(":core"))
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":platform"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // 生物识别快捷解锁（V1-4）：BiometricPrompt 需要 FragmentActivity；
    // :platform 的 implementation 依赖不传递，:app 必须自己声明
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)

    // Compose BOM 统一管理 Compose 系版本
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui.tooling.preview)

    // DI（T01 落地 Hilt；KSP 处理注解处理）
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // 单测（T04 脚手架：MockK/Turbine/coroutines-test；Robolectric 仅 :data 需要）
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}
