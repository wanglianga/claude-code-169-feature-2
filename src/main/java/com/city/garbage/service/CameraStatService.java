package com.city.garbage.service;

import com.city.garbage.config.LinkageProperties;
import com.city.garbage.domain.*;
import com.city.garbage.repo.CameraStatRepository;
import com.city.garbage.repo.GarbageRoomRepository;
import com.city.garbage.support.BusinessException;
import com.city.garbage.support.TimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 摄像头误判统计与校准联动。每次申诉复核结论都累计摄像头的复核样本与误判数；
 * 同一摄像头误判率达到阈值后进入校准：产生校准告警并派生校准任务，
 * 校准期间该摄像头的错投处罚暂停自动生效；校准完成后统计清零、处罚恢复。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CameraStatService {

    private final CameraStatRepository cameraStatRepository;
    private final GarbageRoomRepository roomRepository;
    private final LinkageEngine engine;
    private final LinkageProperties props;
    private final TimeService timeService;

    /** 该摄像头当前是否处于校准期（错投处罚暂停自动生效） */
    public boolean isSuspended(String cameraCode) {
        if (cameraCode == null) return false;
        return cameraStatRepository.findByCameraCode(cameraCode)
                .map(CameraStat::isSuspended).orElse(false);
    }

    /**
     * 登记一次申诉复核结论。误判率达到阈值且尚未校准中时，
     * 摄像头进入校准：产生 CAMERA_CALIBRATION 告警并派生摄像头校准任务。
     */
    @Transactional
    public CameraStat recordReview(String cameraCode, String roomCode, boolean misjudge) {
        if (cameraCode == null) return null;
        CameraStat s = cameraStatRepository.findByCameraCode(cameraCode)
                .orElseGet(() -> new CameraStat(cameraCode, roomCode));
        s.setRoomCode(roomCode);
        s.setReviewTotal(s.getReviewTotal() + 1);
        if (misjudge) s.setMisjudgeCount(s.getMisjudgeCount() + 1);
        s.setUpdatedAt(timeService.now());

        if (!s.isSuspended()
                && s.getReviewTotal() >= props.getCameraMisjudgeMinReviews()
                && s.getMisjudgeRatePct() >= props.getCameraMisjudgeRatePct()) {
            s.setSuspended(true);
            s.setSuspendedAt(timeService.now());
            GarbageRoom room = roomRepository.findByCode(roomCode)
                    .orElseThrow(() -> new BusinessException("垃圾房不存在: " + roomCode));
            engine.raiseAlert(room, AlertType.CAMERA_CALIBRATION, AlertSeverity.HIGH,
                    "摄像头" + cameraCode + "误判率升高（" + s.getMisjudgeCount() + "/" + s.getReviewTotal()
                            + "，" + s.getMisjudgeRatePct() + "%），设备进入校准，相关错投处罚暂停自动生效",
                    "CAMERA_CALIBRATION:" + cameraCode, null, null);
            log.info("摄像头进入校准 camera={} 误判率={}%", cameraCode, s.getMisjudgeRatePct());
        }
        return cameraStatRepository.save(s);
    }

    /** 校准完成：解除处罚暂停，统计清零重新累计 */
    @Transactional
    public CameraStat calibrate(String cameraCode) {
        CameraStat s = cameraStatRepository.findByCameraCode(cameraCode)
                .orElseThrow(() -> new BusinessException("摄像头未登记: " + cameraCode));
        s.setSuspended(false);
        s.setReviewTotal(0);
        s.setMisjudgeCount(0);
        s.setUpdatedAt(timeService.now());
        log.info("摄像头校准完成 camera={}，错投处罚恢复自动生效", cameraCode);
        return cameraStatRepository.save(s);
    }

    public List<CameraStat> all() {
        return cameraStatRepository.findAll();
    }
}
