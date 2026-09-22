package com.zz.passwordmanager.data

import android.content.Context
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T04 测试脚手架冒烟：Robolectric 在 :data 可用（JVM 上提供真 Android 框架）。
 * android-all 运行时经阿里云镜像下载（见 build.gradle.kts testOptions）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RobolectricStackSmokeTest {

    @Test
    fun `android runtime is available on jvm`() {
        val context: Context = RuntimeEnvironment.getApplication()

        assertNotNull(context)
        assertTrue(context.packageName.isNotEmpty())
    }
}
