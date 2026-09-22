package com.zz.passwordmanager.data.header

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Vault Header 存储验收（T03：Header 可在无 DEK 时读写并验签）——纯 JVM，不依赖 Android 运行时。
 */
class VaultHeaderStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val macKey = ByteArray(32) { it.toByte() }

    private fun sampleHeader() = VaultHeader(
        saltKdf = ByteArray(16) { 1 },
        kdfParams = KdfParams(iterations = 3, memoryKib = 65536, parallelism = 4),
        wrappedDekMaster = ByteArray(48) { 2 },
        wrappedDekRecovery = ByteArray(48) { 3 },
        saltRecovery = ByteArray(16) { 4 },
        securityQuestions = listOf(
            SecurityQuestionRecord(1, "问题一"),
            SecurityQuestionRecord(2, "问题二"),
            SecurityQuestionRecord(3, "问题三"),
        ),
    )

    private fun newStore(macKey: ByteArray = this.macKey): VaultHeaderStore =
        VaultHeaderStore(directory = tmp.root, macKey = macKey)

    @Test
    fun `read returns null before initialization`() {
        assertNull(newStore().read())
    }

    @Test
    fun `write then read round trip preserves fields`() {
        val header = sampleHeader()

        newStore().write(header)
        val restored = newStore().read()

        assertNotNull(restored)
        assertEquals(header.saltKdf.toList(), restored!!.saltKdf.toList())
        assertEquals(header.kdfParams, restored.kdfParams)
        assertEquals(header.wrappedDekMaster.toList(), restored.wrappedDekMaster.toList())
        assertEquals(header.wrappedDekRecovery.toList(), restored.wrappedDekRecovery.toList())
        assertEquals(header.saltRecovery.toList(), restored.saltRecovery.toList())
        assertEquals(header.securityQuestions, restored.securityQuestions)
        assertEquals(0, restored.failedAttempts)
        assertEquals(0L, restored.lockUntilEpochMs)
    }

    @Test
    fun `rewrite replaces atomically without residue`() {
        val store = newStore()

        store.write(sampleHeader())
        store.write(sampleHeader().copy(biometricEnabled = true))

        assertEquals(true, newStore().read()!!.biometricEnabled)
        assertEquals(
            "no temp file residue",
            listOf("vault-header.json"),
            tmp.root.listFiles()!!.map { it.name }.sorted(),
        )
    }

    @Test
    fun `tampered payload is rejected`() {
        val store = newStore()
        store.write(sampleHeader())
        val headerFile = File(tmp.root, "vault-header.json")

        // 模拟攻击者：改业务字段（如失败计数），MAC 随之失配
        headerFile.writeText(headerFile.readText().replace("问题一", "问题一改"))

        assertThrows(HeaderIntegrityException::class.java) { newStore().read() }
    }

    @Test
    fun `corrupted file is rejected`() {
        val store = newStore()
        store.write(sampleHeader())
        val headerFile = File(tmp.root, "vault-header.json")

        headerFile.writeText("not-a-json{{{")

        assertThrows(HeaderIntegrityException::class.java) { newStore().read() }
    }

    @Test
    fun `wrong mac key is rejected`() {
        val otherKey = ByteArray(32) { (it + 7).toByte() }

        newStore().write(sampleHeader())

        assertThrows(HeaderIntegrityException::class.java) { newStore(macKey = otherKey).read() }
    }
}
