package com.city.garbage.repo;

import com.city.garbage.domain.DeviceEventLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface DeviceEventLogRepository extends JpaRepository<DeviceEventLog, Long> {
    List<DeviceEventLog> findByRoomCodeOrderByOccurredAtDesc(String roomCode);
    List<DeviceEventLog> findByRoomCodeAndEventTypeAndOccurredAtBetweenOrderByOccurredAtAsc(
            String roomCode, String eventType, OffsetDateTime from, OffsetDateTime to);
    List<DeviceEventLog> findByRoomCodeAndOccurredAtBetweenOrderByOccurredAtAsc(
            String roomCode, OffsetDateTime from, OffsetDateTime to);
}
