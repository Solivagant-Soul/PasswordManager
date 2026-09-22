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
    // SQLCipher：整库加密，库密钥 = DEK（方案 §4.2）；net.zetetic 新坐标（mavenCentral）
    implementation(libs.sqlcipher.android)
    // Header/备份的 JSON 序列化（D5：自定义加密 JSON）
    implementation(libs.kotlinx.serialization.json)

    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.core)
}
