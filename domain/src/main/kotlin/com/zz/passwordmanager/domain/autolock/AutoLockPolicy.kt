package com.zz.passwordmanager.domain.autolock

/** 自动锁定档位（T08 验收：各档生效，默认 M1=Q-04）。 */
enum class AutoLockDuration(val millis: Long) {
    IMMEDIATE(0L),
    S30(30_000L),
    M1(60_000L),
    M5(300_000L),
}

/**
 * 自动锁定策略（FR-8）：后台停留时长达到档位即锁。
 *
 * - 边界语义：elapsed >= duration 即锁（「超过 1 分钟」含等号，S50/S51）；
 * - IMMEDIATE 档离开即锁（elapsed >= 0 恒真）；
 * - 时钟回拨（elapsed < 0）按「应锁」处理——宁严勿松（S52）。
 */
object AutoLockPolicy {

    fun shouldLock(backgroundedAtMs: Long, foregroundAtMs: Long, duration: AutoLockDuration): Boolean {
        val elapsed = foregroundAtMs - backgroundedAtMs
        return elapsed < 0 || elapsed >= duration.millis
    }
}
