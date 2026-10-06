package com.city.garbage.repo;

import com.city.garbage.domain.FeedbackStatus;
import com.city.garbage.domain.ModelFeedbackSample;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ModelFeedbackSampleRepository extends JpaRepository<ModelFeedbackSample, Long> {
    List<ModelFeedbackSample> findByStatus(FeedbackStatus status);
    List<ModelFeedbackSample> findByCameraCode(String cameraCode);
}
