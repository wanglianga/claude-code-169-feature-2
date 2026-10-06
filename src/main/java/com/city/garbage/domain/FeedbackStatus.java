package com.city.garbage.domain;

/** 回传给识别模型的误判样本处理状态 */
public enum FeedbackStatus {
    PENDING,   // 已生成，等待回传
    SENT,      // 已回传识别模型（供再训练/纠偏）
    FAILED     // 回传失败，可重试
}
