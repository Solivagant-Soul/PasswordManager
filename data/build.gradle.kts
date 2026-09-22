// 数据层：Repository 实现、Vault Header、Room + SQLCipher（T03）、备份文件（方案 §2）。
// 分层边界：data 实现 domain 的端口；不得被 domain 反向依赖。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.zz.passwordmanager.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26 // 与 :app 一致（D1）

        // androidTest 就位（T03 决策）：加密库真开真写验证待设备/模拟器（T31 前补跑）
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            all {
                // Robolectric 的 android-all 运行时 jar 走阿里云镜像（本机网络对 mavenCentral 大文件不可达）
                it.systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/public")
                it.systemProperty("robolectric.dependency.repo.id", "aliyun")
            }
        }
    }
}

// Room schema 导出入库（迁移基线：schema v1 起每次变更可审、可生成迁移测试）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":core"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // Argon2id 纯 JVM 实现（D3）；只用轻量 API、不注册 Provider，避开 Android 平台 BC 冲突
    implementation(libs.bouncycastle.bcprov)
    // SQLCipher：整库加密，库密钥 = DEK（方案 §4.2）；net.zetetic 新坐标（mavenCentral）
    implementation(libs.sqlcipher.android)
    // Header/备份的 JSON 序列化（D5：自定义加密 JSON）
    implementation(libs.kotlinx.serialization.json)

    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    // T04 测试脚手架（Robolectric：JVM 单测中提供真 Android 框架）
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.core)
}
