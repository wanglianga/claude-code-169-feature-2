package com.city.garbage.domain;

/** 错投申诉复核状态 */
public enum AppealStatus {
    SUBMITTED,            // 居民已提交申诉，等待取证
    EVIDENCE_COLLECTED,   // 服务已调取视频/称重/桶位/说明，等待复核
    REVIEWING,            // 复核处理中
    MISJUDGE_CONFIRMED,   // 误判成立：积分恢复，样本回传识别模型
    VIOLATION_CONFIRMED,  // 违规成立：派发分类教育任务
    CLOSED                // 复核闭环（教育任务完成等后续动作已处理）
}
