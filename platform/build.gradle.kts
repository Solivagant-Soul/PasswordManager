// 平台层：Android 系统能力的薄封装——Biometric / AutofillService / ClipboardManager（方案 §2）。
// 分层边界：platform 实现 domain 端口对应的系统桥；业务规则不落在本层。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.zz.passwordmanager.platform"
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
