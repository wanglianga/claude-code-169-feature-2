package com.city.garbage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 居民分类教育记录：违规成立后派发分类教育任务（学习+答题）。
 * 与真实投放、复核单一一对应，完成后更新居民教育记录并闭环复核单。
 */
@Getter
@Setter
@Entity
@Table(name = "education_record", indexes = {
        @Index(name = "idx_education_resident", columnList = "resident_id"),
        @Index(name = "idx_education_status", columnList = "status")
})
public class EducationRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resident_id", nullable = false)
    private Long residentId;

    @Column(name = "disposal_id", nullable = false)
    private Long disposalId;

    @Column(name = "appeal_id")
    private Long appealId;

    @Column(name = "room_code", length = 32)
    private String roomCode;

    /** 教育主题，如“厨余垃圾与玻璃瓶正确分类” */
    @Column(name = "topic", length = 128)
    private String topic;

    /** 教育内容/课程指引 */
    @Column(name = "content", length = 512)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EducationStatus status = EducationStatus.ASSIGNED;

    private OffsetDateTime assignedAt;
    private OffsetDateTime completedAt;

    /** 居民完成学习后的回执/答题结果 */
    @Column(name = "result_note", length = 255)
    private String resultNote;
}
