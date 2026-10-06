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
    /** 同一摄像头复核样本数达到该值后才评估误判率 */
    private int cameraMisjudgeMinReviews = 2;
    /** 误判率（%）达到该阈值则摄像头进入校准，错投处罚暂停自动生效 */
    private double cameraMisjudgeRatePct = 50.0;
}
