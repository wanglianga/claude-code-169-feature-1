package com.city.garbage.service;

import com.city.garbage.domain.*;
import com.city.garbage.repo.*;
import com.city.garbage.support.BusinessException;
import com.city.garbage.support.TimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 环卫端：按容量与路线（这里以同小区为一路线）计算清运优先级；
 * 清运离场后需要清洁确认，超时未确认联动“清运后未清洁”。
 */
@Service
@RequiredArgsConstructor
public class SanitationService {

    private final GarbageRoomRepository roomRepository;
    private final TruckVisitRepository visitRepository;
    private final CommunityRepository communityRepository;
    private final WorkTaskRepository taskRepository;
    private final AlertRepository alertRepository;
    private final LinkageEngine engine;
    private final TimeService timeService;

    /**
     * 清运优先级列表：厨余满溢未到场最优先（100），其次其他垃圾满溢，
     * 再按两类桶容量、满溢持续时长综合排序。
     */
    public List<Map<String, Object>> priorityList() {
        List<GarbageRoom> rooms = roomRepository.findAll();
        return rooms.stream()
                .map(this::toPriority)
                .sorted(Comparator.comparingInt((Map<String, Object> m) -> (int) m.get("priorityScore")).reversed())
                .toList();
    }

    private Map<String, Object> toPriority(GarbageRoom r) {
        int score;
        String reason;
        if (r.isOverdueNotified() && r.isKitchenFull() && !r.isTruckPresent()) {
            score = 120;
            reason = "封桶后清运车超时未到，街道督办，立即调车";
        } else if (r.isSealed() && r.isKitchenFull() && !r.isTruckPresent()) {
            score = 110;
            reason = "厨余桶满溢已封桶，居民改投备用点，需调整路线优先清运";
        } else if (r.isKitchenFull() && !r.isTruckPresent()) {
            score = 100;
            reason = "厨余桶满溢且车未到";
        } else if (r.getOtherCapacityPct() != null && r.getOtherCapacityPct() >= 90) {
            score = 80;
            reason = "其他垃圾桶满溢";
        } else {
            score = Math.max(r.getKitchenCapacityPct(), r.getOtherCapacityPct());
            reason = "按容量排队";
        }
        // 满溢持续时间越久越优先（每分钟+1，上限15）
        if (r.getKitchenFullSince() != null) {
            long mins = java.time.Duration.between(r.getKitchenFullSince(), timeService.now()).toMinutes();
            score += (int) Math.min(mins, 15);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roomCode", r.getCode());
        m.put("roomName", r.getName());
        m.put("communityId", r.getCommunityId());
        communityRepository.findById(r.getCommunityId())
                .ifPresent(c -> {
                    m.put("communityName", c.getName());
                    m.put("streetName", c.getStreetName());
                });
        m.put("kitchenCapacityPct", r.getKitchenCapacityPct());
        m.put("otherCapacityPct", r.getOtherCapacityPct());
        m.put("kitchenFull", r.isKitchenFull());
        m.put("sealed", r.isSealed());
        m.put("overdueNotified", r.isOverdueNotified());
        m.put("truckPresent", r.isTruckPresent());
        m.put("online", r.isOnline());
        m.put("odor", r.getCurrentOdor());
        m.put("activeCaseNo", r.getActiveCaseNo());
        m.put("priorityScore", score);
        m.put("reason", reason);
        return m;
    }

    /** 清运完成后垃圾房清洁确认；不确认则由定时巡检升级为“清运后未清洁” */
    @Transactional
    public TruckVisit confirmCleaned(String roomCode, String note) {
        GarbageRoom room = roomRepository.findByCode(roomCode)
                .orElseThrow(() -> new BusinessException("垃圾房不存在: " + roomCode));
        TruckVisit visit = visitRepository.findFirstByRoomCodeAndStatusInOrderByArrivedAtDesc(
                        roomCode, List.of(TruckVisitStatus.DEPARTED, TruckVisitStatus.ARRIVED))
                .orElseThrow(() -> new BusinessException("没有进行中的清运记录"));
        visit.setStatus(TruckVisitStatus.CONFIRMED);
        visit.setCleanedConfirmedAt(timeService.now());
        visitRepository.save(visit);

        room.setLastCleanedAt(timeService.now());
        roomRepository.save(room);
        engine.resolveByType(roomCode, AlertType.NOT_CLEANED_AFTER_COLLECTION,
                "清运后清洁已确认：" + (note == null ? "" : note));
        return visit;
    }

    /** 定时巡检：清运离场超时未清洁确认 -> 联动单补保洁任务 */
    @Transactional
    public int checkNotCleaned(int timeoutMinutes) {
        OffsetDateTime now = timeService.now();
        int n = 0;
        for (GarbageRoom room : roomRepository.findAll()) {
            TruckVisit v = visitRepository.findFirstByRoomCodeAndStatusInOrderByArrivedAtDesc(
                    room.getCode(), List.of(TruckVisitStatus.DEPARTED)).orElse(null);
            if (v != null && v.getDepartedAt() != null
                    && v.getDepartedAt().isBefore(now.minusMinutes(timeoutMinutes))) {
                String dedupe = "NOT_CLEANED:" + room.getCode() + ":" + v.getId();
                boolean exists = alertRepository.existsByDedupeKeyAndStatus(dedupe, AlertStatus.LINKED)
                        || alertRepository.existsByDedupeKeyAndStatus(dedupe, AlertStatus.OPEN);
                if (!exists) {
                    engine.raiseAlert(room, AlertType.NOT_CLEANED_AFTER_COLLECTION, AlertSeverity.HIGH,
                            "清运车已于" + v.getDepartedAt() + "离场，超过" + timeoutMinutes + "分钟未做清洁确认",
                            dedupe, null, null);
                    n++;
                }
            }
        }
        return n;
    }
}
