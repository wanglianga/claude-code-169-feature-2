package com.city.garbage.repo;

import com.city.garbage.domain.EducationRecord;
import com.city.garbage.domain.EducationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EducationRecordRepository extends JpaRepository<EducationRecord, Long> {
    List<EducationRecord> findByResidentIdOrderByAssignedAtDesc(Long residentId);
    List<EducationRecord> findByResidentIdAndStatus(Long residentId, EducationStatus status);
    List<EducationRecord> findByStatus(EducationStatus status);
}
