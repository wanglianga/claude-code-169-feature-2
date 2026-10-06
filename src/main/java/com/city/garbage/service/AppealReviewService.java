package com.city.garbage.service;

import com.city.garbage.config.LinkageProperties;
import com.city.garbage.domain.*;
import com.city.garbage.event.DeviceEventType;
import com.city.garbage.kafka.GovernanceEventPublisher;
import com.city.garbage.repo.*;
import com.city.garbage.support.BusinessException;
import com.city.garbage.support.TimeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 错投申诉复核：
 * 居民对“玻璃瓶投入厨余桶”等错投识别提出申诉后，服务调取
 * 投放视频片段、重量变化、桶位与居民说明形成复核证据；
 * 复核结论二选一——
 *   误判成立：撤销违规、恢复积分，误判样本回传识别模型，并计入摄像头误判率；
 *   违规成立：维持处罚，向居民派发分类教育任务，完成后更新教育记录并闭环。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppealReviewService {

    private final AppealReviewRepository appealRepository;
    private final DisposalRepository disposalRepository;
    private final ResidentRepository residentRepository;
    private final GarbageRoomRepository roomRepository;
    private final PointsLedgerRepository ledgerRepository;
    private final AlertRepository alertRepository;
    private final DeviceEventLogRepository eventLogRepository;
    private final ModelFeedbackSampleRepository sampleRepository;
    private final ModelFeedbackService modelFeedbackService;
    private final CameraCalibrationService cameraService;
    private final EducationService educationService;
    private final LinkageEngine engine;
    private final GovernanceEventPublisher governancePublisher;
    private final LinkageProperties props;
    private final TimeService timeService;
    private final ObjectMapper objectMapper;

    private static final List<AppealStatus> PENDING_REVIEW =
            List.of(AppealStatus.SUBMITTED, AppealStatus.EVIDENCE_COLLECTED, AppealStatus.REVIEWING);
    private static final List<AlertStatus> ACTIVE_ALERT =
            List.of(AlertStatus.OPEN, AlertStatus.LINKED);

    /** 居民提交申诉：建立复核单并自动调取视频/称重/桶位证据 */
    @Transactional
    public AppealReview submitAppeal(Long disposalId, String residentStatement) {
        DisposalRecord r = requireWrongSortDisposal(disposalId);
        if (appealRepository.findByDisposalId(disposalId).isPresent()) {
            throw new BusinessException("该投放已提交申诉，请勿重复提交");
        }
        OffsetDateTime now = timeService.now();

        AppealReview a = new AppealReview();
        a.setAppealNo("APPEAL-" + r.getRoomCode() + "-" + disposalId + "-" + System.currentTimeMillis() % 100000);
        a.setDisposalId(disposalId);
        a.setResidentId(r.getResidentId());
        a.setRoomCode(r.getRoomCode());
        a.setCameraCode(r.getCameraCode());
        a.setBinType(r.getBinType());
        a.setDetectedType(r.getDetectedType());
        a.setResidentStatement(residentStatement);
        a.setStatus(AppealStatus.SUBMITTED);
        a.setCreatedAt(now);
        a.setSubmittedAt(now);
        a = appealRepository.save(a);

        collectEvidence(a, r);
        a.setStatus(AppealStatus.EVIDENCE_COLLECTED);
        a.setEvidenceCollectedAt(timeService.now());

        r.setStatus(DisposalStatus.APPEALING);
        r.setViolationMessage("错投申诉复核中：" + residentStatement);
        disposalRepository.save(r);
        log.info("申诉已受理 appealNo={} disposalId={} 证据已调取", a.getAppealNo(), disposalId);
        return appealRepository.save(a);
    }

    /** 调取投放视频片段、重量变化、桶位（居民说明已随申诉带入） */
    private void collectEvidence(AppealReview a, DisposalRecord r) {
        OffsetDateTime base = r.getCreatedAt() != null ? r.getCreatedAt() : timeService.now();
        int win = props.getAppealEvidenceWindowSeconds();
        OffsetDateTime from = base.minusSeconds(win);
        OffsetDateTime to = base.plusSeconds(win);

        // 1) 视频片段：取投放时间窗内的摄像头识别事件，缺失时按投放记录生成片段引用
        List<DeviceEventLog> cameraLogs = eventLogRepository
                .findByRoomCodeAndEventTypeAndOccurredAtBetweenOrderByOccurredAtAsc(
                        r.getRoomCode(), DeviceEventType.CAMERA_DETECTION.name(), from, to);
        OffsetDateTime clipStart = base.minusSeconds(15);
        OffsetDateTime clipEnd = base.plusSeconds(15);
        String cameraCode = r.getCameraCode();
        if (!cameraLogs.isEmpty()) {
            DeviceEventLog first = cameraLogs.get(0);
            DeviceEventLog last = cameraLogs.get(cameraLogs.size() - 1);
            clipStart = first.getOccurredAt();
            clipEnd = last.getOccurredAt();
            if (first.getDeviceCode() != null) cameraCode = first.getDeviceCode();
        }
        String dayPart = base.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE).replace("-", "");
        a.setVideoClip("clips/" + r.getRoomCode() + "/" + dayPart + "/" + cameraCode + "-d" + r.getId() + ".mp4");
        a.setVideoStartAt(clipStart);
        a.setVideoEndAt(clipEnd);
        if (a.getCameraCode() == null) a.setCameraCode(cameraCode);

        // 2) 重量变化：优先用地磅上报，窗口内首/末读数作为投放前后重量
        List<DeviceEventLog> weightLogs = eventLogRepository
                .findByRoomCodeAndEventTypeAndOccurredAtBetweenOrderByOccurredAtAsc(
                        r.getRoomCode(), DeviceEventType.WEIGHT.name(), from, to);
        Double before = null;
        Double after = null;
        if (!weightLogs.isEmpty()) {
            before = readWeight(weightLogs.get(0));
            after = readWeight(weightLogs.get(weightLogs.size() - 1));
        }
        a.setWeightBeforeKg(before);
        a.setWeightAfterKg(after);
        double delta;
        if (before != null && after != null) {
            delta = after - before;
        } else {
            delta = r.getWeightKg() != null ? r.getWeightKg() : 0.0;
        }
        a.setWeightDeltaKg(Math.round(delta * 100.0) / 100.0);

        // 3) 桶位
        a.setBinPosition(r.getBinPosition() != null ? r.getBinPosition()
                : (r.getBinType() != null ? r.getBinType().getLabel() + "-1号位" : "未知桶位"));
    }

    private Double readWeight(DeviceEventLog log) {
        try {
            JsonNode node = objectMapper.readTree(log.getPayload());
            JsonNode w = node.get("weightKg");
            return w != null && !w.isNull() ? w.asDouble() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 复核结论。
     * @param accepted true=误判成立（恢复积分+样本回传）；false=违规成立（派分类教育任务）
     */
    @Transactional
    public AppealReview review(Long disposalId, boolean accepted, String reviewer, String reviewNote) {
        DisposalRecord r = requireWrongSortDisposal(disposalId);
        AppealReview a = appealRepository.findByDisposalId(disposalId)
                .orElseThrow(() -> new BusinessException("尚未提交申诉或证据未调取，请先发起申诉"));
        if (a.getStatus() == AppealStatus.MISJUDGE_CONFIRMED
                || a.getStatus() == AppealStatus.VIOLATION_CONFIRMED
                || a.getStatus() == AppealStatus.CLOSED) {
            throw new BusinessException("该申诉已复核：" + a.getStatus());
        }
        a.setReviewer(reviewer == null || reviewer.isBlank() ? "现场复核员" : reviewer);
        a.setReviewNote(reviewNote);
        a.setStatus(AppealStatus.REVIEWING);
        appealRepository.save(a);

        return accepted ? confirmMisjudge(a, r, reviewNote) : confirmViolation(a, r, reviewNote);
    }

    /** 误判成立：撤销违规、恢复积分，样本回传识别模型，计入摄像头误判率 */
    private AppealReview confirmMisjudge(AppealReview a, DisposalRecord r, String reviewNote) {
        OffsetDateTime now = timeService.now();
        Resident resident = residentRepository.findById(r.getResidentId())
                .orElseThrow(() -> new BusinessException("居民不存在"));

        // 1) 恢复积分（校准期暂停处罚的记录扣分为0，退还同样为0）
        int refund = r.getPointsChange() != null && r.getPointsChange() < 0
                ? Math.abs(r.getPointsChange()) : 0;
        if (refund > 0) {
            resident.setTotalPoints(resident.getTotalPoints() + refund);
            residentRepository.save(resident);
            ledgerRepository.save(new PointsLedger(resident.getId(), r.getId(), refund,
                    resident.getTotalPoints(), "申诉复核成立：摄像头误判，积分恢复", now));
        }
        r.setPointsChange(0);
        r.setWrongSort(false);
        r.setPenaltySuspended(false);
        r.setStatus(DisposalStatus.APPEAL_ACCEPTED);
        r.setViolationMessage("复核误判成立，已撤销违规" + (refund > 0 ? "并恢复" + refund + "积分" : "（校准期未扣分）"));
        disposalRepository.save(r);

        // 2) 误判样本回传识别模型（纠偏/再训练）
        ModelFeedbackSample sample = new ModelFeedbackSample();
        sample.setDisposalId(r.getId());
        sample.setAppealId(a.getId());
        sample.setCameraCode(a.getCameraCode());
        sample.setRoomCode(a.getRoomCode());
        sample.setPredictedType(r.getDetectedType());
        sample.setActualType(r.getBinType());
        sample.setVideoClip(a.getVideoClip());
        sample.setReviewNote(reviewNote);
        sample.setCreatedAt(now);
        sample = sampleRepository.save(sample);
        modelFeedbackService.send(sample);
        a.setFeedbackSampleId(sample.getId());

        // 3) 错投/误判告警忽略销单
        GarbageRoom room = roomRepository.findByCode(r.getRoomCode()).orElseThrow();
        engine.raiseAlert(room, AlertType.CAMERA_MISJUDGE, AlertSeverity.LOW,
                "投放#" + r.getId() + "摄像头误判，复核成立：" + reviewNote,
                "CAMERA_MISJUDGE:DISPOSAL:" + r.getId(), r.getId(), null);
        for (Alert al : alertRepository.findByDisposalIdAndStatusIn(r.getId(), ACTIVE_ALERT)) {
            engine.resolveAlert(al, "复核成立：摄像头误判，" + reviewNote, true);
        }

        // 4) 计入该摄像头误判率（可能触发校准与处罚暂停）
        cameraService.recordReviewOutcome(a.getCameraCode(), a.getRoomCode(), true);

        a.setMisjudge(true);
        a.setStatus(AppealStatus.MISJUDGE_CONFIRMED);
        a.setReviewedAt(now);
        a.setClosedAt(now);
        appealRepository.save(a);
        publishOutcome(a, resident, "MISJUDGE_CONFIRMED", refund, null);
        log.info("申诉{}复核为误判成立，积分恢复{}，样本#{}已回传模型", a.getAppealNo(), refund, sample.getId());
        return a;
    }

    /** 违规成立：维持处罚，派发分类教育任务，完成后更新教育记录 */
    private AppealReview confirmViolation(AppealReview a, DisposalRecord r, String reviewNote) {
        OffsetDateTime now = timeService.now();
        Resident resident = residentRepository.findById(r.getResidentId())
                .orElseThrow(() -> new BusinessException("居民不存在"));

        // 违规属实：错投告警按真实违规闭环（不忽略），现场复核任务随之完成
        for (Alert al : alertRepository.findByDisposalIdAndStatusIn(r.getId(), ACTIVE_ALERT)) {
            engine.resolveAlert(al, "复核违规成立：" + reviewNote, false);
        }

        // 派发分类教育任务
        EducationRecord edu = educationService.assign(r.getResidentId(), r.getId(), a.getId(),
                r.getRoomCode(), r.getBinType(), r.getDetectedType());
        a.setEducationRecordId(edu.getId());

        r.setStatus(DisposalStatus.APPEAL_REJECTED);
        r.setViolationMessage("复核违规成立，维持处罚；请完成分类教育任务（教育记录#" + edu.getId() + "）");
        disposalRepository.save(r);

        // 计入该摄像头复核结果（违规属实，降低误判率）
        cameraService.recordReviewOutcome(a.getCameraCode(), a.getRoomCode(), false);

        a.setMisjudge(false);
        a.setStatus(AppealStatus.VIOLATION_CONFIRMED);
        a.setReviewedAt(now);
        appealRepository.save(a);
        publishOutcome(a, resident, "VIOLATION_CONFIRMED", 0, edu.getId());
        log.info("申诉{}复核为违规成立，已派分类教育任务#{}", a.getAppealNo(), edu.getId());
        return a;
    }

    private void publishOutcome(AppealReview a, Resident resident, String outcome, int refund, Long educationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("appealNo", a.getAppealNo());
        payload.put("disposalId", a.getDisposalId());
        payload.put("residentCardNo", resident.getCardNo());
        payload.put("roomCode", a.getRoomCode());
        payload.put("cameraCode", a.getCameraCode());
        payload.put("outcome", outcome);
        payload.put("pointsRefunded", refund);
        payload.put("educationRecordId", educationId);
        payload.put("feedbackSampleId", a.getFeedbackSampleId());
        governancePublisher.publish(a.getRoomCode(), payload);
    }

    private DisposalRecord requireWrongSortDisposal(Long disposalId) {
        DisposalRecord r = disposalRepository.findById(disposalId)
                .orElseThrow(() -> new BusinessException("投放记录不存在: " + disposalId));
        if (!r.isWrongSort()) {
            throw new BusinessException("该记录无错投判罚，无需申诉复核");
        }
        return r;
    }

    public AppealReview getByDisposal(Long disposalId) {
        return appealRepository.findByDisposalId(disposalId)
                .orElseThrow(() -> new BusinessException("该投放暂无申诉复核单"));
    }

    public List<AppealReview> pendingReviewQueue() {
        return appealRepository.findByStatusInOrderByCreatedAtAsc(PENDING_REVIEW);
    }

    public List<AppealReview> residentAppeals(Long residentId) {
        return appealRepository.findByResidentIdOrderByCreatedAtDesc(residentId);
    }
}
