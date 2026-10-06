package com.city.garbage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 摄像头误判统计与校准状态。每次申诉复核结论都会累计该摄像头的
 * 复核样本数与误判数；误判率达到阈值后摄像头进入校准（suspended=true），
 * 校准期间该摄像头的错投处罚暂停自动生效，校准完成后清零重新累计。
 */
@Getter
@Setter
@Entity
@Table(name = "camera_stat")
public class CameraStat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "camera_code", nullable = false, unique = true, length = 64)
    private String cameraCode;

    @Column(name = "room_code", nullable = false, length = 32)
    private String roomCode;

    /** 累计复核样本数（申诉复核结论总数） */
    @Column(nullable = false)
    private Integer reviewTotal = 0;

    /** 其中误判成立数 */
    @Column(nullable = false)
    private Integer misjudgeCount = 0;

    /** true=误判率超标，设备校准中，错投处罚暂停自动生效 */
    @Column(nullable = false)
    private boolean suspended = false;

    private OffsetDateTime suspendedAt;
    private OffsetDateTime updatedAt;

    public CameraStat() {}

    public CameraStat(String cameraCode, String roomCode) {
        this.cameraCode = cameraCode;
        this.roomCode = roomCode;
    }

    /** 当前误判率（%），无样本时为 0 */
    @Transient
    public double getMisjudgeRatePct() {
        if (reviewTotal == null || reviewTotal == 0) return 0.0;
        return Math.round(misjudgeCount * 1000.0 / reviewTotal) / 10.0;
    }
}
