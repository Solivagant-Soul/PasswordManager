// 数据层：Repository 实现、Vault Header、Room + SQLCipher（T03 起）、备份文件（方案 §2）。
// 分层边界：data 实现 domain 的端口；不得被 domain 反向依赖。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.zz.passwordmanager.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26 // 与 :app 一致（D1）
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":core"))

    testImplementation(libs.junit)
}
