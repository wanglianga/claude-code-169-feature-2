package com.city.garbage.service;

import com.city.garbage.config.LinkageProperties;
import com.city.garbage.domain.*;
import com.city.garbage.repo.*;
import com.city.garbage.support.BusinessException;
import com.city.garbage.support.TimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 居民投放服务：刷卡/扫码投放 -> 记录小区/楼栋/垃圾类型/重量 ->
 * 摄像头判定错投 -> 积分结算与违规提示 -> 夜间高峰联动。
 * 积分流水始终与真实投放记录一一对应。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisposalService {

    private final DisposalRepository disposalRepository;
    private final ResidentRepository residentRepository;
    private final GarbageRoomRepository roomRepository;
    private final CommunityRepository communityRepository;
    private final PointsLedgerRepository ledgerRepository;
    private final AlertRepository alertRepository;
    private final LinkageEngine engine;
    private final CameraStatService cameraStatService;
    private final LinkageProperties props;
    private final TimeService timeService;

    @Transactional
    public DisposalRecord dispose(String cardNo, String roomCode, WasteType binType,
                                  WasteType detectedTypeRaw, Double weightKg, Double confidence,
                                  String cameraCode, boolean deferPoints) {
        Resident resident = residentRepository.findByCardNo(cardNo)
                .orElseThrow(() -> new BusinessException("卡号未登记: " + cardNo));
        GarbageRoom room = roomRepository.findByCode(roomCode)
                .orElseThrow(() -> new BusinessException("垃圾房不存在: " + roomCode));
        Community community = communityRepository.findById(room.getCommunityId())
                .orElseThrow(() -> new BusinessException("垃圾房未绑定小区"));
        OffsetDateTime now = timeService.now();

        WasteType detected = detectedTypeRaw != null ? detectedTypeRaw : binType;
        boolean wrong = detected != binType;
        String camera = (cameraCode == null || cameraCode.isBlank()) ? "CAM-" + roomCode : cameraCode;
        // 摄像头校准中：错投处罚暂停自动生效，只记录不扣分，待人工复核
        boolean penaltySuspended = wrong && cameraStatService.isSuspended(camera);

        DisposalRecord r = new DisposalRecord();
        r.setResidentId(resident.getId());
        r.setCardNo(cardNo);
        r.setCommunityId(community.getId());
        r.setBuildingName(resident.getBuildingName());
        r.setRoomCode(roomCode);
        r.setBinType(binType);
        r.setDetectedType(detected);
        r.setWeightKg(weightKg);
        r.setWrongSort(wrong);
        r.setConfidence(confidence);
        r.setCameraCode(camera);
        r.setPenaltySuspended(penaltySuspended);
        r.setCreatedAt(now);

        if (deferPoints) {
            // 设备断网/积分通道异常：投放真实落账，积分暂挂，等待巡检告警与物业核查补发
            r.setPointsChange(0);
            r.setStatus(DisposalStatus.PENDING_POINTS);
            r.setViolationMessage(wrong
                    ? "疑似错投（投" + binType.getLabel() + "识别为" + detected.getLabel() + "），积分通道异常，待核查"
                    : "投放已记录，因积分通道异常积分暂未到账，将自动核查补发");
            r = disposalRepository.save(r);
            if (wrong) {
                Alert alert = engine.raiseAlert(room, AlertType.MISSORT, AlertSeverity.MEDIUM,
                        "居民" + maskCard(cardNo) + "在" + room.getName() + "错投（积分暂挂待核查）",
                        "MISSORT:DISPOSAL:" + r.getId(), r.getId(), null);
                r.setCaseNo(alert.getCaseNo());
                disposalRepository.save(r);
            }
            trackNightPeak(room, now);
            return r;
        }

        int points;
        if (wrong) {
            if (penaltySuspended) {
                // 摄像头校准中：处罚暂停自动生效，本次不扣分，待人工复核结论
                points = 0;
                r.setPointsChange(0);
                r.setViolationMessage("疑似错投：您投入" + binType.getLabel() + "桶，摄像头识别为"
                        + detected.getLabel() + "。该摄像头校准中，本次处罚暂缓生效，待人工复核");
                r.setStatus(DisposalStatus.SETTLED);
                r.setSettledAt(now);
            } else {
                // 错投：不予积分并记录违规提示，提示现场整改；扣分不使积分为负
                int deduct = Math.min(props.getWrongSortDeduction(), resident.getTotalPoints());
                points = -deduct;
                resident.setTotalPoints(resident.getTotalPoints() - deduct);
                r.setPointsChange(points);
                r.setViolationMessage("疑似错投：您投入" + binType.getLabel() + "桶，摄像头识别为"
                        + detected.getLabel() + "，本次不计分并扣" + deduct + "分，可在3日内申请复核");
                r.setStatus(DisposalStatus.SETTLED);
                r.setSettledAt(now);
            }
        } else {
            points = weightKg != null ? (int) Math.round(weightKg * props.getPointsPerKg()) : 0;
            resident.setTotalPoints(resident.getTotalPoints() + points);
            r.setPointsChange(points);
            r.setViolationMessage("投放正确，+" + points + "积分");
            r.setStatus(DisposalStatus.SETTLED);
            r.setSettledAt(now);
        }
        r = disposalRepository.save(r);
        residentRepository.save(resident);
        if (!penaltySuspended) {
            ledgerRepository.save(new PointsLedger(resident.getId(), r.getId(), points,
                    resident.getTotalPoints(),
                    wrong ? "错投扣减：识别为" + detected.getLabel() : binType.getLabel() + "正确投放 " + weightKg + "kg", now));
        }

        if (wrong) {
            Alert alert = engine.raiseAlert(room, AlertType.MISSORT, AlertSeverity.MEDIUM,
                    "居民" + maskCard(cardNo) + "在" + room.getName() + "错投：投" + binType.getLabel()
                            + "识别为" + detected.getLabel() + "（置信度" + confidence + "）"
                            + (penaltySuspended ? "（摄像头校准中，处罚暂缓）" : ""),
                    "MISSORT:DISPOSAL:" + r.getId(), r.getId(), null);
            r.setCaseNo(alert.getCaseNo());
            disposalRepository.save(r);
        }

        trackNightPeak(room, now);
        return r;
    }

    /** 夜间时段（默认20:00-次日06:00）投放次数达到阈值即触发高峰联动 */
    private void trackNightPeak(GarbageRoom room, OffsetDateTime now) {
        int hour = now.getHour();
        boolean night = hour >= props.getNightStart() || hour < props.getNightEnd();
        if (!night) return;
        String today = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        if (!today.equals(room.getNightCountDate())) {
            room.setNightCountDate(today);
            room.setNightDisposalCount(0);
        }
        room.setNightDisposalCount(room.getNightDisposalCount() + 1);
        if (room.getNightDisposalCount() >= props.getNightDisposalThreshold()) {
            engine.raiseAlert(room, AlertType.NIGHT_PEAK, AlertSeverity.MEDIUM,
                    "夜间投放高峰：" + today + " 夜间已累计投放" + room.getNightDisposalCount() + "次",
                    "NIGHT_PEAK:" + room.getCode() + ":" + today, null, null);
        }
        roomRepository.save(room);
    }

    /** 对长期未结算的投放补发积分（积分未到账整改），补发后解除对应告警与整改任务 */
    @Transactional
    public int settlePendingPoints() {
        OffsetDateTime deadline = timeService.now().minusSeconds(props.getUnsettledSeconds());
        int fixed = 0;
        for (DisposalRecord r : disposalRepository.findAll()) {
            if (r.getStatus() == DisposalStatus.PENDING_POINTS
                    && r.getCreatedAt() != null && r.getCreatedAt().isBefore(deadline)) {
                Resident resident = residentRepository.findById(r.getResidentId()).orElseThrow();
                int points = r.isWrongSort() ? 0
                        : (r.getWeightKg() != null ? (int) Math.round(r.getWeightKg() * props.getPointsPerKg()) : 0);
                resident.setTotalPoints(resident.getTotalPoints() + points);
                residentRepository.save(resident);
                r.setPointsChange(points);
                r.setStatus(DisposalStatus.SETTLED);
                r.setSettledAt(timeService.now());
                r.setViolationMessage(r.isWrongSort() ? "核查完成，维持错投判罚" : "积分核查补发成功，+" + points + "积分");
                disposalRepository.save(r);
                ledgerRepository.save(new PointsLedger(resident.getId(), r.getId(), points,
                        resident.getTotalPoints(), "积分未到账核查补发", timeService.now()));
                for (Alert a : alertRepository.findByDisposalIdAndStatusIn(r.getId(),
                        List.of(AlertStatus.OPEN, AlertStatus.LINKED))) {
                    if (a.getType() == AlertType.POINTS_UNSETTLED) {
                        engine.resolveAlert(a, "已核查补发" + points + "积分", false);
                    }
                }
                fixed++;
            }
        }
        return fixed;
    }

    public List<DisposalRecord> residentHistory(Long residentId) {
        return disposalRepository.findByResidentIdOrderByCreatedAtDesc(residentId);
    }

    private String maskCard(String cardNo) {
        if (cardNo == null || cardNo.length() <= 4) return cardNo;
        return cardNo.substring(0, 2) + "****" + cardNo.substring(cardNo.length() - 2);
    }
}
