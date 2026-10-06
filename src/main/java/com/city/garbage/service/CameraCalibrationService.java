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

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 摄像头校准联动：按摄像头滚动统计“错投识别”经申诉复核的结果。
 * 当同一摄像头误判率升高（误判数/错投判定数 ≥ 阈值，且样本数达标）时，
 * 设备进入校准任务（{@link TaskType#CAMERA_CALIBRATION}），期间该摄像头相关
 * 错投处罚（扣分）暂停自动生效；校准完成后清零窗口、恢复正常执法。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CameraCalibrationService {

    private final CameraDeviceRepository cameraRepository;
    private final GarbageRoomRepository roomRepository;
    private final DisposalRepository disposalRepository;
    private final LinkageEngine engine;
    private final LinkageProperties props;
    private final TimeService timeService;

    /** 获取（不存在则登记）摄像头档案 */
    @Transactional
    public CameraDevice getOrCreate(String cameraCode, String roomCode) {
        if (cameraCode == null || cameraCode.isBlank()) return null;
        return cameraRepository.findByCameraCode(cameraCode).orElseGet(() -> {
            CameraDevice c = new CameraDevice();
            c.setCameraCode(cameraCode);
            c.setRoomCode(roomCode == null ? "UNKNOWN" : roomCode);
            c.setName(cameraCode + " 识别摄像头");
            c.setWindowStartAt(timeService.now());
            return cameraRepository.save(c);
        });
    }

    /** 记录一次摄像头识别（含正确投放），用于设备画像 */
    @Transactional
    public void recordDetection(String cameraCode, String roomCode, boolean wrongSort) {
        CameraDevice c = getOrCreate(cameraCode, roomCode);
        if (c == null) return;
        c.setTotalDetectionCount(c.getTotalDetectionCount() + 1);
        if (wrongSort) c.setMissortFlaggedCount(c.getMissortFlaggedCount() + 1);
        cameraRepository.save(c);
    }

    /**
     * 记录一次申诉复核结果并重新评估误判率。
     * @param misjudge true=误判成立；false=违规属实
     */
    @Transactional
    public CameraDevice recordReviewOutcome(String cameraCode, String roomCode, boolean misjudge) {
        CameraDevice c = getOrCreate(cameraCode, roomCode);
        if (c == null) return null;
        // 已经在该窗口统计过的错投才纳入基数；复核结论累计
        if (misjudge) c.setMisjudgeConfirmedCount(c.getMisjudgeConfirmedCount() + 1);
        else c.setViolationConfirmedCount(c.getViolationConfirmedCount() + 1);
        cameraRepository.save(c);
        evaluate(c);
        return c;
    }

    /** 评估误判率，达到阈值则进入校准任务并暂停该摄像头错投处罚自动生效 */
    @Transactional
    public void evaluate(CameraDevice c) {
        if (c.getCalibrationStatus() == CameraCalibrationStatus.CALIBRATING) return;
        int flagged = c.getMissortFlaggedCount();
        int samples = c.getMisjudgeConfirmedCount() + c.getViolationConfirmedCount();
        if (samples < props.getCameraMinMissortSamples() || flagged == 0) {
            return;
        }
        double rate = (double) c.getMisjudgeConfirmedCount() / flagged;
        c.setLastMisjudgeRate(Math.round(rate * 10000.0) / 10000.0);
        cameraRepository.save(c);

        if (rate >= props.getCameraMisjudgeRateThreshold()) {
            enterCalibration(c, rate);
        }
    }

    @Transactional
    public CameraDevice enterCalibration(CameraDevice c, double rate) {
        c.setCalibrationStatus(CameraCalibrationStatus.CALIBRATING);
        c.setCalibrationStartedAt(timeService.now());
        c.setCalibrationNote("误判率" + Math.round(rate * 100) + "%达到阈值"
                + Math.round(props.getCameraMisjudgeRateThreshold() * 100) + "%，暂停错投处罚自动生效");
        cameraRepository.save(c);

        GarbageRoom room = roomRepository.findByCode(c.getRoomCode()).orElse(null);
        if (room != null) {
            engine.raiseAlert(room, AlertType.CAMERA_MISJUDGE_RATE_HIGH, AlertSeverity.HIGH,
                    "摄像头" + c.getCameraCode() + "误判率" + Math.round(rate * 100)
                            + "%升高，进入校准；校准期间错投处罚暂停自动生效",
                    "CAMERA_RATE_HIGH:" + c.getCameraCode(), null, null);
        }
        // 将该摄像头当前已判错投、尚未结算处罚的记录标记为暂停生效
        suspendPendingPenalties(c.getCameraCode());
        log.warn("摄像头{}进入校准，误判率={}，相关错投处罚暂停自动生效", c.getCameraCode(), rate);
        return c;
    }

    /** 校准期间的错投记录扣分暂停自动生效（不扣分，待校准后再人工/自动复核） */
    private void suspendPendingPenalties(String cameraCode) {
        for (DisposalRecord r : disposalRepository.findAll()) {
            if (cameraCode.equals(r.getCameraCode()) && r.isWrongSort() && !r.isPenaltySuspended()
                    && r.getStatus() == DisposalStatus.SETTLED
                    && r.getPointsChange() != null && r.getPointsChange() < 0) {
                r.setPenaltySuspended(true);
                disposalRepository.save(r);
            }
        }
    }

    /** 摄像头是否处于校准期（处罚暂停） */
    public boolean isPenaltySuspended(String cameraCode) {
        return cameraCode != null
                && cameraRepository.findByCameraCode(cameraCode)
                .map(c -> c.getCalibrationStatus() == CameraCalibrationStatus.CALIBRATING)
                .orElse(false);
    }

    /**
     * 完成校准：清零滚动窗口、恢复正常执法，并解除误判率告警/校准任务。
     * 暂停期间的错投记录维持“不自动扣分”，由现场复核逐单定性。
     */
    @Transactional
    public CameraDevice completeCalibration(String cameraCode, String note) {
        CameraDevice c = cameraRepository.findByCameraCode(cameraCode)
                .orElseThrow(() -> new BusinessException("摄像头不存在: " + cameraCode));
        OffsetDateTime now = timeService.now();
        c.setCalibrationStatus(CameraCalibrationStatus.CALIBRATED);
        c.setCalibratedAt(now);
        c.setCalibrationNote(note);
        c.setWindowStartAt(now);
        c.setMissortFlaggedCount(0);
        c.setMisjudgeConfirmedCount(0);
        c.setViolationConfirmedCount(0);
        c.setLastMisjudgeRate(0.0);
        cameraRepository.save(c);
        // CALIBRATED 为终态展示：处罚仅在 CALIBRATING 期间暂停，校准完成即恢复自动生效

        GarbageRoom room = roomRepository.findByCode(c.getRoomCode()).orElse(null);
        if (room != null) {
            engine.resolveByType(room.getCode(), AlertType.CAMERA_MISJUDGE_RATE_HIGH,
                    "摄像头" + cameraCode + "校准完成：" + note);
        }
        log.info("摄像头{}校准完成，恢复错投处罚自动生效", cameraCode);
        return c;
    }

    public List<CameraDevice> all() {
        return cameraRepository.findAll();
    }

    public List<CameraDevice> calibrating() {
        return cameraRepository.findByCalibrationStatus(CameraCalibrationStatus.CALIBRATING);
    }
}
