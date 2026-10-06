package com.city.garbage.service;

import com.city.garbage.domain.*;
import com.city.garbage.repo.*;
import com.city.garbage.support.BusinessException;
import com.city.garbage.support.TimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 错投申诉复核服务。居民申诉时自动调取证据包（投放视频片段、称重变化、桶位、居民说明）；
 * 复核结论驱动四条联动：
 * 误判成立 -> 积分恢复、样本回传识别模型、摄像头误判统计（误判率升高则设备进入校准）；
 * 违规成立 -> 居民收到分类教育任务，错投告警以“确认违规”闭环。
 * 复核完成后居民积分与教育记录同步更新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppealReviewService {

    private final AppealCaseRepository appealRepository;
    private final DisposalRepository disposalRepository;
    private final ResidentRepository residentRepository;
    private final GarbageRoomRepository roomRepository;
    private final PointsLedgerRepository ledgerRepository;
    private final AlertRepository alertRepository;
    private final ModelFeedbackSampleRepository feedbackRepository;
    private final EducationTaskRepository educationRepository;
    private final CameraStatService cameraStatService;
    private final LinkageEngine engine;
    private final TimeService timeService;

    /**
     * 居民对错投判罚发起申诉：建立复核单并调取证据包
     * （投放视频片段、称重变化、桶位、居民说明）。
     */
    @Transactional
    public AppealCase appeal(Long disposalId, String reason) {
        DisposalRecord r = disposalRepository.findById(disposalId)
                .orElseThrow(() -> new BusinessException("投放记录不存在: " + disposalId));
        if (!r.isWrongSort()) {
            throw new BusinessException("该记录无错投判罚，无需申诉");
        }
        if (r.getStatus() == DisposalStatus.APPEAL_ACCEPTED || r.getStatus() == DisposalStatus.APPEAL_REJECTED) {
            throw new BusinessException("该申诉已处理：" + r.getStatus());
        }
        appealRepository.findByDisposalIdAndStatus(disposalId, AppealStatus.PENDING_REVIEW)
                .ifPresent(a -> { throw new BusinessException("申诉复核中，请勿重复提交，复核单号: " + a.getId()); });

        GarbageRoom room = roomRepository.findByCode(r.getRoomCode())
                .orElseThrow(() -> new BusinessException("垃圾房不存在: " + r.getRoomCode()));

        AppealCase ap = new AppealCase();
        ap.setDisposalId(r.getId());
        ap.setResidentId(r.getResidentId());
        ap.setCardNo(r.getCardNo());
        ap.setRoomCode(r.getRoomCode());
        ap.setCameraCode(cameraCodeOf(r));
        ap.setBinType(r.getBinType());
        ap.setDetectedType(r.getDetectedType());
        ap.setConfidence(r.getConfidence());
        // 调取证据包：视频片段、称重变化、桶位、居民说明
        ap.setVideoClipUrl("/media/clips/" + r.getRoomCode() + "/" + r.getId() + ".mp4");
        ap.setWeightDeltaKg(r.getWeightKg());
        ap.setBinPosition(room.getAddress() + " · " + r.getBinType().getLabel() + "桶位");
        ap.setResidentStatement(reason);
        ap.setStatus(AppealStatus.PENDING_REVIEW);
        ap.setCreatedAt(timeService.now());
        ap = appealRepository.save(ap);

        r.setStatus(DisposalStatus.APPEALING);
        r.setViolationMessage("错投申诉复核中（复核单#" + ap.getId() + "）：" + reason);
        disposalRepository.save(r);
        log.info("申诉复核单已建立 appeal={} disposal={} camera={}", ap.getId(), r.getId(), ap.getCameraCode());
        return ap;
    }

    /** 物业/街道按复核单号复核（新链路） */
    @Transactional
    public AppealCase review(Long appealId, boolean accepted, String reviewNote) {
        AppealCase ap = appealRepository.findById(appealId)
                .orElseThrow(() -> new BusinessException("申诉复核单不存在: " + appealId));
        return doReview(ap, accepted, reviewNote);
    }

    /** 兼容旧链路：按投放记录复核 */
    @Transactional
    public AppealCase reviewByDisposal(Long disposalId, boolean accepted, String reviewNote) {
        AppealCase ap = appealRepository.findByDisposalIdAndStatus(disposalId, AppealStatus.PENDING_REVIEW)
                .orElseThrow(() -> new BusinessException("该投放记录没有待复核的申诉，请先发起申诉: " + disposalId));
        return doReview(ap, accepted, reviewNote);
    }

    private AppealCase doReview(AppealCase ap, boolean accepted, String reviewNote) {
        if (ap.getStatus() != AppealStatus.PENDING_REVIEW) {
            throw new BusinessException("该申诉已复核：" + ap.getStatus());
        }
        DisposalRecord r = disposalRepository.findById(ap.getDisposalId())
                .orElseThrow(() -> new BusinessException("投放记录不存在: " + ap.getDisposalId()));
        Resident resident = residentRepository.findById(r.getResidentId())
                .orElseThrow(() -> new BusinessException("居民不存在"));
        OffsetDateTime now = timeService.now();
        ap.setReviewedAt(now);
        ap.setReviewNote(reviewNote);

        if (accepted) {
            // 误判成立：积分恢复（处罚暂缓未扣分的无需退还）
            int refund = Math.abs(r.getPointsChange() == null ? 0 : r.getPointsChange());
            if (refund > 0) {
                resident.setTotalPoints(resident.getTotalPoints() + refund);
                residentRepository.save(resident);
                ledgerRepository.save(new PointsLedger(resident.getId(), r.getId(), refund,
                        resident.getTotalPoints(), "申诉成立退还：摄像头误判", now));
            }
            r.setPointsChange(0);
            r.setWrongSort(false);
            r.setStatus(DisposalStatus.APPEAL_ACCEPTED);
            r.setViolationMessage(refund > 0
                    ? "申诉成立，摄像头误判，已撤销违规并退还" + refund + "积分"
                    : "申诉成立，摄像头误判，违规已撤销（校准期处罚暂缓，未产生扣分）");
            ap.setStatus(AppealStatus.MISJUDGE_CONFIRMED);

            // 样本回传识别模型：视频片段 + 称重 + 真实类别
            ModelFeedbackSample sample = new ModelFeedbackSample();
            sample.setAppealId(ap.getId());
            sample.setDisposalId(r.getId());
            sample.setCameraCode(ap.getCameraCode());
            sample.setRoomCode(ap.getRoomCode());
            sample.setDetectedType(ap.getDetectedType());
            sample.setCorrectedType(ap.getBinType());
            sample.setVideoClipUrl(ap.getVideoClipUrl());
            sample.setWeightDeltaKg(ap.getWeightDeltaKg());
            sample.setStatus("SENT");
            sample.setNote("误判样本已回传识别模型训练管道：" + ap.getDetectedType().getLabel()
                    + " -> " + ap.getBinType().getLabel());
            sample.setCreatedAt(now);
            sample.setSentAt(now);
            feedbackRepository.save(sample);

            // 摄像头误判标记 + 错投告警忽略销单
            GarbageRoom room = roomRepository.findByCode(r.getRoomCode())
                    .orElseThrow(() -> new BusinessException("垃圾房不存在"));
            engine.raiseAlert(room, AlertType.CAMERA_MISJUDGE, AlertSeverity.LOW,
                    "投放#" + r.getId() + "摄像头误判，复核成立：" + reviewNote,
                    "CAMERA_MISJUDGE:DISPOSAL:" + r.getId(), r.getId(), null);
            for (Alert a : alertRepository.findByDisposalIdAndStatusIn(r.getId(),
                    List.of(AlertStatus.OPEN, AlertStatus.LINKED))) {
                engine.resolveAlert(a, "复核成立：摄像头误判，" + reviewNote, true);
            }
            // 误判率统计：同一摄像头误判率升高则设备进入校准，错投处罚暂停自动生效
            cameraStatService.recordReview(ap.getCameraCode(), ap.getRoomCode(), true);
        } else {
            // 违规成立：维持判罚，居民收到分类教育任务
            r.setStatus(DisposalStatus.APPEAL_REJECTED);
            r.setViolationMessage("申诉驳回，维持错投判罚：" + reviewNote + "；已下达分类教育任务");
            ap.setStatus(AppealStatus.VIOLATION_CONFIRMED);

            EducationTask edu = new EducationTask();
            edu.setResidentId(resident.getId());
            edu.setCardNo(resident.getCardNo());
            edu.setDisposalId(r.getId());
            edu.setAppealId(ap.getId());
            edu.setTitle("垃圾分类教育：" + r.getBinType().getLabel() + "正确投放");
            edu.setContent("您在" + ap.getBinPosition() + "的投放经复核确认为违规（识别为"
                    + ap.getDetectedType().getLabel() + "，称重" + ap.getWeightDeltaKg()
                    + "kg）。请学习《生活垃圾分类指引》中" + r.getBinType().getLabel()
                    + "与" + ap.getDetectedType().getLabel() + "的区分要点并完成打卡。");
            edu.setStatus(EducationStatus.PENDING);
            edu.setCreatedAt(now);
            educationRepository.save(edu);

            // 复核确认违规：错投告警以真实处置闭环（非忽略）
            for (Alert a : alertRepository.findByDisposalIdAndStatusIn(r.getId(),
                    List.of(AlertStatus.OPEN, AlertStatus.LINKED))) {
                if (a.getType() == AlertType.MISSORT) {
                    engine.resolveAlert(a, "复核确认违规，已下达分类教育任务：" + reviewNote, false);
                }
            }
            cameraStatService.recordReview(ap.getCameraCode(), ap.getRoomCode(), false);
        }
        disposalRepository.save(r);
        return appealRepository.save(ap);
    }

    /** 居民完成分类教育任务（学习打卡），更新教育记录 */
    @Transactional
    public EducationTask completeEducation(Long educationId, String cardNo) {
        EducationTask edu = educationRepository.findById(educationId)
                .orElseThrow(() -> new BusinessException("教育任务不存在: " + educationId));
        if (!edu.getCardNo().equals(cardNo)) {
            throw new BusinessException("只能完成本人的教育任务");
        }
        if (edu.getStatus() == EducationStatus.COMPLETED) {
            return edu;
        }
        edu.setStatus(EducationStatus.COMPLETED);
        edu.setCompletedAt(timeService.now());
        return educationRepository.save(edu);
    }

    public List<AppealCase> pendingAppeals() {
        return appealRepository.findByStatusOrderByCreatedAtAsc(AppealStatus.PENDING_REVIEW);
    }

    public List<AppealCase> residentAppeals(Long residentId) {
        return appealRepository.findByResidentIdOrderByCreatedAtDesc(residentId);
    }

    public List<EducationTask> residentEducation(Long residentId) {
        return educationRepository.findByResidentIdOrderByCreatedAtDesc(residentId);
    }

    public long pendingEducationCount(Long residentId) {
        return educationRepository.countByResidentIdAndStatus(residentId, EducationStatus.PENDING);
    }

    private String cameraCodeOf(DisposalRecord r) {
        return r.getCameraCode() != null ? r.getCameraCode() : "CAM-" + r.getRoomCode();
    }
}
