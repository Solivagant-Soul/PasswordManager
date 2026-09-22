package com.zz.passwordmanager.platform

/**
 * 系统剪贴板桥（FR-9）的最小占位：复制敏感值 + 30 秒自动清理。
 *
 * 平台层只封装 Android 系统能力（含系统能力边界的降级提示，D9），
 * 清理策略与用户提示由上层编排；T17 落地实现。
 */
interface ClipboardBridge
