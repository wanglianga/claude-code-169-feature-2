package com.city.garbage.service;

import com.city.garbage.domain.*;
import com.city.garbage.repo.*;
import com.city.garbage.support.BusinessException;
import com.city.garbage.support.TimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 居民分类教育：申诉复核判定违规成立后，向居民派发分类教育任务（学习+答题），
 * 完成后更新居民教育记录并闭环对应复核单。教育记录与真实投放一一对应。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EducationService {

    private final EducationRecordRepository educationRepository;
    private final AppealReviewRepository appealRepository;
    private final TimeService timeService;

    /** 违规成立：派发分类教育任务 */
    @Transactional
    public EducationRecord assign(Long residentId, Long disposalId, Long appealId,
                                  String roomCode, WasteType binType, WasteType detectedType) {
        EducationRecord e = new EducationRecord();
        e.setResidentId(residentId);
        e.setDisposalId(disposalId);
        e.setAppealId(appealId);
        e.setRoomCode(roomCode);
        e.setTopic(buildTopic(binType, detectedType));
        e.setContent("请学习《生活垃圾分类指引》中“" + (binType == null ? "厨余垃圾" : binType.getLabel())
                + "”投放要求并完成 5 道分类答题，全部答对即完成本次教育任务。");
        e.setStatus(EducationStatus.ASSIGNED);
        e.setAssignedAt(timeService.now());
        return educationRepository.save(e);
    }

    /** 居民完成教育任务：更新教育记录，并把关联复核单闭环 */
    @Transactional
    public EducationRecord complete(Long educationId, String resultNote) {
        EducationRecord e = educationRepository.findById(educationId)
                .orElseThrow(() -> new BusinessException("教育任务不存在: " + educationId));
        if (e.getStatus() == EducationStatus.DONE) {
            throw new BusinessException("该教育任务已完成");
        }
        e.setStatus(EducationStatus.DONE);
        e.setCompletedAt(timeService.now());
        e.setResultNote(resultNote == null || resultNote.isBlank() ? "已完成学习与答题" : resultNote);
        educationRepository.save(e);

        if (e.getAppealId() != null) {
            appealRepository.findById(e.getAppealId()).ifPresent(a -> {
                if (a.getStatus() != AppealStatus.CLOSED) {
                    a.setStatus(AppealStatus.CLOSED);
                    a.setClosedAt(timeService.now());
                    appealRepository.save(a);
                }
            });
        }
        return e;
    }

    public List<EducationRecord> residentRecords(Long residentId) {
        return educationRepository.findByResidentIdOrderByAssignedAtDesc(residentId);
    }

    public List<EducationRecord> pending() {
        return educationRepository.findByStatus(EducationStatus.ASSIGNED);
    }

    private String buildTopic(WasteType binType, WasteType detectedType) {
        String bin = binType == null ? "厨余垃圾" : binType.getLabel();
        String detected = detectedType == null ? "其他垃圾" : detectedType.getLabel();
        if (binType == WasteType.KITCHEN) {
            return "厨余垃圾分类：玻璃瓶等不属于厨余（本次识别为" + detected + "）";
        }
        return bin + "桶正确投放指引（本次识别为" + detected + "）";
    }
}
