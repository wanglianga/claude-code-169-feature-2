package com.city.garbage.repo;

import com.city.garbage.domain.AppealReview;
import com.city.garbage.domain.AppealStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppealReviewRepository extends JpaRepository<AppealReview, Long> {
    Optional<AppealReview> findByDisposalId(Long disposalId);
    List<AppealReview> findByResidentIdOrderByCreatedAtDesc(Long residentId);
    List<AppealReview> findByRoomCodeAndStatusIn(String roomCode, List<AppealStatus> statuses);
    List<AppealReview> findByStatusInOrderByCreatedAtAsc(List<AppealStatus> statuses);
    List<AppealReview> findByCameraCodeAndStatus(String cameraCode, AppealStatus status);
}
