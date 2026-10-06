package com.city.garbage.api;

import com.city.garbage.api.dto.AppealReviewRequest;
import com.city.garbage.api.dto.AssignTaskRequest;
import com.city.garbage.api.dto.CompleteTaskRequest;
import com.city.garbage.api.dto.ComplaintReplyRequest;
import com.city.garbage.api.dto.ComplaintRequest;
import com.city.garbage.domain.*;
import com.city.garbage.repo.AlertRepository;
import com.city.garbage.repo.AppealCaseRepository;
import com.city.garbage.repo.ComplaintRepository;
import com.city.garbage.repo.GovernanceCaseRepository;
import com.city.garbage.repo.ModelFeedbackSampleRepository;
import com.city.garbage.service.AppealReviewService;
import com.city.garbage.service.CameraStatService;
import com.city.garbage.service.ComplaintService;
import com.city.garbage.service.DisposalService;
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
    private final CameraStatService cameraStatService;
    private final AlertRepository alertRepository;
    private final GovernanceCaseRepository caseRepository;
    private final ComplaintRepository complaintRepository;
    private final AppealCaseRepository appealRepository;
    private final ModelFeedbackSampleRepository feedbackRepository;

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

    @Operation(summary = "待复核申诉列表（含证据包：视频片段、称重变化、桶位、居民说明）")
    @GetMapping("/appeals/pending")
    public List<AppealCase> pendingAppeals() {
        return appealReviewService.pendingAppeals();
    }

    @Operation(summary = "全部申诉复核单")
    @GetMapping("/appeals")
    public List<AppealCase> allAppeals() {
        return appealRepository.findAll();
    }

    @Operation(summary = "复核申诉结论",
            description = "误判成立：积分恢复、样本回传识别模型、累计摄像头误判率（超标则设备进入校准）；"
                    + "违规成立：居民收到分类教育任务")
    @PostMapping("/appeals/{id}/review")
    public AppealCase reviewAppeal(@PathVariable Long id, @Valid @RequestBody AppealReviewRequest req) {
        return appealReviewService.review(id, req.accepted(), req.reviewNote());
    }

    @Operation(summary = "摄像头误判统计与校准状态（校准中错投处罚暂停自动生效）")
    @GetMapping("/cameras")
    public List<CameraStat> cameras() {
        return cameraStatService.all();
    }

    @Operation(summary = "已回传识别模型的误判样本")
    @GetMapping("/model-feedback")
    public List<ModelFeedbackSample> modelFeedback() {
        return feedbackRepository.findAllByOrderByCreatedAtDesc();
    }

    @Operation(summary = "立即核查并补发所有未到账积分（积分未到账整改）")
    @PostMapping("/points/reconcile")
    public java.util.Map<String, Object> reconcilePoints() {
        int fixed = disposalService.settlePendingPoints();
        return java.util.Map.of("settledCount", fixed);
    }
}
