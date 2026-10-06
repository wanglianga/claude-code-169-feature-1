package com.city.garbage.service;

import com.city.garbage.config.LinkageProperties;
import com.city.garbage.domain.*;
import com.city.garbage.kafka.GovernanceEventPublisher;
import com.city.garbage.repo.*;
import com.city.garbage.support.TimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 满溢清运超时升级编排：
 * 1) 厨余桶满溢持续 {@code binSealMinutes} 分钟清运车仍未到：
 *    先通知物业临时封桶、引导居民到备用点，再通知环卫调整路线（清运任务升级），
 *    居民端展示封桶状态、最近可用投放点与预计恢复时间；
 * 2) 封桶后再持续 {@code streetOverdueMinutes} 分钟车仍未到：街道端收到超时督办提醒，
 *    联动单记录清运延迟原因与居民投诉数量；
 * 3) 清运车到场/离场或容量回落：解除封桶与全部升级告警，清运离场同步收口现场管理问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionEscalationService {

    private final GarbageRoomRepository roomRepository;
    private final GovernanceCaseRepository caseRepository;
    private final SiteIssueRepository siteIssueRepository;
    private final NotificationRecordRepository notificationRepository;
    private final LinkageEngine engine;
    private final LinkageProperties props;
    private final TimeService timeService;
    private final GovernanceEventPublisher eventPublisher;

    /** 定时巡检入口：封桶 -> 街道超时升级 */
    @Transactional
    public void checkSealAndEscalate() {
        OffsetDateTime now = timeService.now();
        for (GarbageRoom room : roomRepository.findAll()) {
            if (!room.isKitchenFull() || room.isTruckPresent()) continue;
            OffsetDateTime since = room.getKitchenFullSince();
            if (since == null) continue;
            long fullMins = java.time.Duration.between(since, now).toMinutes();

            if (!room.isSealed() && fullMins >= props.getBinSealMinutes()) {
                sealBin(room, now);
            }
            if (!room.isOverdueNotified()
                    && fullMins >= props.getBinSealMinutes() + props.getStreetOverdueMinutes()) {
                escalateToStreet(room, now);
            }
        }
    }

    /** 物业临时封桶：派封桶引导任务，先通知物业再通知环卫调线，居民端同步引导备用点 */
    @Transactional
    public void sealBin(GarbageRoom room, OffsetDateTime now) {
        room.setSealed(true);
        room.setSealedAt(now);
        room.setEstimatedResumeAt(now.plusMinutes(props.getEstimatedResumeMinutes()));
        roomRepository.save(room);

        Alert alert = engine.raiseAlert(room, AlertType.BIN_SEALED, AlertSeverity.HIGH,
                "厨余桶满溢持续" + props.getBinSealMinutes() + "分钟清运车未到，物业临时封桶并引导居民改投备用点",
                "BIN_SEALED:" + room.getCode(), null, null);

        // 1) 先通知物业：临时封桶 + 居民引导
        notify("PROPERTY", "BIN_SEAL", room, alert.getCaseNo(),
                "请立即对" + room.getName() + "厨余桶临时封桶，张贴停用告示并引导居民改投最近备用点，预计"
                        + props.getEstimatedResumeMinutes() + "分钟内恢复");
        // 2) 再通知环卫：调整清运路线、优先调度
        notify("SANITATION", "ROUTE_REROUTE", room, alert.getCaseNo(),
                room.getName() + "厨余桶满溢已封桶，居民改投备用点，请立即调整清运路线优先派车");
        // 居民 App 通过投放引导接口获取备用点与预计恢复时间，此处同步广播留痕
        notify("RESIDENT", "SEAL_GUIDE", room, alert.getCaseNo(),
                room.getName() + "厨余桶临时封桶，请勿在此投放，请前往最近备用点，预计"
                        + props.getEstimatedResumeMinutes() + "分钟内恢复");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "BIN_SEALED");
        payload.put("roomCode", room.getCode());
        payload.put("caseNo", alert.getCaseNo());
        payload.put("sealedAt", now);
        payload.put("estimatedResumeAt", room.getEstimatedResumeAt());
        eventPublisher.publish(room.getCode(), payload);
        log.info("厨余满溢封桶升级 room={} case={} estimatedResume={}",
                room.getCode(), alert.getCaseNo(), room.getEstimatedResumeAt());
    }

    /** 清运仍未到场：街道端超时督办，联动单记录升级时间 */
    @Transactional
    public void escalateToStreet(GarbageRoom room, OffsetDateTime now) {
        room.setOverdueNotified(true);
        roomRepository.save(room);

        Alert alert = engine.raiseAlert(room, AlertType.COLLECTION_OVERDUE, AlertSeverity.URGENT,
                "封桶后超过" + props.getStreetOverdueMinutes() + "分钟清运车仍未到场，升级街道督办",
                "COLLECTION_OVERDUE:" + room.getCode(), null, null);

        if (alert.getCaseNo() != null) {
            caseRepository.findByCaseNo(alert.getCaseNo()).ifPresent(gc -> {
                if (gc.getEscalatedAt() == null) gc.setEscalatedAt(now);
                caseRepository.save(gc);
            });
        }

        notify("STREET", "OVERDUE_ESCALATION", room, alert.getCaseNo(),
                room.getName() + "厨余桶满溢封桶后清运车超时" + props.getStreetOverdueMinutes()
                        + "分钟仍未到场，请督办环卫并回填清运延迟原因");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "COLLECTION_OVERDUE");
        payload.put("roomCode", room.getCode());
        payload.put("caseNo", alert.getCaseNo());
        payload.put("escalatedAt", now);
        eventPublisher.publish(room.getCode(), payload);
        log.warn("清运超时升级街道督办 room={} case={}", room.getCode(), alert.getCaseNo());
    }

    /** 解除封桶状态并销除封桶/超时升级告警（车到场或容量回落） */
    @Transactional
    public void clearSealState(GarbageRoom room, String note) {
        if (!room.isSealed() && !room.isOverdueNotified()
                && room.getSealedAt() == null && room.getEstimatedResumeAt() == null) {
            return;
        }
        engine.resolveByType(room.getCode(), AlertType.BIN_SEALED, "封桶解除：" + note);
        engine.resolveByType(room.getCode(), AlertType.COLLECTION_OVERDUE, "清运恢复：" + note);
        room.setSealed(false);
        room.setSealedAt(null);
        room.setEstimatedResumeAt(null);
        room.setOverdueNotified(false);
        roomRepository.save(room);
        log.info("封桶状态解除 room={} note={}", room.getCode(), note);
    }

    /** 清运离场、桶已清空：收口该点封桶期间产生的全部未处置现场管理问题 */
    @Transactional
    public int closeOpenSiteIssues(String roomCode, String note) {
        List<SiteIssue> open = siteIssueRepository.findByRoomCodeAndStatusOrderByCreatedAtAsc(
                roomCode, SiteIssueStatus.OPEN);
        OffsetDateTime now = timeService.now();
        for (SiteIssue issue : open) {
            issue.setStatus(SiteIssueStatus.HANDLED);
            issue.setHandledAt(now);
            issue.setHandleNote(note);
            siteIssueRepository.save(issue);
            if (issue.getTaskId() != null) {
                // 现场问题随清运清理一并处置，对应督导任务自动完成
                engine.markDoneById(issue.getTaskId(), note);
            }
        }
        return open.size();
    }

    private void notify(String targetRole, String type, GarbageRoom room, String caseNo, String content) {
        String dedupe = type + ":" + room.getCode() + ":" + (room.getKitchenFullSince() == null
                ? "" : room.getKitchenFullSince().toEpochSecond());
        if (notificationRepository.existsByDedupeKey(dedupe)) return;
        NotificationRecord n = new NotificationRecord();
        n.setTargetRole(targetRole);
        n.setNotifyType(type);
        n.setRoomCode(room.getCode());
        n.setCaseNo(caseNo);
        n.setContent(content);
        n.setDedupeKey(dedupe);
        n.setCreatedAt(timeService.now());
        notificationRepository.save(n);
    }
}
