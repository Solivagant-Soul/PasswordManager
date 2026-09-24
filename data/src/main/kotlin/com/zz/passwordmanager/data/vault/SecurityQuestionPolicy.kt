package com.zz.passwordmanager.data.vault

/**
 * 密保问题/答案校验（评审 G-F）：`InitializeVault` 与 `ChangeSecurityQuestions`
 * 原先各写一份相同规则，易漂移——收敛到此处单一实现。
 *
 * 规则（需求 §6.4）：必须 3 道；问题文本 1..100 字符；答案 1..60 字符（均 Trim 后计）。
 * 答案在此只做长度/非空校验，不参与编码——编码与派生见 `KeyManager.deriveRecoveryKek`（D4）。
 */
internal object SecurityQuestionPolicy {

    const val REQUIRED_QUESTIONS = 3
    const val QUESTION_MAX = 100
    const val ANSWER_MAX = 60
    const val FIELD = "securityQuestions"

    fun issues(questions: List<InitializeRequest.SecurityQuestionInput>): List<Issue> {
        if (questions.size != REQUIRED_QUESTIONS) {
            return listOf(Issue(FIELD, "expected $REQUIRED_QUESTIONS questions"))
        }
        val issues = mutableListOf<Issue>()
        questions.forEachIndexed { index, q ->
            val question = q.question.trim()
            if (question.isEmpty() || question.length > QUESTION_MAX) {
                issues += Issue(FIELD, "question ${index + 1}: invalid length")
            }
            val answer = q.answer.concatToString().trim()
            if (answer.isEmpty() || answer.length > ANSWER_MAX) {
                issues += Issue(FIELD, "answer ${index + 1}: invalid length")
            }
        }
        return issues
    }
}
