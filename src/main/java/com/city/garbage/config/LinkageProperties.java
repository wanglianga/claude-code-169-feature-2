package com.city.garbage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.linkage")
public class LinkageProperties {
    private int nightStart = 20;
    private int nightEnd = 6;
    private int nightDisposalThreshold = 3;
    private int offlineMinutes = 10;
    private int binFullThreshold = 90;
    private int odorThreshold = 70;
    private int pointsPerKg = 10;
    private int wrongSortDeduction = 5;
    /** 清运离场后多少分钟未做清洁确认视为“清运后未清洁” */
    private int notCleanedMinutes = 15;
    /** 积分待结算超过多少秒视为“积分未到账” */
    private int unsettledSeconds = 60;
    /** 同一摄像头误判率达到该比例（0-1）即进入校准任务 */
    private double cameraMisjudgeRateThreshold = 0.5;
    /** 触发误判率评估前，窗口内摄像头判定错投的最少样本数 */
    private int cameraMinMissortSamples = 2;
    /** 申诉复核时调取投放前后设备证据（视频/称重）的时间窗（秒） */
    private int appealEvidenceWindowSeconds = 120;
}
