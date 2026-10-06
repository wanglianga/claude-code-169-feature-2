package com.city.garbage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 垃圾房摄像头：按摄像头统计错投识别量与经申诉复核确认的误判量。
 * 同一摄像头误判率升高时进入校准任务（CALIBRATING），期间错投处罚暂停自动生效；
 * 校准完成后计数清零、恢复正常执法。
 */
@Getter
@Setter
@Entity
@Table(name = "camera_device", indexes = {
        @Index(name = "idx_camera_room", columnList = "room_code"),
        @Index(name = "idx_camera_status", columnList = "calibration_status")
})
public class CameraDevice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "camera_code", nullable = false, unique = true, length = 64)
    private String cameraCode;

    @Column(name = "room_code", nullable = false, length = 32)
    private String roomCode;

    @Column(length = 128)
    private String name;

    /* ---- 自上次校准以来的滚动统计窗口 ---- */
    /** 窗口起始时间（上次校准完成时间，无则为首次上报时间） */
    private OffsetDateTime windowStartAt;

    /** 窗口内摄像头判为“错投”的次数（被处罚/被申诉基数） */
    private int missortFlaggedCount = 0;

    /** 窗口内经申诉复核确认“误判”的次数 */
    private int misjudgeConfirmedCount = 0;

    /** 窗口内经申诉复核确认“违规属实”的次数 */
    private int violationConfirmedCount = 0;

    /** 窗口内全部识别次数（含正确投放） */
    private int totalDetectionCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "calibration_status", nullable = false, length = 16)
    private CameraCalibrationStatus calibrationStatus = CameraCalibrationStatus.NORMAL;

    private OffsetDateTime calibrationStartedAt;
    private OffsetDateTime calibratedAt;

    /** 最近一次误判率（0-1），用于展示与判断 */
    private Double lastMisjudgeRate;

    @Column(name = "calibration_note", length = 255)
    private String calibrationNote;
}
