package com.zz.passwordmanager.autofill

import com.zz.passwordmanager.data.vault.AutofillVaultSession
import com.zz.passwordmanager.data.vault.UnlockVault
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 填充服务的**结构性**契约测试（FR-6，v1.2.0）。
 *
 * 这组测试不谈「Dataset 有没有构造出来」——那种断言证明不了任何安全性。
 * 它卡的是本功能真正的设计承诺：
 *
 * 1. [VaultAutofillService] **不持有任何会话或密钥材料**；
 * 2. 认证 Activity **不导出**，伪造不了一���「用户已认证」；
 * 3. 声明齐全（BIND_AUTOFILL_SERVICE / intent-filter / exported），
 *    否则改的人会以为服务还活着；
 * 4. 字段识别的降级口径（S40）。
 *
 * 1 与 3 是可回滚证明的：把 `AutofillVaultSession` 注入 Service、或从 manifest 里
 * 删掉 `android:permission`，本文件立刻红。
 */
class VaultAutofillServiceContractTest {

    private fun manifest(): String =
        File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `服务不持有任何会话或密钥材料`() {
        val fields = VaultAutofillService::class.java.declaredFields

        // 一旦有人「顺手」把会话注入进来让 onFillRequest 直接填，
        // 「未认证不得读」这条就被绕过了——这里就是那道门。
        val forbidden = fields.filter {
            val type = it.type
            type == AutofillVaultSession::class.java ||
                type == UnlockVault::class.java ||
                type == ByteArray::class.java ||
                type == CharArray::class.java ||
                type.simpleName.contains("Session", ignoreCase = true) ||
                type.simpleName.contains("Key", ignoreCase = true) ||
                type.simpleName.contains("Dek", ignoreCase = true)
        }

        assertEquals(
            "VaultAutofillService 不得持有会话/密钥字段，实际持有：${forbidden.map { it.type.simpleName }}",
            emptyList<Any>(),
            forbidden.toList(),
        )
    }

    @Test
    fun `服务的注入点只有库状态探测`() {
        val injected = VaultAutofillService::class.java.declaredFields
            .filter { it.isAnnotationPresent(javax.inject.Inject::class.java) }
            .map { it.type.simpleName }

        assertEquals(
            "只允许注入 VaultStatus（只回答「库在不在」，不碰密钥）",
            listOf("VaultStatus"),
            injected,
        )
    }

    @Test
    fun `认证 Activity 不导出`() {
        assertTrue(
            "AutofillAuthActivity 必须 android:exported=\"false\"——" +
                "导出即可被任意 App 拉起来假装用户已认证",
            manifest().contains("""android:name=".autofill.AutofillAuthActivity""" ) &&
                Regex("""android:name="\.autofill\.AutofillAuthActivity"[\s\S]{0,300}?android:exported="false"""")
                    .containsMatchIn(manifest()),
        )
    }

    @Test
    fun `服务声明了 BIND_AUTOFILL_SERVICE 与 intent-filter`() {
        val text = manifest()
        assertTrue(
            "AutofillService 必须声明 android.permission.BIND_AUTOFILL_SERVICE，否则系统绑不上",
            Regex("""android:name="\.autofill\.VaultAutofillService"[\s\S]{0,400}?android:permission="android\.permission\.BIND_AUTOFILL_SERVICE"""").containsMatchIn(text),
        )
        assertTrue(
            "缺少 android.service.autofill.AutofillService 的 intent-filter",
            Regex("""android:name="\.autofill\.VaultAutofillService"[\s\S]{0,600}?<action android:name="android\.service\.autofill\.AutofillService" />""").containsMatchIn(text),
        )
    }

    @Test
    fun `主界面排除在填充目标之外`() {
        // 主密码框绝不能被任何填充服务（哪怕是我们自己的）认作目标。
        assertTrue(
            "MainActivity 必须 android:importantForAutofill=\"noExcludeDescendants\"",
            Regex("""android:name="\.MainActivity"[\s\S]{0,300}?android:importantForAutofill="noExcludeDescendants"""").containsMatchIn(manifest()),
        )
    }

    // ---- S40：字段识别的降级口径 ----

    @Test
    fun `autofillHints 优先`() {
        assertEquals(
            AutofillStructureReader.FieldKind.USERNAME,
            AutofillStructureReader.classify(listOf("username"), 0),
        )
        assertEquals(
            AutofillStructureReader.FieldKind.PASSWORD,
            AutofillStructureReader.classify(listOf("password"), 0),
        )
    }

    @Test
    fun `同时带账号与密码 hint 时按密码框处理`() {
        // 反过来会���账号框里塞上密码——这是本函数最不能搞反的一条。
        assertEquals(
            AutofillStructureReader.FieldKind.PASSWORD,
            AutofillStructureReader.classify(listOf("username", "password"), 0),
        )
    }

    @Test
    fun `没有 hint 时退回 inputType 嗅探`() {
        assertEquals(
            AutofillStructureReader.FieldKind.PASSWORD,
            AutofillStructureReader.classify(
                emptyList(),
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            ),
        )
        assertEquals(
            AutofillStructureReader.FieldKind.USERNAME,
            AutofillStructureReader.classify(
                emptyList(),
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            ),
        )
    }

    @Test
    fun `识别不出的字段返回 null 而不硬猜`() {
        assertEquals(null, AutofillStructureReader.classify(emptyList(), android.text.InputType.TYPE_CLASS_TEXT))
        assertEquals(null, AutofillStructureReader.classify(listOf("postalAddress"), 0))
        assertFalse(
            "隐藏域（TYPE_NULL）不得被误认成密码框",
            AutofillStructureReader.classify(emptyList(), android.text.InputType.TYPE_NULL) ==
                AutofillStructureReader.FieldKind.PASSWORD,
        )
    }
}
