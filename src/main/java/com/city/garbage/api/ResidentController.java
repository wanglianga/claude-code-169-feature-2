package com.city.garbage.api;

import com.city.garbage.api.dto.AppealRequest;
import com.city.garbage.api.dto.AppealReviewRequest;
import com.city.garbage.api.dto.ComplaintRequest;
import com.city.garbage.api.dto.DisposalRequest;
import com.city.garbage.api.dto.EducationCompleteRequest;
import com.city.garbage.domain.AppealReview;
import com.city.garbage.domain.Complaint;
import com.city.garbage.domain.DisposalRecord;
import com.city.garbage.domain.EducationRecord;
import com.city.garbage.domain.PointsLedger;
import com.city.garbage.domain.Resident;
import com.city.garbage.repo.PointsLedgerRepository;
import com.city.garbage.repo.ResidentRepository;
import com.city.garbage.service.AppealReviewService;
import com.city.garbage.service.ComplaintService;
import com.city.garbage.service.DisposalService;
import com.city.garbage.service.EducationService;
import com.city.garbage.support.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 居民端：刷卡/扫码投放、积分余额与流水、违规提示、错投申诉复核、分类教育、投诉 */
@Tag(name = "居民端", description = "投放、积分、违规提示、误判申诉复核、分类教育")
@RestController
@RequestMapping("/api/resident")
@RequiredArgsConstructor
public class ResidentController {

    private final DisposalService disposalService;
    private final AppealReviewService appealReviewService;
    private final EducationService educationService;
    private final ComplaintService complaintService;
    private final ResidentRepository residentRepository;
    private final PointsLedgerRepository ledgerRepository;

    @Operation(summary = "刷卡/扫码投放",
            description = "记录小区/楼栋/垃圾类型/重量/桶位；摄像头识别不一致判错投并提示；"
                    + "断网时 deferPoints=true 积分暂挂；摄像头校准期间错投处罚暂停自动生效")
    @PostMapping("/disposals")
    public DisposalRecord dispose(@Valid @RequestBody DisposalRequest req) {
        return disposalService.dispose(req.cardNo(), req.roomCode(), req.binType(),
                req.detectedType(), req.weightKg(), req.confidence(), req.deferPoints(),
                req.cameraCode(), req.binPosition());
    }

    @Operation(summary = "我的积分与违规概览")
    @GetMapping("/{cardNo}/profile")
    public Map<String, Object> profile(@PathVariable String cardNo) {
        Resident r = requireResident(cardNo);
        List<DisposalRecord> recent = disposalService.residentHistory(r.getId());
        long wrong = recent.stream().filter(DisposalRecord::isWrongSort).count();
        long eduPending = educationService.residentRecords(r.getId()).stream()
                .filter(e -> e.getStatus() == com.city.garbage.domain.EducationStatus.ASSIGNED).count();
        return Map.of(
                "cardNo", r.getCardNo(),
                "name", r.getName() == null ? "" : r.getName(),
                "buildingName", r.getBuildingName() == null ? "" : r.getBuildingName(),
                "totalPoints", r.getTotalPoints(),
                "disposalCount", recent.size(),
                "wrongSortCount", wrong,
                "educationPendingCount", eduPending,
                "latestViolation", recent.stream().filter(DisposalRecord::isWrongSort).findFirst()
                        .map(DisposalRecord::getViolationMessage).orElse(""));
    }

    @Operation(summary = "我的投放记录（含每次违规提示与积分变动）")
    @GetMapping("/{cardNo}/disposals")
    public List<DisposalRecord> disposals(@PathVariable String cardNo) {
        return disposalService.residentHistory(requireResident(cardNo).getId());
    }

    @Operation(summary = "我的积分流水")
    @GetMapping("/{cardNo}/points-ledger")
    public List<PointsLedger> ledger(@PathVariable String cardNo) {
        return ledgerRepository.findByResidentIdOrderByCreatedAtDesc(requireResident(cardNo).getId());
    }

    @Operation(summary = "对错投判罚发起申诉（服务自动调取视频/重量/桶位证据进入复核）")
    @PostMapping("/disposals/{id}/appeal")
    public AppealReview appeal(@PathVariable Long id, @Valid @RequestBody AppealRequest req) {
        return appealReviewService.submitAppeal(id, req.reason());
    }

    @Operation(summary = "查看某投放申诉复核单（含视频片段、重量变化、桶位、结论）")
    @GetMapping("/disposals/{id}/appeal")
    public AppealReview appealDetail(@PathVariable Long id) {
        return appealReviewService.getByDisposal(id);
    }

    @Operation(summary = "我的申诉复核单列表")
    @GetMapping("/{cardNo}/appeals")
    public List<AppealReview> appeals(@PathVariable String cardNo) {
        return appealReviewService.residentAppeals(requireResident(cardNo).getId());
    }

    @Operation(summary = "复核结论（演示/自助复核入口；物业端另有复核队列接口）")
    @PostMapping("/disposals/{id}/appeal-review")
    public AppealReview review(@PathVariable Long id, @Valid @RequestBody AppealReviewRequest req) {
        return appealReviewService.review(id, req.accepted(), req.reviewer(), req.reviewNote());
    }

    @Operation(summary = "我的分类教育任务（违规成立后派发）")
    @GetMapping("/{cardNo}/education")
    public List<EducationRecord> education(@PathVariable String cardNo) {
        return educationService.residentRecords(requireResident(cardNo).getId());
    }

    @Operation(summary = "完成分类教育任务（学习+答题后回填结果，复核单闭环）")
    @PostMapping("/education/{id}/complete")
    public EducationRecord completeEducation(@PathVariable Long id,
                                             @RequestBody(required = false) EducationCompleteRequest req) {
        return educationService.complete(id, req == null ? null : req.resultNote());
    }

    @Operation(summary = "居民提交投诉（如异味投诉，自动并入垃圾房联动单）")
    @PostMapping("/complaints")
    public Complaint complaint(@Valid @RequestBody ComplaintRequest req) {
        return complaintService.create(req.residentId(), req.contact(), req.roomCode(), req.type(), req.content());
    }

    private Resident requireResident(String cardNo) {
        return residentRepository.findByCardNo(cardNo)
                .orElseThrow(() -> new BusinessException("卡号未登记: " + cardNo));
    }
}
