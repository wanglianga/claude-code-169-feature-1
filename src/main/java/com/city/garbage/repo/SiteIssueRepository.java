package com.city.garbage.repo;

import com.city.garbage.domain.SiteIssue;
import com.city.garbage.domain.SiteIssueStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SiteIssueRepository extends JpaRepository<SiteIssue, Long> {
    List<SiteIssue> findByStatusOrderByCreatedAtDesc(SiteIssueStatus status);
    List<SiteIssue> findByRoomCodeAndStatusOrderByCreatedAtAsc(String roomCode, SiteIssueStatus status);
    List<SiteIssue> findByCaseNoAndStatus(String caseNo, SiteIssueStatus status);
    long countByRoomCodeAndStatus(String roomCode, SiteIssueStatus status);
}
