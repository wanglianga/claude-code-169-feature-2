package com.city.garbage.domain;

/** 错投申诉复核单生命周期 */
public enum AppealStatus {
    PENDING_REVIEW,      // 已提交申诉，待物业/街道复核
    MISJUDGE_CONFIRMED,  // 复核结论：摄像头误判成立
    VIOLATION_CONFIRMED  // 复核结论：违规成立
}
