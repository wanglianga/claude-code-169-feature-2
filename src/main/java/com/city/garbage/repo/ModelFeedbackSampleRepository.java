package com.city.garbage.repo;

import com.city.garbage.domain.ModelFeedbackSample;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ModelFeedbackSampleRepository extends JpaRepository<ModelFeedbackSample, Long> {
    List<ModelFeedbackSample> findByCameraCodeOrderByCreatedAtDesc(String cameraCode);
    List<ModelFeedbackSample> findAllByOrderByCreatedAtDesc();
}
