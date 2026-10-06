package com.city.garbage.service;

import com.city.garbage.config.LinkageProperties;
import com.city.garbage.domain.*;
import com.city.garbage.repo.*;
import com.city.garbage.support.TimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 联动引擎：设备/业务告警统一在此合并为“治理联动单”，并派生现场治理任务。
 * 同一垃圾房同时出现满溢、异味、错投时，只维护一张活跃联动单，
 * 物业保洁消杀、环卫清运、积分申诉、投诉整改全部挂单联动、闭环销单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LinkageEngine {

    private final AlertRepository alertRepository;
    private final GovernanceCaseRepository caseRepository;
    private final WorkTaskRepository taskRepository;
    private final GarbageRoomRepository roomRepository;
    private final TimeService timeService;
    private final LinkageProperties props;

    private static final List<AlertStatus> ACTIVE_ALERT = List.of(AlertStatus.OPEN, AlertStatus.LINKED);

    /**
     * 产生告警并并入垃圾房当前联动单。dedupeKey 非空时同键未闭环告警只保留一条。
     */
    @Transactional
    public Alert raiseAlert(GarbageRoom room, AlertType type, AlertSeverity severity,
                            String message, String dedupeKey, Long disposalId, Long complaintId) {
        if (dedupeKey != null) {
            var existing = alertRepository.findByDedupeKeyAndStatusIn(dedupeKey, ACTIVE_ALERT);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        Alert alert = new Alert();
        alert.setType(type);
        alert.setSeverity(severity);
        alert.setStatus(AlertStatus.OPEN);
        alert.setRoomCode(room.getCode());
        alert.setCommunityId(room.getCommunityId());
        alert.setDisposalId(disposalId);
        alert.setComplaintId(complaintId);
        alert.setMessage(message);
        alert.setDedupeKey(dedupeKey);
        alert.setCreatedAt(timeService.now());
        alert = alertRepository.save(alert);

        attachToCase(room, alert);
        log.info("告警产生 room={} type={} case={}", room.getCode(), type, alert.getCaseNo());
        return alert;
    }

    /** 把告警挂到垃圾房当前活跃联动单（无单则建单），并派生任务 */
    private void attachToCase(GarbageRoom room, Alert alert) {
        GovernanceCase gc = activeCase(room);
        boolean isNew = gc == null;
        if (isNew) {
            gc = new GovernanceCase();
            gc.setCaseNo("CASE-" + room.getCode() + "-" + System.currentTimeMillis());
            gc.setRoomCode(room.getCode());
            gc.setCommunityId(room.getCommunityId());
            gc.setStatus(CaseStatus.OPEN);
            gc.setCreatedAt(timeService.now());
        }
        gc.setUpdatedAt(timeService.now());
        gc.addAlertType(alert.getType());
        gc.setSeverity(maxSeverity(gc.getSeverity(), alert.getSeverity()));
        gc.setSummary(buildSummary(gc));
        gc = caseRepository.save(gc);

        alert.setCaseNo(gc.getCaseNo());
        alert.setStatus(AlertStatus.LINKED);
        alertRepository.save(alert);

        deriveTasks(gc, room, alert);

        if (isNew || room.getActiveCaseNo() == null) {
            room.setActiveCaseNo(gc.getCaseNo());
            roomRepository.save(room);
        }
        recalcCase(gc.getCaseNo());
    }

    private GovernanceCase activeCase(GarbageRoom room) {
        if (room.getActiveCaseNo() == null) return null;
        return caseRepository.findByCaseNo(room.getActiveCaseNo())
                .filter(c -> c.getStatus() != CaseStatus.RESOLVED && c.getStatus() != CaseStatus.CLOSED_FALSE)
                .orElse(null);
    }

    /** 按告警类型派生现场任务（同单同类型任务去重，优先级就高） */
    private void deriveTasks(GovernanceCase gc, GarbageRoom room, Alert alert) {
        switch (alert.getType()) {
            case KITCHEN_FULL_NO_TRUCK -> createTask(gc, TaskType.TRUCK_DISPATCH, "SANITATION", 100,
                    "厨余桶满溢(" + room.getKitchenCapacityPct() + "%)且清运车未到，优先调度清运", alert.getId());
            case OTHER_BIN_FULL -> createTask(gc, TaskType.TRUCK_DISPATCH, "SANITATION", 80,
                    "其他垃圾桶容量" + room.getOtherCapacityPct() + "%，安排清运", alert.getId());
            case ODOR, ODOR_COMPLAINT -> {
                createTask(gc, TaskType.DISINFECTION, "PROPERTY", 70,
                        alert.getType() == AlertType.ODOR_COMPLAINT ? "投诉异味，立即消杀" : "异味超标，安排消杀", alert.getId());
                createTask(gc, TaskType.CLEANING, "PROPERTY", 60, "垃圾房保洁清洗，控制异味", alert.getId());
            }
            case MISSORT, CAMERA_MISJUDGE -> createTask(gc, TaskType.RECHECK_SORTING, "PROPERTY", 55,
                    "摄像头识别疑似错投，现场复核并督导", alert.getId());
            case CAMERA_CALIBRATION -> createTask(gc, TaskType.CAMERA_CALIBRATION, "PROPERTY", 65,
                    "摄像头误判率升高，执行设备校准并人工抽检；校准期间错投处罚暂停自动生效", alert.getId());
            case NIGHT_PEAK -> createTask(gc, TaskType.CLEANING, "PROPERTY", 50,
                    "夜间投放高峰，增派保洁值守", alert.getId());
            case DEVICE_OFFLINE -> createTask(gc, TaskType.REPAIR, "PROPERTY", 75,
                    "设备心跳中断超过" + props.getOfflineMinutes() + "分钟，现场检修恢复联网", alert.getId());
            case NOT_CLEANED_AFTER_COLLECTION -> createTask(gc, TaskType.CLEANING, "PROPERTY", 85,
                    "清运离场后超过" + props.getNotCleanedMinutes() + "分钟未清洁，立即补做清洁", alert.getId());
            case POINTS_UNSETTLED -> createTask(gc, TaskType.RECTIFY, "PROPERTY", 45,
                    "投放积分未到账，核对称重/摄像头数据并补录积分", alert.getId());
        }
    }

    private void createTask(GovernanceCase gc, TaskType type, String role, int priority, String detail, Long alertId) {
        String key = gc.getCaseNo() + ":" + type;
        var found = taskRepository.findByDedupeKeyAndStatusNot(key, TaskStatus.DONE);
        if (found.isPresent()) {
            WorkTask t = found.get();
            if (t.getPriority() < priority) t.setPriority(priority);
            taskRepository.save(t);
            return;
        }
        WorkTask t = new WorkTask();
        t.setCaseNo(gc.getCaseNo());
        t.setRoomCode(gc.getRoomCode());
        t.setType(type);
        t.setAssigneeRole(role);
        t.setPriority(priority);
        t.setDetail(detail);
        t.setAlertId(alertId);
        t.setDedupeKey(key);
        t.setCreatedAt(timeService.now());
        taskRepository.save(t);
    }

    /** 解除垃圾房某类型的全部未闭环告警（不影响投诉类告警） */
    @Transactional
    public int resolveByType(String roomCode, AlertType type, String note) {
        List<Alert> open = alertRepository.findByRoomCodeAndStatusIn(roomCode, ACTIVE_ALERT);
        int n = 0;
        for (Alert a : open) {
            if (a.getType() == type) {
                resolveAlert(a, note, false);
                n++;
            }
        }
        return n;
    }

    @Transactional
    public void resolveAlert(Alert alert, String note, boolean ignored) {
        alert.setStatus(ignored ? AlertStatus.IGNORED : AlertStatus.RESOLVED);
        alert.setResolvedAt(timeService.now());
        alert.setResolveNote(note);
        alertRepository.save(alert);
        // 告警解除，其直接关联的现场任务自动完成
        if (alert.getId() != null) {
            for (WorkTask t : taskRepository.findByCaseNo(alert.getCaseNo())) {
                if (alert.getId().equals(t.getAlertId()) && t.getStatus() != TaskStatus.DONE) {
                    markDone(t, note);
                }
            }
        }
        if (alert.getCaseNo() != null) recalcCase(alert.getCaseNo());
    }

    @Transactional
    public void markDone(WorkTask task, String note) {
        if (task.getStatus() == TaskStatus.DONE) return;
        task.setStatus(TaskStatus.DONE);
        task.setDoneAt(timeService.now());
        task.setResultNote(note);
        taskRepository.save(task);
        recalcCase(task.getCaseNo());
    }

    @Transactional
    public void markAssigned(WorkTask task, String assigneeName) {
        task.setStatus(TaskStatus.ASSIGNED);
        task.setAssigneeName(assigneeName);
        task.setAssignedAt(timeService.now());
        taskRepository.save(task);
        recalcCase(task.getCaseNo());
    }

    /** 重算联动单：活跃告警/任务全部清零则闭环销单，释放垃圾房状态 */
    @Transactional
    public void recalcCase(String caseNo) {
        GovernanceCase gc = caseRepository.findByCaseNo(caseNo).orElse(null);
        if (gc == null) return;
        List<Alert> alerts = alertRepository.findByCaseNo(caseNo);
        boolean hasActive = alerts.stream().anyMatch(a -> ACTIVE_ALERT.contains(a.getStatus()));
        long openTasks = taskRepository.countByCaseNoAndStatusIn(caseNo,
                List.of(TaskStatus.PENDING, TaskStatus.ASSIGNED));
        gc.setOpenTaskCount((int) openTasks);

        if (!hasActive && openTasks == 0) {
            boolean anyResolved = alerts.stream().anyMatch(a -> a.getStatus() == AlertStatus.RESOLVED);
            gc.setStatus(anyResolved ? CaseStatus.RESOLVED : CaseStatus.CLOSED_FALSE);
            gc.setResolvedAt(timeService.now());
            roomRepository.findByCode(gc.getRoomCode()).ifPresent(r -> {
                if (caseNo.equals(r.getActiveCaseNo())) {
                    r.setActiveCaseNo(null);
                    roomRepository.save(r);
                }
            });
        } else if (openTasks > 0) {
            boolean touched = taskRepository.findByCaseNo(caseNo).stream()
                    .anyMatch(t -> t.getStatus() == TaskStatus.ASSIGNED || t.getStatus() == TaskStatus.DONE);
            gc.setStatus(touched ? CaseStatus.IN_PROGRESS : CaseStatus.OPEN);
        } else {
            gc.setStatus(CaseStatus.OPEN);
        }
        gc.setUpdatedAt(timeService.now());
        caseRepository.save(gc);
    }

    private AlertSeverity maxSeverity(AlertSeverity a, AlertSeverity b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private String buildSummary(GovernanceCase gc) {
        StringBuilder sb = new StringBuilder();
        for (String t : gc.getAlertTypes().split(",")) {
            if (!t.isBlank()) {
                if (sb.length() > 0) sb.append("、");
                try {
                    sb.append(AlertType.valueOf(t).getLabel());
                } catch (IllegalArgumentException ignore) {
                    sb.append(t);
                }
            }
        }
        return sb.toString();
    }
}
