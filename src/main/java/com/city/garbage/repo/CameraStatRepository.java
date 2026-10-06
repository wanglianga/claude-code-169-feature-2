package com.city.garbage.repo;

import com.city.garbage.domain.CameraStat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CameraStatRepository extends JpaRepository<CameraStat, Long> {
    Optional<CameraStat> findByCameraCode(String cameraCode);
}
