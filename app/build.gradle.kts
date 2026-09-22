plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.zz.passwordmanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zz.passwordmanager"
        minSdk = 26        // D1 已定：Android 8.0+
        targetSdk = 35     // Q3 决策：保守组合，与 AGP 8.7.3 成对
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose BOM 统一管理 Compose 系版本
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui.tooling.preview)

    // DI（T01 落地 Hilt；KSP 处理注解处理）
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // 单测（T01 占位；完整测试栈 MockK/Turbine/Robolectric 见 T04）
    testImplementation(libs.junit)
}
