package com.city.garbage.repo;

import com.city.garbage.domain.NotificationRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRecordRepository extends JpaRepository<NotificationRecord, Long> {
    boolean existsByDedupeKey(String dedupeKey);
    List<NotificationRecord> findByRoomCodeOrderByCreatedAtAsc(String roomCode);
}
