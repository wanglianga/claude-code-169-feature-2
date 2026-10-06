package com.city.garbage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 居民一次投放记录：小区、楼栋、垃圾类型、重量、积分、违规提示。
 * 积分流水、告警、投诉、整改都必须回链到该记录，保证账实一致。
 */
@Getter
@Setter
@Entity
@Table(name = "disposal_record", indexes = {
        @Index(name = "idx_disposal_resident", columnList = "resident_id"),
        @Index(name = "idx_disposal_room", columnList = "room_code"),
        @Index(name = "idx_disposal_created", columnList = "created_at")
})
public class DisposalRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resident_id", nullable = false)
    private Long residentId;

    @Column(nullable = false, length = 64)
    private String cardNo;

    @Column(name = "community_id", nullable = false)
    private Long communityId;

    @Column(length = 64)
    private String buildingName;

    @Column(name = "room_code", nullable = false, length = 32)
    private String roomCode;

    /** 投放桶对应的类型 */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private WasteType binType;

    /** 摄像头识别出的实际垃圾类型 */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private WasteType detectedType;

    private Double weightKg;

    /** 本次积分变动（错投为负或0，结算后写入） */
    private Integer pointsChange = 0;

    private boolean wrongSort = false;

    private Double confidence;

    /** 识别该次投放的摄像头（默认 CAM-{roomCode}） */
    @Column(name = "camera_code", length = 64)
    private String cameraCode;

    /** true=摄像头校准中，本次错投处罚暂停自动生效（未扣分，待人工复核） */
    @Column(name = "penalty_suspended", nullable = false)
    private boolean penaltySuspended = false;

    /** 现场给居民的违规提示文案 */
    @Column(length = 255)
    private String violationMessage;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DisposalStatus status = DisposalStatus.PENDING_POINTS;

    private OffsetDateTime createdAt;
    private OffsetDateTime settledAt;

    /** 关联的错投告警/联动单，申诉链路使用 */
    @Column(length = 40)
    private String caseNo;
}
