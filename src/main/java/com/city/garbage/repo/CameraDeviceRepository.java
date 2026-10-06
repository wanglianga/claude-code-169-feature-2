package com.city.garbage.repo;

import com.city.garbage.domain.CameraCalibrationStatus;
import com.city.garbage.domain.CameraDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CameraDeviceRepository extends JpaRepository<CameraDevice, Long> {
    Optional<CameraDevice> findByCameraCode(String cameraCode);
    List<CameraDevice> findByRoomCode(String roomCode);
    List<CameraDevice> findByCalibrationStatus(CameraCalibrationStatus status);
}
