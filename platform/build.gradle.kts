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

        // T09：androidx runner（framework 老 runner 无法加载 AndroidJUnit4，会进程崩溃）
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

dependencies {
    implementation(project(":domain"))
    implementation(project(":core"))
    // 生物识别系统桥（T09）：androidx 统一 API，含 26–29 兼容路径
    implementation(libs.androidx.biometric)

    testImplementation(libs.junit)
    // T04 测试脚手架
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
