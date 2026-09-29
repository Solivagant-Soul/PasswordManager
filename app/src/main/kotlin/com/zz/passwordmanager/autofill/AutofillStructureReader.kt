package com.zz.passwordmanager.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import com.zz.passwordmanager.domain.autofill.AutofillTarget

/**
 * 把 `AssistStructure`（框架模型）压成 [ParsedForm]（纯数据 + 一个 [AutofillId]）。
 *
 * 压平之后「这个字段算不算用户名/密码框」这条判断就落在 [classify] 上，
 * 不再依赖真实 View 树，可以在 JVM 单测里直接喂 hint/inputType 覆盖
 * （需求 S40：非标准表单要能优雅降级，而「能不能识别」正是降级与否的分界线）。
 */
object AutofillStructureReader {

    /**
     * 遍历上限。真实表单的 View 树可能极深极宽（恶意构造的还能故意撑爆），
     * 而 `onFillRequest` 跑在主线程上，不设闸等于把 ANR 的开关交给对方。
     */
    private const val MAX_NODES = 200

    /** 识别不出密码框时返回 null——调用方据此静默不填（S40）。 */
    fun read(structure: AssistStructure): ParsedForm? {
        var username: AutofillId? = null
        var password: AutofillId? = null
        var domain: String? = null
        // 包名只能从 ActivityComponent 取：ViewNode 本身不携带所属包。
        var packageName: String? = structure.activityComponent?.packageName
        var visited = 0

        // 显式栈而非递归：真实表单的 View 树可以很深，递归有栈溢出风险，
        // 而崩在主线程回调里就是 ANR。
        val stack = ArrayDeque<AssistStructure.ViewNode>()
        for (i in 0 until structure.windowNodeCount) {
            structure.getWindowNodeAt(i).rootViewNode?.let(stack::addLast)
        }
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            visited++
            val node = stack.removeLast()
            val id = node.autofillId
            if (id != null) {
                when (classify(node.autofillHints?.toList().orEmpty(), node.inputType)) {
                    null -> Unit
                    // 密码优先：不少 App 的登录表单里密码框也带 username hint，
                    // 先记密码再记用户名，顺序反了会把密码当成账号填出去。
                    FieldKind.PASSWORD -> if (password == null) password = id
                    FieldKind.USERNAME -> if (username == null) username = id
                }
            }
            node.webDomain?.takeIf { it.isNotBlank() }?.let { if (domain == null) domain = it }

            for (i in 0 until node.childCount) {
                node.getChildAt(i)?.let(stack::addLast)
            }
        }

        val passwordId = password ?: return null
        return ParsedForm(
            usernameId = username,
            passwordId = passwordId,
            target = AutofillTarget(packageName = packageName, domain = domain),
        )
    }

    enum class FieldKind { USERNAME, PASSWORD }

    /**
     * 判定一个节点是用户名框还是密码框。
     *
     * 优先信 `autofillHints`——那是目标 App 显式声明的语义；完全没有 hint 时才退回
     * `inputType` 嗅探。嗅探只覆盖**变化位**（variation）而不看 `TYPE_CLASS_*`：
     * 带 `TYPE_MASK_VARIATION` 的位只在 TYPE_CLASS_TEXT / TYPE_CLASS_NUMBER 下才有意义，
     * 混判会把 `TYPE_NULL`（隐藏域）之类的字段误认成密码框。
     */
    internal fun classify(hints: List<String>, inputType: Int): FieldKind? {
        if (hints.isNotEmpty()) {
            if (hints.any { it == View.AUTOFILL_HINT_PASSWORD }) {
                return FieldKind.PASSWORD
            }
            if (hints.any {
                    it == View.AUTOFILL_HINT_USERNAME ||
                        it == View.AUTOFILL_HINT_EMAIL_ADDRESS
                }
            ) {
                return FieldKind.USERNAME
            }
        }
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            -> FieldKind.PASSWORD

            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            -> FieldKind.USERNAME

            else -> null
        } ?: null
    }
}

/** 从表单里识别出来的字段与目标特征。 */
data class ParsedForm(
    val usernameId: AutofillId?,
    val passwordId: AutofillId,
    val target: AutofillTarget,
)
