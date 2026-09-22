// 领域层：UseCase、Repository 端口接口、领域模型（方案 §2）。
// 分层边界：domain 纯 Kotlin（不含 Android 依赖），仅依赖 :core；
// 数据层/平台层是本层端口的实现方（依赖方向 data/platform → domain）。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // api：core 的类型与 Flow 会出现在本层公共签名中，需传递给消费方
    api(project(":core"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}

kotlin {
    jvmToolchain(17)
}
