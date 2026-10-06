package com.city.garbage.service;

import com.city.garbage.domain.*;
import com.city.garbage.repo.*;
import com.city.garbage.support.BusinessException;
import com.city.garbage.support.TimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 物业工作台：看待处理任务、派发、完成保洁/消杀/维修/复核并回填整改结果。
 * 完成任务会同步解除告警、刷新联动单与垃圾房状态。
 */
@Service
@RequiredArgsConstructor
public class WorkTaskService {

    private final WorkTaskRepository taskRepository;
    private final AlertRepository alertRepository;
    private final GarbageRoomRepository roomRepository;
    private final LinkageEngine engine;
    private final CameraStatService cameraStatService;
    private final TimeService timeService;

    /** 物业端待处理任务（保洁、消杀、维修、错投复核、整改） */
    public List<WorkTask> propertyPending() {
        return taskRepository.findByAssigneeRoleAndStatusNotOrderByPriorityDescCreatedAtAsc("PROPERTY", TaskStatus.DONE);
    }

    /** 环卫端清运任务 */
    public List<WorkTask> sanitationPending() {
        return taskRepository.findByAssigneeRoleAndTypeAndStatusNotOrderByPriorityDescCreatedAtAsc(
                "SANITATION", TaskType.TRUCK_DISPATCH, TaskStatus.DONE);
    }

    public List<WorkTask> byCase(String caseNo) {
        return taskRepository.findByCaseNo(caseNo);
    }

    @Transactional
    public WorkTask assign(Long taskId, String assigneeName) {
        WorkTask t = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException("任务不存在: " + taskId));
        engine.markAssigned(t, assigneeName);
        return t;
    }

    /**
     * 完成任务。保洁/消杀完成会解除异味相关告警；错投复核任务需带申诉结论（由投放复核接口处理）。
     */
    @Transactional
    public WorkTask complete(Long taskId, String note) {
        WorkTask t = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException("任务不存在: " + taskId));
        engine.markDone(t, note);

        GarbageRoom room = roomRepository.findByCode(t.getRoomCode()).orElse(null);
        if (room == null) return t;
        switch (t.getType()) {
            case DISINFECTION -> {
                room.setLastDisinfectionAt(timeService.now());
                roomRepository.save(room);
                engine.resolveByType(room.getCode(), AlertType.ODOR, "消杀完成：" + note);
            }
            case CLEANING -> {
                room.setLastCleanedAt(timeService.now());
                room.setCurrentOdor(Math.min(room.getCurrentOdor(), 30));
                roomRepository.save(room);
                // 普通保洁不能销投诉，投诉由整改反馈接口闭环；但保洁+消杀后传感器异味已解除
                engine.resolveByType(room.getCode(), AlertType.ODOR, "保洁清洗完成：" + note);
            }
            case REPAIR -> {
                room.setOnline(true);
                room.setLastHeartbeatAt(timeService.now());
                roomRepository.save(room);
                engine.resolveByType(room.getCode(), AlertType.DEVICE_OFFLINE, "现场检修恢复联网：" + note);
            }
            case CAMERA_CALIBRATION -> {
                // 校准完成：解除该房所有校准中摄像头的处罚暂停，并销校准告警
                for (Alert a : alertRepository.findByRoomCodeAndStatusIn(room.getCode(),
                        List.of(AlertStatus.OPEN, AlertStatus.LINKED))) {
                    if (a.getType() == AlertType.CAMERA_CALIBRATION && a.getDedupeKey() != null
                            && a.getDedupeKey().startsWith("CAMERA_CALIBRATION:")) {
                        cameraStatService.calibrate(a.getDedupeKey().substring("CAMERA_CALIBRATION:".length()));
                    }
                }
                engine.resolveByType(room.getCode(), AlertType.CAMERA_CALIBRATION, "摄像头校准完成：" + note);
            }
            default -> { /* 清运/复核类由专门接口闭环 */ }
        }
        return t;
    }

    public List<Alert> openAlerts(String roomCode) {
        return alertRepository.findByRoomCodeAndStatusIn(roomCode,
                List.of(AlertStatus.OPEN, AlertStatus.LINKED));
    }
}
