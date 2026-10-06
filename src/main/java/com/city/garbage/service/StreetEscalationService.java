package com.city.garbage.service;

import com.city.garbage.domain.*;
import com.city.garbage.repo.*;
import com.city.garbage.support.BusinessException;
import com.city.garbage.support.TimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 街道端清运超时督办视图：列出已升级的满溢清运超时事件，
 * 含满溢/封桶/升级时间、清运延迟原因、居民投诉数量、现场管理问题数量；
 * 支持街道回填清运延迟原因，记录在联动单上。
 */
@Service
@RequiredArgsConstructor
public class StreetEscalationService {

    private final GovernanceCaseRepository caseRepository;
    private final GarbageRoomRepository roomRepository;
    private final CommunityRepository communityRepository;
    private final AlertRepository alertRepository;
    private final ComplaintRepository complaintRepository;
    private final SiteIssueRepository siteIssueRepository;
    private final NotificationRecordRepository notificationRepository;
    private final TimeService timeService;

    /** 当前已升级街道督办（存在活跃 COLLECTION_OVERDUE 告警）的超时事件 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> overdueEvents(boolean includeResolved) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (GovernanceCase gc : caseRepository.findAll()) {
            if (gc.getEscalatedAt() == null) continue;
            boolean active = alertRepository.findByCaseNo(gc.getCaseNo()).stream()
                    .anyMatch(a -> a.getType() == AlertType.COLLECTION_OVERDUE
                            && (a.getStatus() == AlertStatus.OPEN || a.getStatus() == AlertStatus.LINKED));
            if (!active && !includeResolved) continue;
            result.add(toEvent(gc, active));
        }
        result.sort((a, b) -> {
            OffsetDateTime ta = (OffsetDateTime) a.get("escalatedAt");
            OffsetDateTime tb = (OffsetDateTime) b.get("escalatedAt");
            return tb.compareTo(ta);
        });
        return result;
    }

    private Map<String, Object> toEvent(GovernanceCase gc, boolean active) {
        GarbageRoom room = roomRepository.findByCode(gc.getRoomCode()).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseNo", gc.getCaseNo());
        m.put("roomCode", gc.getRoomCode());
        m.put("roomName", room == null ? "" : room.getName());
        m.put("address", room == null ? "" : room.getAddress());
        communityRepository.findById(gc.getCommunityId() == null ? -1L : gc.getCommunityId())
                .ifPresent(c -> {
                    m.put("communityName", c.getName());
                    m.put("streetName", c.getStreetName());
                });
        m.put("active", active);
        m.put("status", gc.getStatus());
        m.put("kitchenFullSince", room == null ? null : room.getKitchenFullSince());
        m.put("sealedAt", room == null ? null : room.getSealedAt());
        m.put("escalatedAt", gc.getEscalatedAt());
        m.put("resolvedAt", gc.getResolvedAt());
        m.put("sealed", room != null && room.isSealed());
        m.put("truckPresent", room != null && room.isTruckPresent());
        // 清运延迟原因（环卫/街道回填）
        m.put("delayReason", gc.getDelayReason());
        // 居民投诉数量（事件期间累计）
        m.put("residentComplaintCount", gc.getResidentComplaintCount());
        // 封桶期间摄像头记录的现场管理问题
        m.put("openSiteIssueCount",
                siteIssueRepository.findByCaseNoAndStatus(gc.getCaseNo(), SiteIssueStatus.OPEN).size());
        long delayComplaints = complaintRepository.findAll().stream()
                .filter(c -> gc.getCaseNo().equals(c.getCaseNo()) && "COLLECTION_DELAY".equals(c.getType()))
                .count();
        m.put("collectionDelayComplaintCount", delayComplaints);
        m.put("notifications", notificationRepository.findByRoomCodeOrderByCreatedAtAsc(gc.getRoomCode()).stream()
                .filter(n -> gc.getCaseNo().equals(n.getCaseNo()))
                .map(n -> Map.of("targetRole", n.getTargetRole(), "notifyType", n.getNotifyType(),
                        "content", n.getContent() == null ? "" : n.getContent(),
                        "createdAt", n.getCreatedAt() == null ? "" : n.getCreatedAt().toString()))
                .toList());
        return m;
    }

    /** 街道/环卫回填清运延迟原因（车辆故障、交通管制、人手不足、路线冲突等） */
    @Transactional
    public GovernanceCase recordDelayReason(String caseNo, String reason) {
        GovernanceCase gc = caseRepository.findByCaseNo(caseNo)
                .orElseThrow(() -> new BusinessException("联动单不存在: " + caseNo));
        gc.setDelayReason(reason);
        gc.setUpdatedAt(timeService.now());
        return caseRepository.save(gc);
    }
}
