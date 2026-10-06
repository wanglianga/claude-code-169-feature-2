package com.city.garbage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 误判样本：申诉复核确认摄像头误判后生成，并回传给识别模型用于纠偏/再训练。
 * 样本与真实投放、复核单一一对应，保证可追溯。
 */
@Getter
@Setter
@Entity
@Table(name = "model_feedback_sample", indexes = {
        @Index(name = "idx_feedback_camera", columnList = "camera_code"),
        @Index(name = "idx_feedback_status", columnList = "status")
})
public class ModelFeedbackSample {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "disposal_id", nullable = false)
    private Long disposalId;

    @Column(name = "appeal_id", nullable = false)
    private Long appealId;

    @Column(name = "camera_code", length = 64)
    private String cameraCode;

    @Column(name = "room_code", length = 32)
    private String roomCode;

    /** 模型当时给出的（错误）识别结果 */
    @Enumerated(EnumType.STRING)
    @Column(name = "predicted_type", length = 16)
    private WasteType predictedType;

    /** 经人工复核确认的正确类别（纠偏标签） */
    @Enumerated(EnumType.STRING)
    @Column(name = "actual_type", length = 16)
    private WasteType actualType;

    /** 视频片段引用，回传模型侧用于再训练 */
    @Column(name = "video_clip", length = 255)
    private String videoClip;

    @Column(name = "review_note", length = 512)
    private String reviewNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FeedbackStatus status = FeedbackStatus.PENDING;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "send_error", length = 255)
    private String sendError;

    private OffsetDateTime createdAt;
}
