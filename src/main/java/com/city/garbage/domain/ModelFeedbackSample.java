package com.city.garbage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 识别模型回传样本：复核判定摄像头误判后，把该次投放的
 * 视频片段、称重、真实类别回传给识别模型训练管道，用于降低后续误判。
 */
@Getter
@Setter
@Entity
@Table(name = "model_feedback_sample", indexes = {
        @Index(name = "idx_feedback_camera", columnList = "camera_code")
})
public class ModelFeedbackSample {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 来源申诉复核单 */
    @Column(name = "appeal_id", nullable = false)
    private Long appealId;

    @Column(name = "disposal_id", nullable = false)
    private Long disposalId;

    @Column(name = "camera_code", nullable = false, length = 64)
    private String cameraCode;

    @Column(name = "room_code", nullable = false, length = 32)
    private String roomCode;

    /** 摄像头当时的识别结果（错误标签） */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private WasteType detectedType;

    /** 复核确认的真实类别（正确标签） */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private WasteType correctedType;

    @Column(name = "video_clip_url", length = 255)
    private String videoClipUrl;

    @Column(name = "weight_delta_kg")
    private Double weightDeltaKg;

    /** SENT=已回传识别模型训练管道 */
    @Column(nullable = false, length = 16)
    private String status = "SENT";

    @Column(length = 255)
    private String note;

    private OffsetDateTime createdAt;
    private OffsetDateTime sentAt;
}
