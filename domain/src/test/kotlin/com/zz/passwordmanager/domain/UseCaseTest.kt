package com.zz.passwordmanager.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class UseCaseTest {

    @Test
    fun `invoke delegates to implementation`() = runBlocking {
        val useCase = UseCase { input: String -> input.uppercase() }

        assertEquals("T02", useCase("t02"))
    }
}
