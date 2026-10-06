package com.city.garbage.api;

import com.city.garbage.api.dto.AppealRequest;
import com.city.garbage.api.dto.AppealReviewRequest;
import com.city.garbage.api.dto.ComplaintRequest;
import com.city.garbage.api.dto.DisposalRequest;
import com.city.garbage.domain.AppealCase;
import com.city.garbage.domain.Complaint;
import com.city.garbage.domain.DisposalRecord;
import com.city.garbage.domain.EducationTask;
import com.city.garbage.domain.PointsLedger;
import com.city.garbage.domain.Resident;
import com.city.garbage.repo.DisposalRepository;
import com.city.garbage.repo.PointsLedgerRepository;
import com.city.garbage.repo.ResidentRepository;
import com.city.garbage.service.AppealReviewService;
import com.city.garbage.service.ComplaintService;
import com.city.garbage.service.DisposalService;
import com.city.garbage.support.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 居民端：刷卡/扫码投放、积分余额与流水、违规提示、错投申诉复核、分类教育任务 */
@Tag(name = "居民端", description = "投放、积分、违规提示、错投申诉复核、分类教育任务")
@RestController
@RequestMapping("/api/resident")
@RequiredArgsConstructor
public class ResidentController {

    private final DisposalService disposalService;
    private final AppealReviewService appealReviewService;
    private final ComplaintService complaintService;
    private final ResidentRepository residentRepository;
    private final PointsLedgerRepository ledgerRepository;
    private final DisposalRepository disposalRepository;

    @Operation(summary = "刷卡/扫码投放",
            description = "记录小区/楼栋/垃圾类型/重量；摄像头识别不一致判错投并提示；"
                    + "摄像头校准中错投处罚暂缓生效；断网时 deferPoints=true 积分暂挂，核查后自动补发")
    @PostMapping("/disposals")
    public DisposalRecord dispose(@Valid @RequestBody DisposalRequest req) {
        return disposalService.dispose(req.cardNo(), req.roomCode(), req.binType(),
                req.detectedType(), req.weightKg(), req.confidence(), req.cameraCode(), req.deferPoints());
    }

    @Operation(summary = "我的积分与违规概览")
    @GetMapping("/{cardNo}/profile")
    public Map<String, Object> profile(@PathVariable String cardNo) {
        Resident r = residentRepository.findByCardNo(cardNo)
                .orElseThrow(() -> new BusinessException("卡号未登记: " + cardNo));
        List<DisposalRecord> recent = disposalService.residentHistory(r.getId());
        long wrong = recent.stream().filter(DisposalRecord::isWrongSort).count();
        return Map.of(
                "cardNo", r.getCardNo(),
                "name", r.getName() == null ? "" : r.getName(),
                "buildingName", r.getBuildingName() == null ? "" : r.getBuildingName(),
                "totalPoints", r.getTotalPoints(),
                "disposalCount", recent.size(),
                "wrongSortCount", wrong,
                "educationPending", appealReviewService.pendingEducationCount(r.getId()),
                "latestViolation", recent.stream().filter(DisposalRecord::isWrongSort).findFirst()
                        .map(DisposalRecord::getViolationMessage).orElse(""));
    }

    @Operation(summary = "我的投放记录（含每次违规提示与积分变动）")
    @GetMapping("/{cardNo}/disposals")
    public List<DisposalRecord> disposals(@PathVariable String cardNo) {
        Resident r = residentRepository.findByCardNo(cardNo)
                .orElseThrow(() -> new BusinessException("卡号未登记: " + cardNo));
        return disposalService.residentHistory(r.getId());
    }

    @Operation(summary = "我的积分流水")
    @GetMapping("/{cardNo}/points-ledger")
    public List<PointsLedger> ledger(@PathVariable String cardNo) {
        Resident r = residentRepository.findByCardNo(cardNo)
                .orElseThrow(() -> new BusinessException("卡号未登记: " + cardNo));
        return ledgerRepository.findByResidentIdOrderByCreatedAtDesc(r.getId());
    }

    @Operation(summary = "对摄像头错投判罚发起申诉",
            description = "服务自动调取证据包（投放视频片段、称重变化、桶位、居民说明）生成复核单")
    @PostMapping("/disposals/{id}/appeal")
    public AppealCase appeal(@PathVariable Long id, @Valid @RequestBody AppealRequest req) {
        return appealReviewService.appeal(id, req.reason());
    }

    @Operation(summary = "我的申诉复核单（含证据包与复核结论）")
    @GetMapping("/{cardNo}/appeals")
    public List<AppealCase> myAppeals(@PathVariable String cardNo) {
        Resident r = residentRepository.findByCardNo(cardNo)
                .orElseThrow(() -> new BusinessException("卡号未登记: " + cardNo));
        return appealReviewService.residentAppeals(r.getId());
    }

    @Operation(summary = "我的分类教育任务（复核违规成立后下达）")
    @GetMapping("/{cardNo}/education")
    public List<EducationTask> myEducation(@PathVariable String cardNo) {
        Resident r = residentRepository.findByCardNo(cardNo)
                .orElseThrow(() -> new BusinessException("卡号未登记: " + cardNo));
        return appealReviewService.residentEducation(r.getId());
    }

    @Operation(summary = "完成分类教育任务（学习打卡）")
    @PostMapping("/{cardNo}/education/{id}/complete")
    public EducationTask completeEducation(@PathVariable String cardNo, @PathVariable Long id) {
        return appealReviewService.completeEducation(id, cardNo);
    }

    @Operation(summary = "物业/街道现场复核申诉结论（兼容旧链路，按投放记录复核）")
    @PostMapping("/disposals/{id}/appeal-review")
    public DisposalRecord review(@PathVariable Long id, @Valid @RequestBody AppealReviewRequest req) {
        appealReviewService.reviewByDisposal(id, req.accepted(), req.reviewNote());
        return disposalRepository.findById(id)
                .orElseThrow(() -> new BusinessException("投放记录不存在: " + id));
    }

    @Operation(summary = "居民提交投诉（如异味投诉，自动并入垃圾房联动单）")
    @PostMapping("/complaints")
    public Complaint complaint(@Valid @RequestBody ComplaintRequest req) {
        return complaintService.create(req.residentId(), req.contact(), req.roomCode(), req.type(), req.content());
    }
}
