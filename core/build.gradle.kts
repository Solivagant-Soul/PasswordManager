// 通用件层：Result 封装、日志脱敏接口等（方案 §3 core）。
// 分层边界：core 不依赖任何项目模块——它是被所有层依赖的最底层。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    // T04 测试脚手架
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(17)
}
