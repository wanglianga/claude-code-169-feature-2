package com.city.garbage.api;

import com.city.garbage.api.dto.AssignTaskRequest;
import com.city.garbage.api.dto.CalibrationCompleteRequest;
import com.city.garbage.api.dto.CompleteTaskRequest;
import com.city.garbage.api.dto.ComplaintReplyRequest;
import com.city.garbage.api.dto.ComplaintRequest;
import com.city.garbage.api.dto.AppealReviewRequest;
import com.city.garbage.domain.*;
import com.city.garbage.repo.AlertRepository;
import com.city.garbage.repo.ComplaintRepository;
import com.city.garbage.repo.GovernanceCaseRepository;
import com.city.garbage.service.AppealReviewService;
import com.city.garbage.service.CameraCalibrationService;
import com.city.garbage.service.ComplaintService;
import com.city.garbage.service.DisposalService;
import com.city.garbage.service.ModelFeedbackService;
import com.city.garbage.service.WorkTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 物业端：待处理任务（保洁/消杀/维修/复核/整改）、联动单、告警、投诉处理、积分补发 */
@Tag(name = "物业端", description = "待处理任务、派发/完成、联动单、投诉整改、积分核查补发")
@RestController
@RequestMapping("/api/property")
@RequiredArgsConstructor
public class PropertyController {

    private final WorkTaskService taskService;
    private final ComplaintService complaintService;
    private final DisposalService disposalService;
    private final AppealReviewService appealReviewService;
    private final CameraCalibrationService cameraCalibrationService;
    private final ModelFeedbackService modelFeedbackService;
    private final AlertRepository alertRepository;
    private final GovernanceCaseRepository caseRepository;
    private final ComplaintRepository complaintRepository;

    @Operation(summary = "待处理任务列表（按优先级排序：保洁/消杀/维修/错投复核/整改）")
    @GetMapping("/tasks/pending")
    public List<WorkTask> pendingTasks() {
        return taskService.propertyPending();
    }

    @Operation(summary = "派发任务给保洁/维修人员")
    @PostMapping("/tasks/{id}/assign")
    public WorkTask assign(@PathVariable Long id, @Valid @RequestBody AssignTaskRequest req) {
        return taskService.assign(id, req.assigneeName());
    }

    @Operation(summary = "完成任务并回填现场处置结果（同步解除关联告警、刷新联动单）")
    @PostMapping("/tasks/{id}/complete")
    public WorkTask complete(@PathVariable Long id, @Valid @RequestBody CompleteTaskRequest req) {
        return taskService.complete(id, req.note());
    }

    @Operation(summary = "某联动单下全部任务")
    @GetMapping("/cases/{caseNo}/tasks")
    public List<WorkTask> caseTasks(@PathVariable String caseNo) {
        return taskService.byCase(caseNo);
    }

    @Operation(summary = "某垃圾房当前未闭环告警")
    @GetMapping("/rooms/{roomCode}/alerts")
    public List<Alert> roomAlerts(@PathVariable String roomCode) {
        return taskService.openAlerts(roomCode);
    }

    @Operation(summary = "全部联动单（含已闭环）")
    @GetMapping("/cases")
    public List<GovernanceCase> cases() {
        return caseRepository.findAll();
    }

    @Operation(summary = "联动单详情（含告警清单与任务清单）")
    @GetMapping("/cases/{caseNo}")
    public java.util.Map<String, Object> caseDetail(@PathVariable String caseNo) {
        GovernanceCase gc = caseRepository.findByCaseNo(caseNo)
                .orElseThrow(() -> new com.city.garbage.support.BusinessException("联动单不存在: " + caseNo));
        return java.util.Map.of(
                "caseInfo", gc,
                "alerts", alertRepository.findByCaseNo(caseNo),
                "tasks", taskService.byCase(caseNo));
    }

    @Operation(summary = "代居民登记投诉（如电话/上门异味投诉）")
    @PostMapping("/complaints")
    public Complaint createComplaint(@Valid @RequestBody ComplaintRequest req) {
        return complaintService.create(req.residentId(), req.contact(), req.roomCode(), req.type(), req.content());
    }

    @Operation(summary = "投诉列表")
    @GetMapping("/complaints")
    public List<Complaint> complaints() {
        return complaintRepository.findAll();
    }

    @Operation(summary = "投诉整改反馈并闭环（同步解除投诉告警）")
    @PostMapping("/complaints/{id}/reply")
    public Complaint replyComplaint(@PathVariable Long id, @Valid @RequestBody ComplaintReplyRequest req) {
        return complaintService.reply(id, req.reply());
    }

    @Operation(summary = "立即核查并补发所有未到账积分（积分未到账整改）")
    @PostMapping("/points/reconcile")
    public java.util.Map<String, Object> reconcilePoints() {
        int fixed = disposalService.settlePendingPoints();
        return java.util.Map.of("settledCount", fixed);
    }

    @Operation(summary = "错投申诉复核队列（已取证待复核）")
    @GetMapping("/appeals/pending")
    public List<AppealReview> pendingAppeals() {
        return appealReviewService.pendingReviewQueue();
    }

    @Operation(summary = "某投放的申诉复核单（含视频片段/重量变化/桶位/居民说明）")
    @GetMapping("/appeals/disposals/{disposalId}")
    public AppealReview appealDetail(@PathVariable Long disposalId) {
        return appealReviewService.getByDisposal(disposalId);
    }

    @Operation(summary = "复核申诉：accepted=true 误判成立(恢复积分+样本回传)；false 违规成立(派分类教育)")
    @PostMapping("/appeals/disposals/{disposalId}/review")
    public AppealReview reviewAppeal(@PathVariable Long disposalId,
                                     @Valid @RequestBody AppealReviewRequest req) {
        return appealReviewService.review(disposalId, req.accepted(), req.reviewer(), req.reviewNote());
    }

    @Operation(summary = "全部摄像头识别质量（误判率与校准状态）")
    @GetMapping("/cameras")
    public List<CameraDevice> cameras() {
        return cameraCalibrationService.all();
    }

    @Operation(summary = "校准中的摄像头（错投处罚暂停自动生效）")
    @GetMapping("/cameras/calibrating")
    public List<CameraDevice> calibratingCameras() {
        return cameraCalibrationService.calibrating();
    }

    @Operation(summary = "完成摄像头校准（清零误判率窗口、解除告警/校准任务、恢复处罚自动生效）")
    @PostMapping("/cameras/{cameraCode}/calibration/complete")
    public CameraDevice completeCalibration(@PathVariable String cameraCode,
                                            @Valid @RequestBody CalibrationCompleteRequest req) {
        return cameraCalibrationService.completeCalibration(cameraCode, req.note());
    }

    @Operation(summary = "重传所有待回传/失败的误判样本到识别模型")
    @PostMapping("/model-feedback/retry")
    public java.util.Map<String, Object> retryFeedback() {
        return java.util.Map.of("sentCount", modelFeedbackService.retryPending());
    }
}
