package com.city.garbage.repo;

import com.city.garbage.domain.AppealCase;
import com.city.garbage.domain.AppealStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppealCaseRepository extends JpaRepository<AppealCase, Long> {
    Optional<AppealCase> findByDisposalIdAndStatus(Long disposalId, AppealStatus status);
    Optional<AppealCase> findFirstByDisposalIdOrderByIdDesc(Long disposalId);
    List<AppealCase> findByStatusOrderByCreatedAtAsc(AppealStatus status);
    List<AppealCase> findByResidentIdOrderByCreatedAtDesc(Long residentId);
}
