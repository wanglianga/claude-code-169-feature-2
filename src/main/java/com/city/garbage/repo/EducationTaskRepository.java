package com.city.garbage.repo;

import com.city.garbage.domain.EducationStatus;
import com.city.garbage.domain.EducationTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EducationTaskRepository extends JpaRepository<EducationTask, Long> {
    List<EducationTask> findByResidentIdOrderByCreatedAtDesc(Long residentId);
    long countByResidentIdAndStatus(Long residentId, EducationStatus status);
}
