package com.city.garbage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 错投申诉复核单。居民对“玻璃瓶投入厨余桶”等错投识别提出申诉后，
 * 服务调取投放视频片段、重量变化、桶位与居民说明作为复核证据；
 * 复核结论二选一：误判成立（积分恢复、样本回传识别模型）或违规成立（派分类教育任务）。
 */
@Getter
@Setter
@Entity
@Table(name = "appeal_review", indexes = {
        @Index(name = "idx_appeal_disposal", columnList = "disposal_id", unique = true),
        @Index(name = "idx_appeal_resident", columnList = "resident_id"),
        @Index(name = "idx_appeal_status", columnList = "status")
})
public class AppealReview {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "appeal_no", nullable = false, unique = true, length = 40)
    private String appealNo;

    @Column(name = "disposal_id", nullable = false)
    private Long disposalId;

    @Column(name = "resident_id", nullable = false)
    private Long residentId;

    @Column(name = "room_code", nullable = false, length = 32)
    private String roomCode;

    /** 被申诉识别结果所属摄像头 */
    @Column(name = "camera_code", length = 64)
    private String cameraCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "bin_type", length = 16)
    private WasteType binType;

    @Enumerated(EnumType.STRING)
    @Column(name = "detected_type", length = 16)
    private WasteType detectedType;

    /** 居民说明（申诉理由） */
    @Column(name = "resident_statement", length = 512)
    private String residentStatement;

    /* ---- 服务调取的复核证据 ---- */
    /** 投放视频片段引用（相对地址/对象存储 key） */
    @Column(name = "video_clip", length = 255)
    private String videoClip;

    private OffsetDateTime videoStartAt;
    private OffsetDateTime videoEndAt;

    /** 投放前后桶内重量与变化量 */
    private Double weightBeforeKg;
    private Double weightAfterKg;
    private Double weightDeltaKg;

    /** 桶位，如 厨余垃圾-1号位 */
    @Column(name = "bin_position", length = 64)
    private String binPosition;

    private OffsetDateTime evidenceCollectedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AppealStatus status = AppealStatus.SUBMITTED;

    /** true=误判成立；false=违规成立；null=尚未复核 */
    private Boolean misjudge;

    @Column(length = 64)
    private String reviewer;

    @Column(name = "review_note", length = 512)
    private String reviewNote;

    /** 误判成立后生成并回传模型的样本 id */
    @Column(name = "feedback_sample_id")
    private Long feedbackSampleId;

    /** 违规成立后派发的教育记录 id */
    @Column(name = "education_record_id")
    private Long educationRecordId;

    private OffsetDateTime createdAt;
    private OffsetDateTime submittedAt;
    private OffsetDateTime reviewedAt;
    private OffsetDateTime closedAt;
}
