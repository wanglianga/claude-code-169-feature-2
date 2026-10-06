package com.city.garbage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 居民分类教育任务：申诉复核判定违规成立后下达，
 * 居民端可见并完成学习打卡，形成居民教育记录。
 */
@Getter
@Setter
@Entity
@Table(name = "education_task", indexes = {
        @Index(name = "idx_edu_resident", columnList = "resident_id")
})
public class EducationTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resident_id", nullable = false)
    private Long residentId;

    @Column(nullable = false, length = 64)
    private String cardNo;

    /** 触发该教育任务的投放记录与申诉复核单 */
    @Column(name = "disposal_id", nullable = false)
    private Long disposalId;

    @Column(name = "appeal_id", nullable = false)
    private Long appealId;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(nullable = false, length = 255)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EducationStatus status = EducationStatus.PENDING;

    private OffsetDateTime createdAt;
    private OffsetDateTime completedAt;
}
