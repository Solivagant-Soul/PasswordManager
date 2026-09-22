package com.zz.passwordmanager.core

import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/** T04 测试脚手架冒烟：MockK 在 :core 可用。 */
class CoreStackSmokeTest {

    interface Sink {
        fun write(line: String)
    }

    @Test
    fun `mockk is operational`() {
        val sink = mockk<Sink>(relaxed = true)

        sink.write("t04")

        verify { sink.write("t04") }
    }
}
