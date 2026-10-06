package com.city.garbage.service;

import com.city.garbage.config.LinkageProperties;
import com.city.garbage.domain.*;
import com.city.garbage.kafka.GovernanceEventPublisher;
import com.city.garbage.repo.*;
import com.city.garbage.support.BusinessException;
import com.city.garbage.support.TimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 满溢清运超时升级编排：
 * 1) 厨余桶满溢后，物业先“临时封桶”，居民端展示最近可用投放点与预计恢复时间；
 * 2) 封桶即通知环卫调整清运路线（清运任务已在满溢告警时以最高优先级派生）；
 * 3) 定时巡检：封桶超过阈值清运车仍未到场 -> 升级提醒街道，联动单记录延迟原因与投诉数量；
 * 4) 清运车到场/离场自动解封，现场管理问题随封桶结束闭环。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SealManagementService {

    private final GarbageRoomRepository roomRepository;
    private final CommunityRepository communityRepository;
    private final GovernanceCaseRepository caseRepository;
    private final WorkTaskRepository taskRepository;
    private final ComplaintRepository complaintRepository;
    private final TruckVisitRepository visitRepository;
    private final LinkageEngine engine;
    private final GovernanceEventPublisher eventPublisher;
    private final LinkageProperties props;
    private final TimeService timeService;

    /** 物业确认临时封桶：封桶并引导居民到备用投放点，随后通知环卫调整路线 */
    @Transactional
    public GarbageRoom confirmSeal(String roomCode, String operator, String alternativeRoomCode,
                                  Integer estimatedRestoreMinutes, String note) {
        GarbageRoom room = requireRoom(roomCode);
        if (!room.isKitchenFull()) {
            throw new BusinessException("厨余桶当前未满溢，无需临时封桶: " + roomCode);
        }
        if (alternativeRoomCode != null && alternativeRoomCode.isBlank()) alternativeRoomCode = null;
        if (alternativeRoomCode != null && !alternativeRoomCode.equals(roomCode)) {
            requireRoom(alternativeRoomCode);
        }
        OffsetDateTime now = timeService.now();
        int restoreMinutes = estimatedRestoreMinutes != null && estimatedRestoreMinutes > 0
                ? estimatedRestoreMinutes : props.getDefaultRestoreMinutes();

        room.setKitchenSealed(true);
        room.setSealedAt(now);
        room.setSealedBy(operator);
        room.setSealedNote(note);
        room.setAlternativeRoomCode(alternativeRoomCode);
        room.setEstimatedRestoreAt(now.plusMinutes(restoreMinutes));
        room.setOverdueEscalated(false);
        room.setOverdueEscalatedAt(null);
        roomRepository.save(room);

        // 封桶引导任务闭环
        if (room.getActiveCaseNo() != null) {
            for (WorkTask t : taskRepository.findByCaseNo(room.getActiveCaseNo())) {
                if (t.getType() == TaskType.TEMP_SEAL && t.getStatus() != TaskStatus.DONE) {
                    engine.markDone(t, "已临时封桶，引导居民至"
                            + (alternativeRoomCode != null ? alternativeRoomCode : "就近备用点")
                            + (note == null ? "" : "；" + note));
                }
            }
        }

        // 先物业封桶，再通知环卫调整路线（联动事件广播，四端大屏/通知服务可订阅）
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", "SEALED_NOTIFY_SANITATION");
        payload.put("roomCode", roomCode);
        payload.put("operator", operator);
        payload.put("alternativeRoomCode", alternativeRoomCode);
        payload.put("estimatedRestoreAt", room.getEstimatedRestoreAt());
        payload.put("message", "厨余桶已临时封桶，请环卫立即调整路线优先清运");
        eventPublisher.publish(roomCode, payload);
        log.info("厨余桶已临时封桶 room={} operator={} alternative={}，已通知环卫调整路线",
                roomCode, operator, alternativeRoomCode);
        return room;
    }

    /** 清运车到场：超时升级撤销（车已到），但封桶保持到清运离场清空后再解除 */
    @Transactional
    public void onTruckArrived(GarbageRoom room) {
        if (room.isOverdueEscalated()) {
            room.setOverdueEscalated(false);
            room.setOverdueEscalatedAt(null);
            roomRepository.save(room);
            if (room.getActiveCaseNo() != null) {
                caseRepository.findByCaseNo(room.getActiveCaseNo()).ifPresent(gc -> {
                    gc.setOverdueEscalated(false);
                    gc.setOverdueEscalatedAt(null);
                    caseRepository.save(gc);
                });
            }
            engine.resolveByType(room.getCode(), AlertType.COLLECTION_OVERDUE, "清运车已到场，超时升级解除");
            log.info("清运车到场，街道超时升级解除 room={}", room.getCode());
        }
    }

    /** 物业人工解封（正常情况下清运车到场会自动解封） */
    @Transactional
    public GarbageRoom unseal(String roomCode, String note) {
        GarbageRoom room = requireRoom(roomCode);
        if (!room.isKitchenSealed() && !room.isOverdueEscalated()) return room;
        releaseSeal(room, note == null ? "人工解封" : note);
        return room;
    }

    /** 清运车到场/离场后由设备联动调用：解除封桶、撤销超时升级与现场管理问题 */
    @Transactional
    public void releaseSeal(GarbageRoom room, String note) {
        boolean wasSealed = room.isKitchenSealed() || room.isOverdueEscalated();
        room.setKitchenSealed(false);
        room.setSealedAt(null);
        room.setSealedBy(null);
        room.setSealedNote(null);
        room.setAlternativeRoomCode(null);
        room.setEstimatedRestoreAt(null);
        room.setOverdueEscalated(false);
        room.setOverdueEscalatedAt(null);
        roomRepository.save(room);

        if (room.getActiveCaseNo() != null) {
            GovernanceCase gc = caseRepository.findByCaseNo(room.getActiveCaseNo()).orElse(null);
            if (gc != null) {
                gc.setOverdueEscalated(false);
                gc.setOverdueEscalatedAt(null);
                caseRepository.save(gc);
            }
        }
        engine.resolveByType(room.getCode(), AlertType.COLLECTION_OVERDUE, note);
        engine.resolveByType(room.getCode(), AlertType.SEALED_SITE_DISPOSAL, note);
        if (wasSealed) log.info("垃圾房解封 room={} reason={}", room.getCode(), note);
    }

    /** 定时巡检：封桶后清运车超时未到场 -> 升级提醒街道 */
    @Transactional
    public int checkCollectionOverdue() {
        OffsetDateTime now = timeService.now();
        int n = 0;
        for (GarbageRoom room : roomRepository.findAll()) {
            if (!room.isKitchenSealed() || room.isTruckPresent() || room.isOverdueEscalated()) continue;
            OffsetDateTime sealedAt = room.getSealedAt();
            if (sealedAt == null) continue;
            if (sealedAt.isBefore(now.minusMinutes(props.getSealOverdueMinutes()))) {
                escalate(room, sealedAt, now);
                n++;
            }
        }
        return n;
    }

    private void escalate(GarbageRoom room, OffsetDateTime sealedAt, OffsetDateTime now) {
        String dedupe = "COLLECTION_OVERDUE:" + room.getCode();
        long complaints = room.getActiveCaseNo() != null
                ? complaintRepository.countByCaseNo(room.getActiveCaseNo()) : 0;

        Alert alert = engine.raiseAlert(room, AlertType.COLLECTION_OVERDUE, AlertSeverity.URGENT,
                "临时封桶已超过" + props.getSealOverdueMinutes() + "分钟清运车仍未到场，升级街道督办；"
                        + "当前居民投诉" + complaints + "起",
                dedupe, null, null);

        room.setOverdueEscalated(true);
        room.setOverdueEscalatedAt(now);
        roomRepository.save(room);

        if (alert.getCaseNo() != null) {
            GovernanceCase gc = caseRepository.findByCaseNo(alert.getCaseNo()).orElse(null);
            if (gc != null) {
                gc.setOverdueEscalated(true);
                gc.setOverdueEscalatedAt(now);
                gc.setComplaintCount((int) complaints);
                caseRepository.save(gc);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", "OVERDUE_ESCALATE_STREET");
        payload.put("roomCode", room.getCode());
        payload.put("sealedAt", sealedAt);
        payload.put("overdueMinutes", props.getSealOverdueMinutes());
        payload.put("complaintCount", complaints);
        payload.put("message", "清运超时未到场，请街道督办环卫与物业");
        eventPublisher.publish(room.getCode(), payload);
        log.warn("清运超时升级街道 room={} 封桶于{} 投诉{}起", room.getCode(), sealedAt, complaints);
    }

    /** 环卫回填清运延迟原因（车辆故障/交通管制/排班不足等），记录到联动事件与清运记录 */
    @Transactional
    public GovernanceCase reportDelayReason(String roomCode, String reason) {
        GarbageRoom room = requireRoom(roomCode);
        GovernanceCase gc = room.getActiveCaseNo() == null ? null
                : caseRepository.findByCaseNo(room.getActiveCaseNo()).orElse(null);
        if (gc == null) {
            throw new BusinessException("该垃圾房当前没有进行中的清运联动单，无法回填延迟原因: " + roomCode);
        }
        gc.setCollectionDelayReason(reason);
        caseRepository.save(gc);
        // 最近一条清运记录同步留痕（车尚未到场时可能尚无记录，则仅记在联动单上）
        visitRepository.findByRoomCodeOrderByArrivedAtDesc(roomCode).stream().findFirst().ifPresent(v -> {
            v.setDelayReason(reason);
            visitRepository.save(v);
        });
        log.info("环卫回填清运延迟原因 room={} case={} reason={}", roomCode, gc.getCaseNo(), reason);
        return gc;
    }

    /** 居民端投放指引：封桶状态、最近可用投放点、预计恢复时间 */
    @Transactional(readOnly = true)
    public Map<String, Object> guidance(String roomCode) {
        GarbageRoom room = requireRoom(roomCode);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("roomCode", room.getCode());
        view.put("roomName", room.getName());
        view.put("kitchenFull", room.isKitchenFull());
        view.put("kitchenSealed", room.isKitchenSealed());
        view.put("estimatedRestoreAt", room.getEstimatedRestoreAt());
        view.put("sealedAt", room.getSealedAt());
        view.put("sealedNote", room.getSealedNote());
        view.put("alternativePoints", nearestAvailable(room));
        return view;
    }

    /** 最近可用投放点：优先同小区未满溢/未封桶垃圾房，按厨余容量升序，推荐物业指定备用点 */
    private List<Map<String, Object>> nearestAvailable(GarbageRoom sealedRoom) {
        List<GarbageRoom> candidates = roomRepository.findAll().stream()
                .filter(r -> !r.getCode().equals(sealedRoom.getCode()))
                .filter(r -> !r.isKitchenSealed())
                .filter(r -> !(r.isKitchenFull() && !r.isTruckPresent()))
                .toList();
        List<GarbageRoom> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator
                .comparing((GarbageRoom r) -> !Objects.equals(r.getCommunityId(), sealedRoom.getCommunityId()))
                .thenComparingInt(r -> r.getKitchenCapacityPct() == null ? 0 : r.getKitchenCapacityPct()));

        String recommended = sealedRoom.getAlternativeRoomCode();
        List<Map<String, Object>> result = new ArrayList<>();
        for (GarbageRoom r : sorted) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("roomCode", r.getCode());
            m.put("roomName", r.getName());
            m.put("address", r.getAddress());
            m.put("sameCommunity", Objects.equals(r.getCommunityId(), sealedRoom.getCommunityId()));
            communityRepository.findById(r.getCommunityId()).ifPresent(c -> {
                m.put("communityName", c.getName());
                m.put("streetName", c.getStreetName());
            });
            m.put("kitchenCapacityPct", r.getKitchenCapacityPct());
            m.put("recommended", r.getCode().equals(recommended));
            result.add(m);
        }
        return result;
    }

    private GarbageRoom requireRoom(String code) {
        return roomRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException("垃圾房不存在: " + code));
    }
}
