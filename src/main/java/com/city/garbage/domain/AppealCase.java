package com.city.garbage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 错投申诉复核单。居民对摄像头错投判罚发起申诉时建立，
 * 服务自动调取证据包（投放视频片段、称重变化、桶位、居民说明）随单流转，
 * 复核结论回写投放记录、积分流水、教育记录与摄像头误判统计。
 */
@Getter
@Setter
@Entity
@Table(name = "appeal_case", indexes = {
        @Index(name = "idx_appeal_disposal", columnList = "disposal_id"),
        @Index(name = "idx_appeal_resident", columnList = "resident_id"),
        @Index(name = "idx_appeal_status", columnList = "status")
})
public class AppealCase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被申诉的投放记录 */
    @Column(name = "disposal_id", nullable = false)
    private Long disposalId;

    @Column(name = "resident_id", nullable = false)
    private Long residentId;

    @Column(nullable = false, length = 64)
    private String cardNo;

    @Column(name = "room_code", nullable = false, length = 32)
    private String roomCode;

    /** 识别该次投放的摄像头 */
    @Column(name = "camera_code", nullable = false, length = 64)
    private String cameraCode;

    /** 投放桶类型（居民刷卡选择） */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private WasteType binType;

    /** 摄像头识别出的垃圾类型 */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private WasteType detectedType;

    private Double confidence;

    /* ---- 复核证据包（申诉时由服务自动调取） ---- */

    /** 投放视频片段地址（摄像头存储调取） */
    @Column(name = "video_clip_url", length = 255)
    private String videoClipUrl;

    /** 投放前后称重变化（kg） */
    @Column(name = "weight_delta_kg")
    private Double weightDeltaKg;

    /** 桶位描述，如 阳光花园南门 · 厨余垃圾桶位 */
    @Column(name = "bin_position", length = 128)
    private String binPosition;

    /** 居民说明（申诉理由） */
    @Column(name = "resident_statement", length = 255)
    private String residentStatement;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AppealStatus status = AppealStatus.PENDING_REVIEW;

    @Column(name = "review_note", length = 255)
    private String reviewNote;

    private OffsetDateTime createdAt;
    private OffsetDateTime reviewedAt;
}
