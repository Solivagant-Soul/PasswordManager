package com.zz.passwordmanager.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AppResultTest {

    @Test
    fun `fold maps ok branch`() {
        val result: AppResult<Int> = AppResult.Ok(2)

        assertEquals("ok:2", result.fold({ v -> "ok:$v" }, { m -> "err:$m" }))
    }

    @Test
    fun `fold maps error branch`() {
        val result: AppResult<Int> = AppResult.Error("boom")

        assertEquals("err:boom", result.fold({ v -> "ok:$v" }, { m -> "err:$m" }))
    }
}
